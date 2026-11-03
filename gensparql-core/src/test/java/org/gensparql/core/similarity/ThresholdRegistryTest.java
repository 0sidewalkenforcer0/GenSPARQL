package org.gensparql.core.similarity;

import org.apache.jena.sparql.core.Var;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A variable without its own threshold uses the default, 0.85: in the evaluation of the
 * WWW'27 demo paper, the lowest threshold at which the embedding similarity mapped no
 * generated name to a wrong entity.
 */
class ThresholdRegistryTest {

    @Test
    void variablesWithoutAThresholdUseTheDefault() {
        ThresholdRegistry registry = new ThresholdRegistry();
        assertEquals(0.85, registry.getDefaultThreshold());
        assertEquals(0.85, registry.getThreshold(Var.alloc("team")));
    }

    @Test
    void theThresholdOfAVariableOverridesTheDefault() {
        ThresholdRegistry registry = new ThresholdRegistry();
        registry.setThreshold(Var.alloc("team"), 0.6);
        assertEquals(0.6, registry.getThreshold(Var.alloc("team")));
        assertEquals(0.85, registry.getThreshold(Var.alloc("city")));
    }

    @Test
    void thresholdsOutsideTheUnitIntervalAreRejected() {
        ThresholdRegistry registry = new ThresholdRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.setThreshold(Var.alloc("team"), 1.5));
    }
}
