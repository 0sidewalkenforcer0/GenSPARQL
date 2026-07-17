package org.gensparql.engine.cost;

import org.apache.jena.graph.Graph;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Graph statistics for cost estimation (C2 ↔ C3 bridge).
 *
 * <p>For a context-mode GENOP fed by a graph pattern binding a variable {@code v}, the number
 * of LLM calls under cross-binding dedup equals the number of <em>distinct</em> values of
 * {@code v} in the KG — a statistic knowable <em>before</em> execution. This lets the cost
 * model predict actual LLM calls (not just raw fan-out) from the graph alone: on the bundled
 * scientists KG, {@code distinctBindings(?s ex:researchField ?field, "field") = 9} predicts
 * the 9 calls the deduped run makes over its 46 bindings.
 *
 * <p>No relational system frames cardinality estimation this way, because a row-dedup ratio
 * is not a schema property; in SPARQL it is a graph statistic.
 *
 * <p><b>Caching.</b> COUNT results are cached per (graph, query) so cost-based planning does
 * not re-scan the graph on every execution (e.g. a benchmark that reruns the same query across
 * plan variants). The cache keys graphs weakly (GC-friendly) and assumes the graph is stable
 * while cost planning reads it — true during query evaluation. Call {@link #clearCache()} after
 * mutating a cached graph, or {@link #setCachingEnabled(boolean)} to disable.
 */
public final class KgStats {

    private static final Map<Graph, Map<String, Long>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean cachingEnabled = true;

    private KgStats() {
    }

    /** Number of solutions of {@code pattern} (= input bindings N a GENOP would fire over). */
    public static long bindings(Model model, String prefixes, String pattern) {
        return countQuery(model, prefixes
                + " SELECT (COUNT(*) AS ?c) WHERE { " + pattern + " }");
    }

    /** Number of distinct values of {@code var} in {@code pattern} (= distinct prompts D). */
    public static long distinctBindings(Model model, String prefixes, String pattern, String var) {
        return countQuery(model, prefixes
                + " SELECT (COUNT(DISTINCT ?" + var + ") AS ?c) WHERE { " + pattern + " }");
    }

    /**
     * Dedup ratio D/N in [0,1]: the fraction of bindings that survive as distinct LLM calls.
     * Lower = more dedup benefit. Returns 1.0 for an empty input.
     */
    public static double dedupRatio(Model model, String prefixes, String pattern, String var) {
        long n = bindings(model, prefixes, pattern);
        if (n == 0) {
            return 1.0;
        }
        return (double) distinctBindings(model, prefixes, pattern, var) / n;
    }

    /** Enable/disable COUNT caching (disable if querying a graph that mutates between calls). */
    public static void setCachingEnabled(boolean enabled) {
        cachingEnabled = enabled;
    }

    /** Drop all cached COUNTs (call after mutating a graph that was queried). */
    public static void clearCache() {
        CACHE.clear();
    }

    private static long countQuery(Model model, String queryString) {
        if (!cachingEnabled) {
            return runCount(model, queryString);
        }
        Graph graph = model.getGraph();
        Map<String, Long> perGraph;
        synchronized (CACHE) {
            perGraph = CACHE.computeIfAbsent(graph, g -> new ConcurrentHashMap<>());
        }
        Long cached = perGraph.get(queryString);
        if (cached != null) {
            return cached;
        }
        long value = runCount(model, queryString);
        perGraph.put(queryString, value);
        return value;
    }

    private static long runCount(Model model, String queryString) {
        Query q = QueryFactory.create(queryString);
        try (QueryExecution qe = QueryExecutionFactory.create(q, model)) {
            ResultSet rs = qe.execSelect();
            if (rs.hasNext()) {
                return rs.next().getLiteral("c").getLong();
            }
            return 0;
        }
    }
}
