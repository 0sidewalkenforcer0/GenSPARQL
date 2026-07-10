package org.gensparql.core.similarity;

import org.apache.jena.sparql.core.Var;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for variable-specific similarity thresholds θ(v).
 *
 * According to the paper, each variable can have its own threshold
 * for semantic join operations. This registry stores these thresholds
 * and provides a default fallback value.
 *
 * Thread-safe implementation using ConcurrentHashMap.
 */
public class ThresholdRegistry {

    private final Map<Var, Double> thresholds = new ConcurrentHashMap<>();
    private volatile double defaultThreshold = 0.8;

    /**
     * Set the threshold for a specific variable.
     *
     * @param var       the variable
     * @param threshold value between 0.0 and 1.0
     * @throws IllegalArgumentException if threshold is out of range
     */
    public void setThreshold(Var var, double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be in [0.0, 1.0], got: " + threshold);
        }
        thresholds.put(var, threshold);
    }

    /**
     * Get the threshold for a specific variable.
     * Returns the default threshold if no custom threshold is set.
     *
     * @param var the variable
     * @return threshold value in [0.0, 1.0]
     */
    public double getThreshold(Var var) {
        return thresholds.getOrDefault(var, defaultThreshold);
    }

    /**
     * Set the default threshold for variables without custom thresholds.
     *
     * @param threshold value between 0.0 and 1.0
     * @throws IllegalArgumentException if threshold is out of range
     */
    public void setDefaultThreshold(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be in [0.0, 1.0], got: " + threshold);
        }
        this.defaultThreshold = threshold;
    }

    /**
     * Get the default threshold.
     *
     * @return default threshold value
     */
    public double getDefaultThreshold() {
        return defaultThreshold;
    }

    /**
     * Check if a custom threshold is set for a variable.
     *
     * @param var the variable
     * @return true if custom threshold exists
     */
    public boolean hasCustomThreshold(Var var) {
        return thresholds.containsKey(var);
    }

    /**
     * Remove the custom threshold for a variable.
     *
     * @param var the variable
     */
    public void removeThreshold(Var var) {
        thresholds.remove(var);
    }

    /**
     * Clear all custom thresholds.
     */
    public void clear() {
        thresholds.clear();
    }

    /**
     * Get the number of custom thresholds registered.
     *
     * @return count of custom thresholds
     */
    public int size() {
        return thresholds.size();
    }
}
