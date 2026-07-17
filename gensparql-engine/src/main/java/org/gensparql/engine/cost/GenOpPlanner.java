package org.gensparql.engine.cost;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cost-based reordering planner for GENOP-bearing conjunctive fragments — component C4.
 *
 * <p>Given a set of plan items (KG patterns and GENOPs), it finds a legal order that
 * minimizes total LLM cost, using the C1 cost model ({@link GenOpCostModel}) and the C2
 * cardinality inputs. The lever is the classic expensive-predicate move (Hellerstein-
 * Stonebraker 1993; Chaudhuri-Shim 1996) applied to a generative operator: run selective KG
 * patterns before an expensive GENOP so it fires over fewer bindings.
 *
 * <p><b>Legality (safe under the semantics paper's rewrite rules).</b> The only ordering
 * constraint is a dependency one — a GENOP's input variables X must be bound by items placed
 * before it (Prop 6 side condition {@code X ⊆ var(preceding)}). Pure joins are commutative and
 * associative, so any dependency-respecting order is result-equivalent (Prop 6 safe join
 * reordering; Theorem 6 topological-order invariance) and we may freely pick the cheapest.
 * NOTE: this holds for the deterministic-oracle reading; combine with C3 dedup / record-replay
 * for reproducibility (see docs §2). OPTIONAL/left-join is out of scope until the dom(μ)
 * condition is settled.
 *
 * <p><b>Algorithm.</b> Held-Karp subset DP over placed items. Running cardinality after a set
 * S is order-independent (a product of per-item cardinality factors), so only the position of
 * a GENOP relative to selective patterns changes its cost. DP is O(2^n · n^2) (boundVars and
 * cardinality are recomputed per state); query fragments are small (capped at n=20), and
 * dependency pruning shrinks the space further. Exact optimum.
 */
public final class GenOpPlanner {

    /** An item in a conjunctive fragment. */
    public sealed interface PlanItem permits KgPattern, GenOpItem {
        String label();
        Set<String> requires();
        Set<String> binds();
        /** Expected rows produced per incoming binding (a selectivity/fan-out multiplier). */
        double cardinalityFactor();
    }

    /** A KG pattern/filter: negligible LLM cost, multiplies the binding count. */
    public record KgPattern(String label, Set<String> requires, Set<String> binds,
                            double cardinalityFactor) implements PlanItem {
    }

    /** A generative operator: requires input vars X, produces Y, expensive per binding. */
    public record GenOpItem(String label, Set<String> requires, Set<String> binds,
                            double fanOut, int promptTokens, int completionTokens,
                            double dedupRatio) implements PlanItem {
        @Override
        public double cardinalityFactor() {
            return fanOut;
        }
    }

    public record PlanResult(List<PlanItem> order, long totalLlmCalls,
                             double totalDollars, double totalLatencyMs) {
    }

    private static final double INF = Double.POSITIVE_INFINITY;

    /**
     * Compute the cost-minimal legal order.
     *
     * @param items           the fragment
     * @param baseCardinality starting binding count (typically 1 for the empty binding)
     * @param model           the C1 cost model
     * @param dedup           whether C3 cross-binding dedup is in effect
     */
    public PlanResult plan(List<PlanItem> items, double baseCardinality,
                           GenOpCostModel model, boolean dedup) {
        int n = items.size();
        if (n == 0) {
            return new PlanResult(List.of(), 0, 0, 0);
        }
        if (n > 20) {
            throw new IllegalArgumentException("fragment too large for exact DP: " + n);
        }

        int full = (1 << n) - 1;
        double[] dp = new double[1 << n];
        int[] parent = new int[1 << n];      // last item index placed to reach this mask
        java.util.Arrays.fill(dp, INF);
        java.util.Arrays.fill(parent, -1);
        dp[0] = 0.0;

        for (int mask = 0; mask <= full; mask++) {
            if (dp[mask] == INF) {
                continue;
            }
            Set<String> bound = boundVars(items, mask);
            double cardBefore = cardinality(items, mask, baseCardinality);
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    continue;
                }
                PlanItem it = items.get(i);
                if (!bound.containsAll(it.requires())) {
                    continue; // dependency not yet satisfied (Prop 6 precondition)
                }
                double added = placementCost(it, cardBefore, model, dedup);
                int next = mask | (1 << i);
                if (dp[mask] + added < dp[next]) {
                    dp[next] = dp[mask] + added;
                    parent[next] = i;
                }
            }
        }

        if (dp[full] == INF) {
            throw new IllegalArgumentException(
                    "no legal ordering: some GENOP input variables are never bound");
        }

        // Reconstruct order.
        List<PlanItem> order = new ArrayList<>(n);
        for (int mask = full; mask != 0; ) {
            int i = parent[mask];
            order.add(0, items.get(i));
            mask &= ~(1 << i);
        }

        // Accumulate reportable costs along the chosen order.
        long totalCalls = 0;
        double totalDollars = 0, totalLatency = 0;
        double card = baseCardinality;
        for (PlanItem it : order) {
            if (it instanceof GenOpItem g) {
                GenOpCostModel.Estimate e = estimateGenOp(g, card, model, dedup);
                totalCalls += e.llmCalls();
                totalDollars += e.dollars();
                totalLatency += e.latencyMs();
            }
            card *= it.cardinalityFactor();
        }
        return new PlanResult(order, totalCalls, totalDollars, totalLatency);
    }

    private static double placementCost(PlanItem it, double cardBefore,
                                        GenOpCostModel model, boolean dedup) {
        if (it instanceof GenOpItem g) {
            return estimateGenOp(g, cardBefore, model, dedup).llmCalls();
        }
        return 0.0; // KG pattern: negligible vs. the LLM
    }

    private static GenOpCostModel.Estimate estimateGenOp(GenOpItem g, double cardBefore,
                                                         GenOpCostModel model, boolean dedup) {
        long nBindings = Math.max(0, Math.round(cardBefore));
        long distinct = nBindings;
        if (dedup && nBindings > 0) {
            distinct = Math.max(1, Math.round(nBindings * g.dedupRatio()));
            distinct = Math.min(distinct, nBindings);
        }
        return model.estimate(new GenOpCostModel.Inputs(
                nBindings, distinct, g.fanOut(), g.promptTokens(), g.completionTokens()), dedup);
    }

    private static Set<String> boundVars(List<PlanItem> items, int mask) {
        Set<String> bound = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            if ((mask & (1 << i)) != 0) {
                bound.addAll(items.get(i).binds());
            }
        }
        return bound;
    }

    private static double cardinality(List<PlanItem> items, int mask, double base) {
        double c = base;
        for (int i = 0; i < items.size(); i++) {
            if ((mask & (1 << i)) != 0) {
                c *= items.get(i).cardinalityFactor();
            }
        }
        return c;
    }
}
