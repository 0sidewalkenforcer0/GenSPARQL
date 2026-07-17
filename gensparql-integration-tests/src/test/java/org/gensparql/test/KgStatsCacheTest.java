package org.gensparql.test;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.gensparql.engine.cost.KgStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies KgStats caches COUNTs per graph so cost planning does not re-scan each run. */
public class KgStatsCacheTest {

    private static final String NS = "http://example.org/";
    private static final String PREFIXES = "PREFIX ex: <" + NS + ">";
    private static final String PATTERN = "?s ex:researchField ?field .";

    @AfterEach
    void reset() {
        KgStats.setCachingEnabled(true);
        KgStats.clearCache();
    }

    private static Model modelWith(int n) {
        Model m = ModelFactory.createDefaultModel();
        for (int i = 0; i < n; i++) {
            m.add(ResourceFactory.createResource(NS + "p" + i),
                  ResourceFactory.createProperty(NS + "researchField"),
                  ResourceFactory.createPlainLiteral("Field" + (i % 2)));
        }
        return m;
    }

    private static void addTriple(Model m, String id, String field) {
        m.add(ResourceFactory.createResource(NS + id),
              ResourceFactory.createProperty(NS + "researchField"),
              ResourceFactory.createPlainLiteral(field));
    }

    @Test
    void testCountsAreCachedPerGraph() {
        KgStats.clearCache();
        Model m = modelWith(3);
        assertEquals(3, KgStats.bindings(m, PREFIXES, PATTERN)); // computes + caches

        // Mutate the same graph: a cached (stale) count is served -> proves the cache is used.
        addTriple(m, "pX", "Field9");
        assertEquals(3, KgStats.bindings(m, PREFIXES, PATTERN), "cached count served without re-scan");

        // After clearing, the fresh read reflects the mutation.
        KgStats.clearCache();
        assertEquals(4, KgStats.bindings(m, PREFIXES, PATTERN));
    }

    @Test
    void testCachingCanBeDisabled() {
        KgStats.clearCache();
        KgStats.setCachingEnabled(false);
        Model m = modelWith(3);
        assertEquals(3, KgStats.bindings(m, PREFIXES, PATTERN));

        addTriple(m, "pX", "Field9");
        assertEquals(4, KgStats.bindings(m, PREFIXES, PATTERN), "caching off -> always a fresh read");
    }

    @Test
    void testRatioSharedDefinition() {
        assertEquals(0.2, KgStats.ratio(2, 10), 1e-9);
        assertEquals(0.5, KgStats.ratio(5, 10), 1e-9);
        assertEquals(1.0, KgStats.ratio(0, 10), 1e-9, "distinct 0 (var absent) -> no dedup");
        assertEquals(1.0, KgStats.ratio(5, 0), 1e-9, "empty input -> 1.0");
        assertEquals(1.0, KgStats.ratio(20, 10), 1e-9, "clamped to 1.0");
    }

    @Test
    void testDistinctAndCountUseSeparateCacheEntries() {
        KgStats.clearCache();
        Model m = modelWith(4); // 4 triples, 2 distinct fields (Field0, Field1)
        assertEquals(4, KgStats.bindings(m, PREFIXES, PATTERN));
        assertEquals(2, KgStats.distinctBindings(m, PREFIXES, PATTERN, "field"));
        // Repeat: both served from distinct cache entries, values unchanged.
        assertEquals(4, KgStats.bindings(m, PREFIXES, PATTERN));
        assertEquals(2, KgStats.distinctBindings(m, PREFIXES, PATTERN, "field"));
    }
}
