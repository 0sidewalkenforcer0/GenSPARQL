package org.gensparql.engine.iterator;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.iterator.QueryIteratorBase;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.gensparql.core.model.SourceType;
import org.gensparql.core.model.TypedBinding;
import org.gensparql.core.model.TypedValue;
import org.gensparql.core.similarity.CanonicalForm;
import org.gensparql.core.similarity.SimText;
import org.gensparql.engine.similarity.EmbeddingSimText;
import org.gensparql.engine.similarity.SimScoreEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Similarity-based join iterator for GenSPARQL.
 *
 * Uses SimScore instead of exact equality for binding compatibility.
 * Implements the COM (compatible) predicate from the GenSPARQL paper:
 *
 * COM(μ₁, μ₂) ⟺ ∀v ∈ dom(μ₁) ∩ dom(μ₂): SimScore(μ₁(v), μ₂(v), v) = ⊤
 *
 * <h2>Performance</h2>
 * A naive N×M scan that calls an embedding model for every pair does not scale:
 * on a single join it triggered hundreds of sequential embedding requests
 * (minutes of wall-clock). This implementation therefore:
 * <ol>
 *   <li><b>Exact-match first.</b> A pre-built hash index over the right side,
 *       keyed by the normalized join-variable labels, resolves exact matches in
 *       O(1) with no similarity computation at all. For constrained generation
 *       (where the model copies exact KG labels) this handles every match.</li>
 *   <li><b>Exact-only fast path.</b> When every join variable requires an exact
 *       match (threshold ≥ 1.0), the fuzzy scan is skipped entirely.</li>
 *   <li><b>Batched pre-warm.</b> When approximate matching is needed and the
 *       similarity strategy is embedding-based, all join-variable labels are
 *       embedded in a few batched requests up front, so the fuzzy scan performs
 *       in-memory cosine comparisons instead of per-pair API round-trips.</li>
 * </ol>
 * Results are identical to the naive scan (every candidate is still verified with
 * the {@link SimScoreEvaluator}); only their order may differ, which is
 * irrelevant to SPARQL bag semantics.
 */
public class QueryIterSimJoin extends QueryIteratorBase {
    private static final Logger LOG = LoggerFactory.getLogger(QueryIterSimJoin.class);

    private final List<TypedBinding> leftBindings;
    private final List<TypedBinding> rightBindings;
    private final SimScoreEvaluator simScoreEvaluator;
    private final ExecutionContext execCxt;
    private final Map<Var, SourceType> leftSourceTypes;
    private final Map<Var, SourceType> rightSourceTypes;

    // Pre-built exact-match index over the right side.
    private final List<Var> joinVars;
    private final boolean approximate;
    private final Map<String, List<TypedBinding>> rightIndex = new HashMap<>();
    private final List<String> rightKeys = new ArrayList<>(); // aligned with rightBindings

    private Iterator<TypedBinding> leftIter;
    private TypedBinding currentLeft;
    private Iterator<TypedBinding> matchIter;
    private boolean exhausted = false;

    /**
     * Create a similarity join iterator.
     *
     * @param left            left side iterator
     * @param right           right side iterator
     * @param evaluator       SimScore evaluator
     * @param leftSourceTypes source types for left side variables
     * @param rightSourceTypes source types for right side variables
     * @param execCxt         execution context
     */
    public QueryIterSimJoin(QueryIterator left, QueryIterator right,
                            SimScoreEvaluator evaluator,
                            Map<Var, SourceType> leftSourceTypes,
                            Map<Var, SourceType> rightSourceTypes,
                            ExecutionContext execCxt) {
        this.simScoreEvaluator = evaluator;
        this.execCxt = execCxt;
        this.leftSourceTypes = leftSourceTypes != null ? leftSourceTypes : Collections.emptyMap();
        this.rightSourceTypes = rightSourceTypes != null ? rightSourceTypes : Collections.emptyMap();

        // Materialize both sides.
        this.leftBindings = materialize(left, this.leftSourceTypes);
        this.rightBindings = materialize(right, this.rightSourceTypes);
        this.leftIter = leftBindings.iterator();

        // Build the pre-computed index and decide whether a fuzzy scan is needed.
        this.joinVars = computeJoinVars();
        this.approximate = computeApproximate();
        buildRightIndex();
        maybeWarmEmbeddings();

        if (LOG.isDebugEnabled()) {
            LOG.debug("SimJoin init: {} left, {} right, joinVars={}, approximate={}, index buckets={}",
                    leftBindings.size(), rightBindings.size(), joinVars, approximate, rightIndex.size());
        }
    }

