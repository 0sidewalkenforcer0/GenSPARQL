package org.gensparql.test;

import org.gensparql.engine.cost.GenOpCostModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the GENOP cost model (C1). Pure arithmetic, no LLM. */
public class GenOpCostModelTest {

    @Test
    void testDedupReducesCallsButNotRows() {
        // Mirrors the scientists KG workload: 46 bindings, 9 distinct fields, fan-out 1.
        GenOpCostModel model = GenOpCostModel.builder().build();
        GenOpCostModel.Inputs in = new GenOpCostModel.Inputs(46, 9, 1.0, 50, 20);

        GenOpCostModel.Estimate off = model.estimate(in, false);
        GenOpCostModel.Estimate on = model.estimate(in, true);

        assertEquals(46, off.llmCalls(), "no dedup -> one call per binding");
        assertEquals(9, on.llmCalls(), "dedup -> one call per distinct prompt");
        assertEquals(46, off.outputRows(), "rows = N * F");
        assertEquals(46, on.outputRows(), "dedup must not change row count");
        assertTrue(on.dominantCost() < off.dominantCost(), "dedup lowers dominant cost");
    }

    @Test
    void testFanOutScalesRows() {
        GenOpCostModel model = GenOpCostModel.builder().build();
        // 10 bindings, all distinct, each call yields 5 rows.
        GenOpCostModel.Estimate e =
                model.estimate(new GenOpCostModel.Inputs(10, 10, 5.0, 100, 200), false);
        assertEquals(10, e.llmCalls());
        assertEquals(50, e.outputRows());
        assertEquals(10L * (100 + 200), e.totalTokens());
    }

    @Test
    void testDollarsAndLatency() {
        GenOpCostModel model = GenOpCostModel.builder()
                .pricing(0.14, 0.28)     // $/1M tokens in/out
                .perCallLatencyMs(1000)
                .concurrency(4)
                .build();
        // 8 distinct calls, 1000 prompt + 500 completion tokens each.
        GenOpCostModel.Estimate e =
                model.estimate(new GenOpCostModel.Inputs(8, 8, 1.0, 1000, 500), false);

        double expectedDollars = 8 * (1000 * 0.14 + 500 * 0.28) / 1_000_000.0;
        assertEquals(expectedDollars, e.dollars(), 1e-12);
        // ceil(8/4) * 1000ms = 2000ms
        assertEquals(2000.0, e.latencyMs(), 1e-9);
    }

    @Test
    void testTokenHeuristic() {
        assertEquals(0, GenOpCostModel.estimateTokens(""));
        assertEquals(0, GenOpCostModel.estimateTokens(null));
        assertEquals(25, GenOpCostModel.estimateTokens("x".repeat(100))); // 100/4
    }

    @Test
    void testInvalidInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new GenOpCostModel.Inputs(5, 10, 1.0, 10, 10),
                "distinctPrompts cannot exceed inputBindings");
        assertThrows(IllegalArgumentException.class,
                () -> new GenOpCostModel.Inputs(-1, 0, 1.0, 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new GenOpCostModel.Inputs(5, 5, -1.0, 10, 10));
    }
}
