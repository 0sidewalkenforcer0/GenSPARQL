package org.gensparql.engine;

/**
 * Global configuration for GenSPARQL optimization settings.
 * Controls caching and batching behavior.
 */
public class GenSPARQLConfig {

    // Caching configuration
    private static boolean cachingEnabled = false;
    private static int cacheMaxSize = 10_000;
    private static long cacheTtlDays = 7;

    // Batching configuration
    private static boolean batchingEnabled = false;
    private static int batchSize = 5;
    private static int batchMaxTokens = 8000;

    // Cross-binding prompt deduplication (C3): identical resolved prompts within a
    // single query execution collapse to one LLM call; results are fanned back out.
    // Lossless (exact-prompt match); off by default to preserve baseline behavior.
    private static boolean batchDedupEnabled = false;

    // Cost-based reordering (C4): order context-mode GENOP sequences by estimated cost
    // (GenOpPlanner) instead of the correctness-only "BGPs first" heuristic. Respects
    // dependency legality (Prop 6); off by default to preserve baseline behavior.
    private static boolean costBasedPlanningEnabled = false;

    // Timing configuration
    private static boolean timingEnabled = true;
    private static boolean verboseLogging = false;

    // Constrained generation configuration
    private static boolean constrainedGenerationEnabled = false;
    private static int maxCandidates = 500;

    // Embedding-based grounding configuration
    private static boolean groundingEnabled = false;
    private static double groundingThreshold = 0.8;
    private static int groundingBatchSize = 50;
    private static String groundingStrategy = "embedding"; // embedding, lexical, hybrid

    /**
     * Check if response caching is enabled.
     */
    public static boolean isCachingEnabled() {
        return cachingEnabled;
    }

    /**
     * Enable or disable response caching.
     */
    public static void setCachingEnabled(boolean enabled) {
        cachingEnabled = enabled;
    }

    /**
     * Get the maximum cache size.
     */
    public static int getCacheMaxSize() {
        return cacheMaxSize;
    }

