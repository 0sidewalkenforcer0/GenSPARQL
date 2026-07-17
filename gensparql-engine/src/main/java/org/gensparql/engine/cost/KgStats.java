package org.gensparql.engine.cost;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;

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
 */
public final class KgStats {

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

    private static long countQuery(Model model, String queryString) {
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