    /**
     * Simplified constructor that infers source types.
     */
    public QueryIterSimJoin(QueryIterator left, QueryIterator right,
                            SimScoreEvaluator evaluator,
                            ExecutionContext execCxt) {
        this(left, right, evaluator, null, null, execCxt);
    }

    /**
     * Materialize an iterator into a list of TypedBindings.
     */
    private List<TypedBinding> materialize(QueryIterator iter, Map<Var, SourceType> sourceTypes) {
        List<TypedBinding> list = new ArrayList<>();
        while (iter.hasNext()) {
            Binding b = iter.next();
            TypedBinding tb = toTypedBinding(b, sourceTypes);
            list.add(tb);
        }
        iter.close();
        return list;
    }

    /**
     * Convert a Binding to a TypedBinding with source type information.
     */
    private TypedBinding toTypedBinding(Binding binding, Map<Var, SourceType> knownTypes) {
        TypedBinding.Builder builder = TypedBinding.builder();
        binding.vars().forEachRemaining(var -> {
            Node node = binding.get(var);
            SourceType type = knownTypes.getOrDefault(var, SourceType.RDF);
            builder.add(var, node, type);
        });
        return builder.build();
    }

    /** Variables shared by both sides (the join key), in a deterministic order. */
    private List<Var> computeJoinVars() {
        Set<Var> leftVars = new LinkedHashSet<>();
        for (TypedBinding tb : leftBindings) {
            leftVars.addAll(tb.varSet());
        }
        Set<Var> rightVars = new HashSet<>();
        for (TypedBinding tb : rightBindings) {
            rightVars.addAll(tb.varSet());
        }
        List<Var> shared = new ArrayList<>();
        for (Var v : leftVars) {
            if (rightVars.contains(v)) {
                shared.add(v);
            }
        }
        shared.sort(Comparator.comparing(Var::getName));
        return shared;
    }

    /** True if any join variable allows approximate (non-exact) matches. */
    private boolean computeApproximate() {
        for (Var v : joinVars) {
            if (simScoreEvaluator.getThreshold(v) < 1.0) {
                return true;
            }
        }
        return false;
    }

    private void buildRightIndex() {
        if (joinVars.isEmpty()) {
            return;
        }
        for (TypedBinding tb : rightBindings) {
            String key = keyOf(tb);
            rightKeys.add(key);
            if (key != null) {
                rightIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(tb);
            }
        }
    }

    /**
     * Normalized join key for a binding, or {@code null} if any join variable is
     * unbound/null (such bindings cannot be indexed and are handled by scanning).
     * Uses the same lexical/canonical forms the {@link SimScoreEvaluator} compares.
     */
    private String keyOf(TypedBinding tb) {
        StringBuilder sb = new StringBuilder();
        for (Var v : joinVars) {
            TypedValue tv = tb.getTypedValue(v);
            if (tv == null || tv.getNode() == null) {
                return null;
            }
            String s = tv.isGen() ? CanonicalForm.lex(tv.getNode()) : CanonicalForm.canon(tv.getNode());
            sb.append(CanonicalForm.normalize(s)).append('\u0001');
        }
        return sb.toString();
    }

    /** Batch-embed all join-variable labels up front when a fuzzy scan will run. */
    private void maybeWarmEmbeddings() {
        if (!approximate || joinVars.isEmpty()) {
            return;
        }
        SimText st = simScoreEvaluator.getSimText();
        if (!(st instanceof EmbeddingSimText)) {
            return; // Jaccard etc. are in-memory; nothing to pre-warm.
        }
        Set<String> labels = new HashSet<>();
        collectLabels(leftBindings, labels);
        collectLabels(rightBindings, labels);
        if (!labels.isEmpty()) {
            LOG.debug("SimJoin pre-warming {} embeddings (batched)", labels.size());
            EmbeddingSimText.warmUp(labels);
        }
    }

    private void collectLabels(List<TypedBinding> bindings, Set<String> out) {
        for (TypedBinding tb : bindings) {
            for (Var v : joinVars) {
                TypedValue tv = tb.getTypedValue(v);
                if (tv == null || tv.getNode() == null) {
                    continue;
                }
                String s = tv.isGen() ? CanonicalForm.lex(tv.getNode()) : CanonicalForm.canon(tv.getNode());
                if (s != null && !s.isEmpty()) {
                    out.add(s);
                }
            }
        }
    }