    /**
     * Set the maximum cache size.
     */
    public static void setCacheMaxSize(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("Cache max size must be positive");
        }
        cacheMaxSize = maxSize;
    }

    /**
     * Get the cache TTL in days.
     */
    public static long getCacheTtlDays() {
        return cacheTtlDays;
    }

    /**
     * Set the cache TTL in days.
     */
    public static void setCacheTtlDays(long ttlDays) {
        if (ttlDays <= 0) {
            throw new IllegalArgumentException("Cache TTL must be positive");
        }
        cacheTtlDays = ttlDays;
    }

    /**
     * Check if request batching is enabled.
     */
    public static boolean isBatchingEnabled() {
        return batchingEnabled;
    }

    /**
     * Enable or disable request batching.
     */
    public static void setBatchingEnabled(boolean enabled) {
        batchingEnabled = enabled;
    }

    /**
     * Get the batch size (number of requests per batch).
     */
    public static int getBatchSize() {
        return batchSize;
    }

    /**
     * Set the batch size.
     */
    public static void setBatchSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        batchSize = size;
    }

    /**
     * Get the maximum tokens for batched requests.
     */
    public static int getBatchMaxTokens() {
        return batchMaxTokens;
    }

    /**
     * Set the maximum tokens for batched requests.
     */
    public static void setBatchMaxTokens(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("Batch max tokens must be positive");
        }
        batchMaxTokens = maxTokens;
    }

    /**
     * Check if cross-binding prompt deduplication (C3) is enabled.
     * When enabled, identical resolved prompts within one query execution are sent
     * to the LLM only once and the parsed outputs are reused for every input binding
     * that produced the same prompt.
     */
    public static boolean isBatchDedupEnabled() {
        return batchDedupEnabled;
    }

    /**
     * Enable or disable cross-binding prompt deduplication.
     */
    public static void setBatchDedupEnabled(boolean enabled) {
        batchDedupEnabled = enabled;
    }

    /**
     * Check if cost-based reordering (C4) is enabled for context-mode GENOP sequences.
     */
    public static boolean isCostBasedPlanningEnabled() {
        return costBasedPlanningEnabled;
    }

    /**
     * Enable or disable cost-based reordering.
     */
    public static void setCostBasedPlanningEnabled(boolean enabled) {
        costBasedPlanningEnabled = enabled;
    }

    /**
     * Check if timing is enabled.
     */
    public static boolean isTimingEnabled() {
        return timingEnabled;
    }

    /**
     * Enable or disable timing.
     */
    public static void setTimingEnabled(boolean enabled) {
        timingEnabled = enabled;
    }

    /**
     * Check if verbose logging is enabled.
     */
    public static boolean isVerboseLogging() {
        return verboseLogging;
    }

    /**
     * Enable or disable verbose logging.
     */
    public static void setVerboseLogging(boolean verbose) {
        verboseLogging = verbose;
    }

    /**
     * Check if constrained generation is enabled.
     * When enabled, LLM is provided with candidate entities from KG
     * and must select from them instead of generating arbitrary answers.
     */
    public static boolean isConstrainedGenerationEnabled() {
        return constrainedGenerationEnabled;
    }

    /**
     * Enable or disable constrained generation.
     */
    public static void setConstrainedGenerationEnabled(boolean enabled) {
        constrainedGenerationEnabled = enabled;
    }

    /**
     * Get the maximum number of candidates to include in constrained prompts.
     */
    public static int getMaxCandidates() {
        return maxCandidates;
    }

    /**
     * Set the maximum number of candidates.
     */
    public static void setMaxCandidates(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("Max candidates must be positive");
        }
        maxCandidates = max;
    }

    /**
     * Check if embedding-based grounding is enabled.
     * When enabled, LLM free-text outputs are automatically grounded to
     * the nearest KG entity using embedding similarity.
     */
    public static boolean isGroundingEnabled() {
        return groundingEnabled;
    }

    /**
     * Enable or disable embedding-based grounding.
     */
    public static void setGroundingEnabled(boolean enabled) {
        groundingEnabled = enabled;
    }

    /**
     * Get the similarity threshold for grounding.
     * LLM outputs are only grounded if similarity >= threshold.
     */
    public static double getGroundingThreshold() {
        return groundingThreshold;
    }

    /**
     * Set the similarity threshold for grounding.
     */
    public static void setGroundingThreshold(double threshold) {
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Grounding threshold must be between 0 and 1");
        }
        groundingThreshold = threshold;
    }

    /**
     * Get the batch size for embedding index building.
     */
    public static int getGroundingBatchSize() {
        return groundingBatchSize;
    }

    /**
     * Set the batch size for embedding index building.
     */
    public static void setGroundingBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Grounding batch size must be positive");
        }
        groundingBatchSize = batchSize;
    }

    /**
     * Get the grounding strategy: embedding, lexical, or hybrid.
     */
    public static String getGroundingStrategy() {
        return groundingStrategy;
    }

    /**
     * Set the grounding strategy.
     */
    public static void setGroundingStrategy(String strategy) {
        if (strategy == null ||
            (!strategy.equals("embedding") && !strategy.equals("lexical") && !strategy.equals("hybrid"))) {
            throw new IllegalArgumentException("Grounding strategy must be: embedding, lexical, or hybrid");
        }
        groundingStrategy = strategy;
    }

    /**
     * Load configuration from system properties.
     * Properties:
     * - gensparql.cache.enabled=true|false
     * - gensparql.cache.maxSize=10000
     * - gensparql.cache.ttlDays=7
     * - gensparql.batch.enabled=true|false
     * - gensparql.batch.size=5
     * - gensparql.batch.maxTokens=8000
     * - gensparql.timing.enabled=true|false
     * - gensparql.verbose=true|false
     */
    public static void loadFromSystemProperties() {
        // Caching
        String cacheEnabled = System.getProperty("gensparql.cache.enabled");
        if (cacheEnabled != null) {
            cachingEnabled = Boolean.parseBoolean(cacheEnabled);
        }

        String cacheMax = System.getProperty("gensparql.cache.maxSize");
        if (cacheMax != null) {
            try {
                cacheMaxSize = Integer.parseInt(cacheMax);
            } catch (NumberFormatException e) {
                System.err.println("Invalid gensparql.cache.maxSize: " + cacheMax);
            }
        }

        String cacheTtl = System.getProperty("gensparql.cache.ttlDays");
        if (cacheTtl != null) {
            try {
                cacheTtlDays = Long.parseLong(cacheTtl);
            } catch (NumberFormatException e) {
                System.err.println("Invalid gensparql.cache.ttlDays: " + cacheTtl);
            }
        }

        // Batching
        String batchEnabled = System.getProperty("gensparql.batch.enabled");
        if (batchEnabled != null) {
            batchingEnabled = Boolean.parseBoolean(batchEnabled);
        }

        String batchSizeProp = System.getProperty("gensparql.batch.size");
        if (batchSizeProp != null) {
            try {
                batchSize = Integer.parseInt(batchSizeProp);
            } catch (NumberFormatException e) {
                System.err.println("Invalid gensparql.batch.size: " + batchSizeProp);
            }
        }

        String batchMaxTok = System.getProperty("gensparql.batch.maxTokens");
        if (batchMaxTok != null) {
            try {
                batchMaxTokens = Integer.parseInt(batchMaxTok);
            } catch (NumberFormatException e) {
                System.err.println("Invalid gensparql.batch.maxTokens: " + batchMaxTok);
            }
        }

        String batchDedup = System.getProperty("gensparql.batch.dedup");
        if (batchDedup != null) {
            batchDedupEnabled = Boolean.parseBoolean(batchDedup);
        }

        String costPlanning = System.getProperty("gensparql.planner.costBased");
        if (costPlanning != null) {
            costBasedPlanningEnabled = Boolean.parseBoolean(costPlanning);
        }

        // Timing
        String timingEn = System.getProperty("gensparql.timing.enabled");
        if (timingEn != null) {
            timingEnabled = Boolean.parseBoolean(timingEn);
        }

        // Verbose
        String verbose = System.getProperty("gensparql.verbose");
        if (verbose != null) {
            verboseLogging = Boolean.parseBoolean(verbose);
        }

        // Constrained generation
        String constrained = System.getProperty("gensparql.constrained.enabled");
        if (constrained != null) {
            constrainedGenerationEnabled = Boolean.parseBoolean(constrained);
        }

        String maxCand = System.getProperty("gensparql.constrained.maxCandidates");
        if (maxCand != null) {
            try {
                maxCandidates = Integer.parseInt(maxCand);
            } catch (NumberFormatException e) {
                System.err.println("Invalid gensparql.constrained.maxCandidates: " + maxCand);
            }
        }

        // Grounding
        String groundingEn = System.getProperty("gensparql.grounding.enabled");
        if (groundingEn != null) {
            groundingEnabled = Boolean.parseBoolean(groundingEn);
        }

        String groundingThr = System.getProperty("gensparql.grounding.threshold");
        if (groundingThr != null) {
            try {
                groundingThreshold = Double.parseDouble(groundingThr);
            } catch (NumberFormatException e) {
                System.err.println("Invalid gensparql.grounding.threshold: " + groundingThr);
            }
        }

        String groundingBatch = System.getProperty("gensparql.grounding.batchSize");
        if (groundingBatch != null) {
            try {
                groundingBatchSize = Integer.parseInt(groundingBatch);
            } catch (NumberFormatException e) {
                System.err.println("Invalid gensparql.grounding.batchSize: " + groundingBatch);
            }
        }

        String groundingStrat = System.getProperty("gensparql.grounding.strategy");
        if (groundingStrat != null) {
            groundingStrategy = groundingStrat;
        }
    }

    /**
     * Reset all configuration to defaults.
     */
    public static void reset() {
        cachingEnabled = false;
        cacheMaxSize = 10_000;
        cacheTtlDays = 7;
        batchingEnabled = false;
        batchSize = 5;
        batchMaxTokens = 8000;
        batchDedupEnabled = false;
        costBasedPlanningEnabled = false;
        timingEnabled = true;
        verboseLogging = false;
        constrainedGenerationEnabled = false;
        maxCandidates = 500;
        groundingEnabled = false;
        groundingThreshold = 0.8;
        groundingBatchSize = 50;
        groundingStrategy = "embedding";
    }

    /**
     * Get a summary of current configuration.
     */
    public static String getSummary() {
        return String.format(
            "GenSPARQLConfig{caching=%s (maxSize=%d, ttlDays=%d), " +
            "batching=%s (size=%d, maxTokens=%d, dedup=%s), timing=%s, verbose=%s, " +
            "constrained=%s (maxCandidates=%d), " +
            "grounding=%s (threshold=%.2f, strategy=%s, batchSize=%d)}",
            cachingEnabled, cacheMaxSize, cacheTtlDays,
            batchingEnabled, batchSize, batchMaxTokens, batchDedupEnabled,
            timingEnabled, verboseLogging,
            constrainedGenerationEnabled, maxCandidates,
            groundingEnabled, groundingThreshold, groundingStrategy, groundingBatchSize
        );
    }
}
