package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.cost.FanOutEstimator;
import org.gensparql.engine.cost.KgStats;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the fan-out estimator and KG statistics (C2). */
public class FanOutEstimatorTest {

    // ---- Tier 1: static prompt prior ----

    @Test
    void testExplicitCardinal() {
        assertEquals(5.0, FanOutEstimator.promptPrior("List five Nobel physicists."), 1e-9);
        assertEquals(3.0, FanOutEstimator.promptPrior("Name three tools for {?field}."), 1e-9);
        assertEquals(10.0, FanOutEstimator.promptPrior("Give the top 10 results for {?x}."), 1e-9);
    }

    @Test
    void testSingleAnswerIntent() {
        assertEquals(1.0, FanOutEstimator.promptPrior("What is the capital of {?country}?"), 1e-9);
        assertEquals(1.0, FanOutEstimator.promptPrior("Which element has symbol {?s}?"), 1e-9);
    }

    @Test
    void testListIntentAndDefault() {
        assertEquals(FanOutEstimator.DEFAULT_LIST_SIZE,
                FanOutEstimator.promptPrior("List examples of {?field}."), 1e-9);
        assertEquals(FanOutEstimator.DEFAULT_UNKNOWN,
                FanOutEstimator.promptPrior("Describe {?field}."), 1e-9);
    }

    @Test
    void testMultiLinePromptIntentDetected() {
        // Newline-safe: intent on a later line must still be seen (regression: matches()+".*").
        assertEquals(FanOutEstimator.DEFAULT_LIST_SIZE,
                FanOutEstimator.promptPrior("Consider {?x}.\nList all related items."), 1e-9);
        assertEquals(5.0,
                FanOutEstimator.promptPrior("For {?x}:\nlist 5 examples."), 1e-9);
    }

    @Test
    void testIncidentalNumberNotTreatedAsCardinal() {
        // "2 sentences" is a formatting hint, not a result count.
        assertEquals(1.0,
                FanOutEstimator.promptPrior("What is the capital of {?c}? Answer in 2 sentences."), 1e-9);
        assertEquals(FanOutEstimator.DEFAULT_UNKNOWN,
                FanOutEstimator.promptPrior("Describe {?x} in 2 sentences."), 1e-9);
    }

    // ---- Tier 3: online feedback ----

    @Test
    void testOnlineFeedbackMovesTowardObserved() {
        FanOutEstimator est = FanOutEstimator.withPrior(10.0);
        assertEquals(10.0, est.estimate(), 1e-9);
        for (int i = 0; i < 20; i++) {
            est.observe(2); // true fan-out is consistently 2
        }
        assertTrue(est.estimate() < 4.0,
                "estimate should converge toward observed fan-out (2), got " + est.estimate());
        assertEquals(20, est.observations());
    }

    // ---- KG statistics: distinct-prompt (D) prediction, the C2<->C3 bridge ----

    static boolean dataAvailable() {
        return locateTtl() != null;
    }

    private static File locateTtl() {
        for (String c : new String[]{
                "../gensparql-example/data/scientists_awards_large.ttl",
                "gensparql-example/data/scientists_awards_large.ttl"}) {
            File f = new File(c);
            if (f.exists()) return f;
        }
        return null;
    }

    private static Model scientists;

    @BeforeAll
    static void load() {
        File f = locateTtl();
        if (f != null) {
            scientists = RDFDataMgr.loadModel(f.toURI().toString());
        }
    }

    @Test
    @EnabledIf("dataAvailable")
    void testDistinctPromptPredictionMatchesDedup() {
        String prefixes = "PREFIX ex: <http://example.org/>";
        String pattern = "?s ex:researchField ?field .";

        long n = KgStats.bindings(scientists, prefixes, pattern);
        long d = KgStats.distinctBindings(scientists, prefixes, pattern, "field");

        System.out.printf("[C2-DEMO] KG predicts N=%d bindings -> D=%d distinct prompts "
                + "(dedup ratio %.2f) BEFORE any LLM call%n",
                n, d, KgStats.dedupRatio(scientists, prefixes, pattern, "field"));

        // Matches the C3 workload result (46 -> 9) computed purely from graph statistics.
        assertEquals(46, n);
        assertEquals(9, d);
        assertTrue(d < n, "dedup ratio must be < 1 on a repeated-value predicate");
    }
}
