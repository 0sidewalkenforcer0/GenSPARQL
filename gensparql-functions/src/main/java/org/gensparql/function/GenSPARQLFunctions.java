package org.gensparql.function;

import org.apache.jena.sparql.function.FunctionRegistry;
import org.gensparql.function.similarity.*;
import org.gensparql.function.nlp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for GenSPARQL extension functions.
 *
 * Implements Table 1 from the GenSPARQL paper:
 * - gen:similarity(?x, ?y) - semantic similarity
 * - gen:approxEq(?x, ?y, ?t) - approximate equality
 * - gen:embedding(?x) - get embedding vector
 * - gen:classify(?x, ?labels) - text classification
 * - gen:extract(?x, ?schema) - structured extraction
 * - gen:ground(?text) - entity grounding/linking
 * - gen:validate(?x, ?schema) - schema validation
 * - gen:entail(?p, ?h) - textual entailment
 * - gen:parseJSON(?raw, ?field) - JSON parsing
 * - gen:tokenCost(?prompt) - token cost estimation
 */
public class GenSPARQLFunctions {
    private static final Logger LOG = LoggerFactory.getLogger(GenSPARQLFunctions.class);

    public static final String NS = "http://gensparql.org/function#";

    private static volatile boolean registered = false;

    /**
     * Register all GenSPARQL functions.
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }

        LOG.info("Registering GenSPARQL functions...");

        FunctionRegistry registry = FunctionRegistry.get();

        // Similarity functions
        registry.put(NS + "similarity", SimilarityFunction.class);
        registry.put(NS + "approxEq", ApproxEqFunction.class);

        // Embedding function
        registry.put(NS + "embedding", EmbeddingFunction.class);

        // NLP functions
        registry.put(NS + "classify", ClassifyFunction.class);
        registry.put(NS + "extract", ExtractFunction.class);
        registry.put(NS + "ground", GroundFunction.class);
        registry.put(NS + "validate", ValidateFunction.class);
        registry.put(NS + "entail", EntailFunction.class);

        // Utility functions
        registry.put(NS + "parseJSON", ParseJSONFunction.class);
        registry.put(NS + "tokenCost", TokenCostFunction.class);

        registered = true;
        LOG.info("GenSPARQL functions registered: 10 functions");
    }

    /**
     * Check if functions are registered.
     */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * Unregister functions (for testing).
     */
    public static synchronized void unregister() {
        if (!registered) {
            return;
        }

        FunctionRegistry registry = FunctionRegistry.get();

        registry.remove(NS + "similarity");
        registry.remove(NS + "approxEq");
        registry.remove(NS + "embedding");
        registry.remove(NS + "classify");
        registry.remove(NS + "extract");
        registry.remove(NS + "ground");
        registry.remove(NS + "validate");
        registry.remove(NS + "entail");
        registry.remove(NS + "parseJSON");
        registry.remove(NS + "tokenCost");

        registered = false;
        LOG.debug("GenSPARQL functions unregistered");
    }

    // Prevent instantiation
    private GenSPARQLFunctions() {}
}
