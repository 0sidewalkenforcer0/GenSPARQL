package org.gensparql.engine;

import org.apache.jena.sparql.util.Symbol;

/**
 * Constants used in GenSPARQL query execution.
 *
 * These symbols are used to store and retrieve values from
 * the Jena execution context.
 */
public class GenSPARQLConstants {

    private static final String NS = "http://gensparql.org/context#";

    /**
     * Context symbol for the ThresholdRegistry.
     * Stores variable-specific similarity thresholds.
     */
    public static final Symbol THRESHOLD_REGISTRY = Symbol.create(NS + "thresholdRegistry");

    /**
     * Context symbol for the SimText strategy.
     * Stores the text similarity implementation to use.
     */
    public static final Symbol SIM_TEXT = Symbol.create(NS + "simText");

    /**
     * Context symbol for source type tracking.
     * Maps variables to their source types (RDF or GEN).
     */
    public static final Symbol SOURCE_TYPES = Symbol.create(NS + "sourceTypes");

    /**
     * Context symbol to enable/disable similarity joins.
     * When true, joins involving GEN-typed variables use SimScore.
     */
    public static final Symbol ENABLE_SIM_JOIN = Symbol.create(NS + "enableSimJoin");

    /**
     * Context symbol for the default similarity threshold.
     * Used when no variable-specific threshold is set.
     */
    public static final Symbol DEFAULT_THRESHOLD = Symbol.create(NS + "defaultThreshold");

    /**
     * Default threshold value (0.8).
     */
    public static final double DEFAULT_THRESHOLD_VALUE = 0.8;

    // Prevent instantiation
    private GenSPARQLConstants() {}
}
