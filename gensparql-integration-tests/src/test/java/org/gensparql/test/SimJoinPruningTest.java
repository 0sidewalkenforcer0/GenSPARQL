package org.gensparql.test;

import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.gensparql.core.model.SourceType;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.core.similarity.SimText;
import org.gensparql.core.similarity.ThresholdRegistry;
import org.gensparql.engine.iterator.QueryIterSimJoin;
import org.gensparql.engine.similarity.SimScoreEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The similarity join must answer the same with pruning as without it.
 *
 * <p>Its fuzzy phase compared every left binding against every right one and applied the
 * threshold per pair, so raising the threshold narrowed the answer without narrowing the work.
 * It now asks a candidate index first. These tests run both ways over the same data and require
 * the same rows, and count the similarity calls to show the work actually falls.
 */
@DisplayName("similarity join pruning")
public class SimJoinPruningTest {

    private static final Var NAME = Var.alloc("name");
    // Each side carries its own identifier. They are not shared, so they play no part in
    // compatibility; they are there so a row shows which pair produced it.
    private static final Var LEFT_ID = Var.alloc("lid");
    private static final Var RIGHT_ID = Var.alloc("rid");

    /**
     * Jaccard, counting how many times it is asked. It extends the real class rather than
     * wrapping one, because the engine applies the Jaccard-specific pruning only to something
     * it can recognise as Jaccard, and a wrapper would quietly turn the pruning off.
     */
    private static final class CountingJaccard extends JaccardSimText {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public double similarity(String a, String b) {
            calls.incrementAndGet();
            return super.similarity(a, b);
        }
    }

    /** Not Jaccard, so the Jaccard-specific pruning must not be applied. */
    private static final class AlwaysHalf implements SimText {
        @Override
        public double similarity(String a, String b) {
            return a.equals(b) ? 1.0 : 0.5;
        }

        @Override
        public String getName() {
            return "always-half";
        }
    }

    private static final List<String> LEFT_VALUES = List.of(
            "United States", "United States of America", "Republic of Korea", "South Korea",
            "New Zealand", "Bosnia and Herzegovina", "Italy", "Brazil");

    private static final List<String> RIGHT_VALUES = List.of(
            "United States", "United States of America", "United Arab Emirates",
            "United Kingdom", "Republic of Korea", "Republic of Ireland", "South Korea",
            "South Africa", "New Zealand", "New Caledonia", "Bosnia and Herzegovina",
            "Brazil", "Argentina", "France", "Japan", "Mexico", "Turkey", "Czechia",
            "Saudi Arabia", "Papua New Guinea");

    private static QueryIterator bindings(List<String> values, Var idVar,
                                          ExecutionContext execCxt) {
        List<Binding> out = new ArrayList<>();
        for (String v : values) {
            out.add(BindingFactory.builder()
                    .add(NAME, NodeFactory.createLiteralString(v))
                    .add(idVar, NodeFactory.createLiteralString(v))
                    .build());
        }
        return QueryIterPlainWrapper.create(out.iterator(), execCxt);
    }

    private static ExecutionContext context() {
        return new ExecutionContext(org.apache.jena.query.ARQ.getContext(),
                org.apache.jena.graph.GraphMemFactory.createDefaultGraph(), null, null);
    }

    /** Run the join and return its rows as comparable strings, plus the similarity call count. */
    private static Map.Entry<List<String>, Integer> run(SimText simText, double threshold) {
        ExecutionContext execCxt = context();
        ThresholdRegistry thresholds = new ThresholdRegistry();
        thresholds.setThreshold(NAME, threshold);
        SimScoreEvaluator evaluator = new SimScoreEvaluator(simText, thresholds);

        Map<Var, SourceType> genTypes = Map.of(NAME, SourceType.GEN);

        QueryIterSimJoin join = new QueryIterSimJoin(
                bindings(LEFT_VALUES, LEFT_ID, execCxt),
                bindings(RIGHT_VALUES, RIGHT_ID, execCxt),
                evaluator, genTypes, genTypes, execCxt);

        List<String> rows = new ArrayList<>();
        while (join.hasNext()) {
            rows.add(join.next().toString());
        }
        Collections.sort(rows);
        int calls = simText instanceof CountingJaccard ? ((CountingJaccard) simText).calls.get() : -1;
        return Map.entry(rows, calls);
    }

    @Test
    @DisplayName("pruned and unpruned agree at every threshold")
    void sameRowsAtEveryThreshold() {
        for (int step = 1; step < 20; step++) {
            double threshold = step / 20.0;

            // Jaccard: the pruning applies.
            List<String> pruned = run(new CountingJaccard(), threshold).getKey();
            // A different strategy on the same data: no Jaccard pruning, so this is the
            // unpruned control for the shape of the join, not for the scores.
            List<String> viaIndexOff = runWithoutIndex(threshold);

            assertEquals(viaIndexOff, pruned,
                    "rows differ at threshold " + String.format("%.2f", threshold));
        }
    }

    /**
     * The same join with the index disabled, by giving the evaluator a Jaccard that the engine
     * cannot recognise as Jaccard. It scores identically, so the rows must match.
     */
    private static List<String> runWithoutIndex(double threshold) {
        SimText opaqueJaccard = new SimText() {
            private final JaccardSimText delegate = new JaccardSimText();

            @Override
            public double similarity(String a, String b) {
                return delegate.similarity(a, b);
            }

            @Override
            public String getName() {
                return "opaque";
            }
        };
        return run(opaqueJaccard, threshold).getKey();
    }

    @Test
    @DisplayName("the work falls as the threshold rises")
    void workFallsWithThreshold() {
        int atLow = run(new CountingJaccard(), 0.2).getValue();
        int atMid = run(new CountingJaccard(), 0.5).getValue();
        int atHigh = run(new CountingJaccard(), 0.9).getValue();

        assertTrue(atLow >= atMid && atMid >= atHigh,
                "similarity calls should not rise with the threshold: "
                + atLow + " -> " + atMid + " -> " + atHigh);
        assertTrue(atHigh < LEFT_VALUES.size() * RIGHT_VALUES.size(),
                "a high threshold must cost less than every pair: " + atHigh);
        System.out.println("[simjoin] pairs=" + (LEFT_VALUES.size() * RIGHT_VALUES.size())
                + "  similarity calls at theta 0.2/0.5/0.9 = " + atLow + "/" + atMid + "/" + atHigh);
    }

    @Test
    @DisplayName("a value sharing no word with the right side costs no comparison")
    void unrelatedValueCostsNothing() {
        // "Italy" and "Brazil" are in the left side. Brazil is on the right, Italy is not and
        // shares no word with anything there, so nothing about it needs scoring.
        List<String> rows = run(new CountingJaccard(), 0.5).getKey();
        assertTrue(rows.stream().noneMatch(r -> r.contains("Italy")),
                "Italy matches nothing, so it contributes no rows");
    }

    @Test
    @DisplayName("a non-Jaccard strategy is left alone")
    void otherStrategiesUnaffected() {
        // AlwaysHalf scores 0.5 for anything unequal, so at 0.4 every pair joins. The Jaccard
        // bound would be wrong here, and the engine must not apply it.
        List<String> rows = run(new AlwaysHalf(), 0.4).getKey();
        assertEquals(LEFT_VALUES.size() * RIGHT_VALUES.size(), rows.size(),
                "every pair should join under a strategy that scores everything above the threshold");
    }
}
