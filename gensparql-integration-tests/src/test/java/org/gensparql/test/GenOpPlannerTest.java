package org.gensparql.test;

import org.gensparql.engine.cost.GenOpCostModel;
import org.gensparql.engine.cost.GenOpPlanner;
import org.gensparql.engine.cost.GenOpPlanner.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the cost-based reordering planner (C4). */
public class GenOpPlannerTest {

    private final GenOpPlanner planner = new GenOpPlanner();
    private final GenOpCostModel model = GenOpCostModel.builder().build();

    private static int indexOf(List<PlanItem> order, String label) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).label().equals(label)) return i;
        }
        return -1;
    }

    @Test
    void testSelectivePatternPulledBeforeGenOp() {
        // base -> 26 bindings; a selective filter keeps 20%; then an expensive GENOP.
        PlanItem base = new KgPattern("base", Set.of(), Set.of("s"), 26.0);
        PlanItem selective = new KgPattern("selective", Set.of("s"), Set.of(), 0.2);
        PlanItem gen = new GenOpItem("gen", Set.of("s"), Set.of("desc"),
                1.0, 50, 20, 1.0);

        // Feed in a deliberately bad order; planner must reorder.
        PlanResult r = planner.plan(List.of(base, gen, selective), 1.0, model, false);

        assertTrue(indexOf(r.order(), "selective") < indexOf(r.order(), "gen"),
                "selective filter must be placed before the expensive GENOP");
        assertEquals(5, r.totalLlmCalls(),
                "GENOP fires over 26*0.2 = 5 bindings, not 26");
    }

    @Test
    void testDependencyRespected() {
        PlanItem base = new KgPattern("base", Set.of(), Set.of("s"), 10.0);
        PlanItem gen = new GenOpItem("gen", Set.of("s"), Set.of("y"), 1.0, 10, 10, 1.0);

        PlanResult r = planner.plan(List.of(gen, base), 1.0, model, false);

        assertTrue(indexOf(r.order(), "base") < indexOf(r.order(), "gen"),
                "a GENOP must never be placed before the pattern binding its input");
    }

    @Test
    void testDedupLowersCost() {
        PlanItem base = new KgPattern("base", Set.of(), Set.of("s"), 26.0);
        // GENOP over a low-cardinality context var: dedup ratio 0.2 (like researchField).
        PlanItem gen = new GenOpItem("gen", Set.of("s"), Set.of("y"), 1.0, 50, 20, 0.2);

        long noDedup = planner.plan(List.of(base, gen), 1.0, model, false).totalLlmCalls();
        long withDedup = planner.plan(List.of(base, gen), 1.0, model, true).totalLlmCalls();

        assertEquals(26, noDedup);
        assertEquals(5, withDedup, "26 bindings but ~5 distinct prompts under dedup");
        assertTrue(withDedup < noDedup);
    }

    @Test
    void testUnsatisfiableDependencyThrows() {
        PlanItem gen = new GenOpItem("gen", Set.of("missing"), Set.of("y"), 1.0, 10, 10, 1.0);
        assertThrows(IllegalArgumentException.class,
                () -> planner.plan(List.of(gen), 1.0, model, false),
                "an unbound GENOP input must be rejected, not silently reordered");
    }

    @Test
    void testEmptyFragment() {
        PlanResult r = planner.plan(List.of(), 1.0, model, false);
        assertTrue(r.order().isEmpty());
        assertEquals(0, r.totalLlmCalls());
    }
}
