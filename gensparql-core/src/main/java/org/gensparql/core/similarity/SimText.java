package org.gensparql.core.similarity;

/**
 * Text similarity strategy interface for semantic comparisons.
 *
 * Implementations can use various algorithms:
 * - Levenshtein (edit distance)
 * - Jaccard (word set intersection)
 * - Cosine (embedding-based)
 *
 * Used by SimScore to compare lexical forms of RDF and GEN values.
 */
public interface SimText {

    /**
     * Compute similarity between two strings.
     *
     * @param s1 first string
     * @param s2 second string
     * @return value in [0.0, 1.0] where 1.0 = identical
     */
    double similarity(String s1, String s2);

    /**
     * Get the strategy name for logging/debugging.
     *
     * @return strategy name
     */
    String getName();
}