    @Override
    protected boolean hasNextBinding() {
        if (exhausted) {
            return false;
        }

        while (true) {
            if (matchIter != null && matchIter.hasNext()) {
                return true;
            }
            if (!leftIter.hasNext()) {
                exhausted = true;
                return false;
            }
            currentLeft = leftIter.next();
            matchIter = findCompatibleBindings(currentLeft).iterator();
        }
    }

    @Override
    protected Binding moveToNextBinding() {
        TypedBinding rightBinding = matchIter.next();
        TypedBinding merged = TypedBinding.merge(currentLeft, rightBinding);
        return merged.getBaseBinding();
    }

    /**
     * Find all right bindings compatible with the left binding, using the exact
     * index first and only falling back to a scan for approximate matches.
     */
    private List<TypedBinding> findCompatibleBindings(TypedBinding left) {
        // No shared variables => Cartesian product (all right bindings compatible).
        if (joinVars.isEmpty()) {
            return rightBindings;
        }

        String leftKey = keyOf(left);
        List<TypedBinding> compatible = new ArrayList<>();

        // Phase 1 -- exact match via the pre-built index (no similarity/embeddings).
        if (leftKey != null) {
            List<TypedBinding> exact = rightIndex.get(leftKey);
            if (exact != null) {
                for (TypedBinding right : exact) {
                    if (areCompatible(left, right)) {
                        compatible.add(right);
                    }
                }
            }
        }

        // Phase 2 -- approximate scan (skip the exact bucket already handled).
        if (approximate) {
            for (int i = 0; i < rightBindings.size(); i++) {
                String rKey = rightKeys.get(i);
                if (leftKey != null && leftKey.equals(rKey)) {
                    continue; // already handled exactly in phase 1
                }
                TypedBinding right = rightBindings.get(i);
                if (areCompatible(left, right)) {
                    compatible.add(right);
                }
            }
        } else if (leftKey == null) {
            // Exact-only mode but the left key is unbound: scan to honour the
            // null==null compatibility semantics of SimScore.
            for (TypedBinding right : rightBindings) {
                if (areCompatible(left, right)) {
                    compatible.add(right);
                }
            }
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("Found {} compatible bindings for left: {}", compatible.size(), left);
        }
        return compatible;
    }

    /**
     * Check COM(μ₁, μ₂): compatibility using SimScore.
     *
     * Two bindings are compatible if for all shared variables,
     * SimScore evaluates to true.
     */
    private boolean areCompatible(TypedBinding b1, TypedBinding b2) {
        Set<Var> shared = new HashSet<>();
        Set<Var> b1Vars = b1.varSet();
        Set<Var> b2Vars = b2.varSet();
        for (Var v : b1Vars) {
            if (b2Vars.contains(v)) {
                shared.add(v);
            }
        }

        // If no shared variables, they are compatible (can be merged).
        if (shared.isEmpty()) {
            return true;
        }

        for (Var var : shared) {
            TypedValue tv1 = b1.getTypedValue(var);
            TypedValue tv2 = b2.getTypedValue(var);
            if (!simScoreEvaluator.evaluate(tv1, tv2, var)) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Bindings not compatible at var ?{}: {} vs {}",
                            var.getName(),
                            tv1 != null ? tv1.getLexicalForm() : "null",
                            tv2 != null ? tv2.getLexicalForm() : "null");
                }
                return false;
            }
        }
        return true;
    }

    @Override
    protected void closeIterator() {
        // No resources to close beyond what was materialized
    }

    @Override
    protected void requestCancel() {
        exhausted = true;
    }

    @Override
    public void output(IndentedWriter out, SerializationContext sCxt) {
        out.print("QueryIterSimJoin(");
        out.print("left=" + leftBindings.size());
        out.print(", right=" + rightBindings.size());
        out.print(", exactBuckets=" + rightIndex.size());
        out.print(", approximate=" + approximate);
        out.print(")");
    }

    /**
     * Get statistics about this join operation.
     */
    public JoinStats getStats() {
        return new JoinStats(leftBindings.size(), rightBindings.size());
    }

    /**
     * Statistics about the join operation.
     */
    public static class JoinStats {
        private final int leftCount;
        private final int rightCount;

        public JoinStats(int leftCount, int rightCount) {
            this.leftCount = leftCount;
            this.rightCount = rightCount;
        }

        public int getLeftCount() {
            return leftCount;
        }

        public int getRightCount() {
            return rightCount;
        }

        public long getMaxComparisons() {
            return (long) leftCount * rightCount;
        }

        @Override
        public String toString() {
            return String.format("JoinStats{left=%d, right=%d, maxComparisons=%d}",
                    leftCount, rightCount, getMaxComparisons());
        }
    }
}
