package org.gensparql.core.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive metrics for GenSPARQL query execution.
 * Tracks timing at multiple levels: query, engine, LLM calls, caching, and batching.
 */
public class QueryExecutionMetrics {

    // Query-level timing
    private long totalTimeMs;
    private long parseTimeMs;
    private long planningTimeMs;
    private long executionTimeMs;

    // LLM-specific metrics
    private int llmCallCount;
    private long totalLlmTimeMs;
    private long minLlmTimeMs = Long.MAX_VALUE;
    private long maxLlmTimeMs = 0;
    private double avgLlmTimeMs;
    private int totalPromptTokens;
    private int totalCompletionTokens;

    // Cache metrics (when caching enabled)
    private int cacheHits;
    private int cacheMisses;
    private double cacheHitRate;

    // Batch metrics (when batching enabled)
    private int batchCount;
    private double avgBatchSize;
    private long totalBatchTimeMs;

    // Result counts
    private int resultCount;

    // Timestamps
    private Instant startTime;
    private Instant endTime;

    // Individual LLM call metrics
    private List<LLMCallMetrics> llmCalls;

    public QueryExecutionMetrics() {
        this.llmCalls = new ArrayList<>();
        this.startTime = Instant.now();
    }

    // Getters
    public long getTotalTimeMs() {
        return totalTimeMs;
    }

    public long getParseTimeMs() {
        return parseTimeMs;
    }

    public long getPlanningTimeMs() {
        return planningTimeMs;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public int getLlmCallCount() {
        return llmCallCount;
    }

    public long getTotalLlmTimeMs() {
        return totalLlmTimeMs;
    }

    public long getMinLlmTimeMs() {
        return minLlmTimeMs == Long.MAX_VALUE ? 0 : minLlmTimeMs;
    }

    public long getMaxLlmTimeMs() {
        return maxLlmTimeMs;
    }

    public double getAvgLlmTimeMs() {
        return avgLlmTimeMs;
    }

    public int getTotalPromptTokens() {
        return totalPromptTokens;
    }

    public int getTotalCompletionTokens() {
        return totalCompletionTokens;
    }

    public int getTotalTokens() {
        return totalPromptTokens + totalCompletionTokens;
    }

    public int getCacheHits() {
        return cacheHits;
    }

    public int getCacheMisses() {
        return cacheMisses;
    }

    public double getCacheHitRate() {
        return cacheHitRate;
    }

    public int getBatchCount() {
        return batchCount;
    }

    public double getAvgBatchSize() {
        return avgBatchSize;
    }

    public long getTotalBatchTimeMs() {
        return totalBatchTimeMs;
    }

    public int getResultCount() {
        return resultCount;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public List<LLMCallMetrics> getLlmCalls() {
        return llmCalls;
    }

    // Setters
    public void setTotalTimeMs(long totalTimeMs) {
        this.totalTimeMs = totalTimeMs;
    }

    public void setParseTimeMs(long parseTimeMs) {
        this.parseTimeMs = parseTimeMs;
    }

    public void setPlanningTimeMs(long planningTimeMs) {
        this.planningTimeMs = planningTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public void setResultCount(int resultCount) {
        this.resultCount = resultCount;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    // Methods to add LLM call metrics
    public void addLlmCallMetrics(LLMCallMetrics metrics) {
        this.llmCalls.add(metrics);
        this.llmCallCount++;
        this.totalLlmTimeMs += metrics.getLatencyMs();
        this.minLlmTimeMs = Math.min(this.minLlmTimeMs, metrics.getLatencyMs());
        this.maxLlmTimeMs = Math.max(this.maxLlmTimeMs, metrics.getLatencyMs());
        this.totalPromptTokens += metrics.getPromptTokens();
        this.totalCompletionTokens += metrics.getCompletionTokens();

        if (metrics.isCacheHit()) {
            this.cacheHits++;
        } else {
            this.cacheMisses++;
        }
    }

    public void addAllLlmCallMetrics(List<LLMCallMetrics> metricsList) {
        for (LLMCallMetrics metrics : metricsList) {
            addLlmCallMetrics(metrics);
        }
    }

    // Method to finalize metrics calculation
    public void finalizeMetrics() {
        this.endTime = Instant.now();

        // Calculate averages
        if (llmCallCount > 0) {
            this.avgLlmTimeMs = (double) totalLlmTimeMs / llmCallCount;
        }

        // Calculate cache hit rate
        int totalCacheChecks = cacheHits + cacheMisses;
        if (totalCacheChecks > 0) {
            this.cacheHitRate = (double) cacheHits / totalCacheChecks;
        }

        // Calculate batch metrics
        if (batchCount > 0) {
            this.avgBatchSize = (double) llmCallCount / batchCount;
        }
    }

    // Batch-related setters (used when batching is enabled)
    public void setBatchCount(int batchCount) {
        this.batchCount = batchCount;
    }

    public void setTotalBatchTimeMs(long totalBatchTimeMs) {
        this.totalBatchTimeMs = totalBatchTimeMs;
    }

    // Calculate LLM percentage of total execution time
    public double getLlmTimePercentage() {
        if (executionTimeMs == 0) return 0;
        return (double) totalLlmTimeMs / executionTimeMs * 100;
    }

    @Override
    public String toString() {
        return String.format(
            "QueryExecutionMetrics{totalTimeMs=%d, executionTimeMs=%d, llmCallCount=%d, " +
            "totalLlmTimeMs=%d, avgLlmTimeMs=%.1f, cacheHits=%d, cacheMisses=%d, " +
            "cacheHitRate=%.1f%%, batchCount=%d, resultCount=%d}",
            totalTimeMs, executionTimeMs, llmCallCount, totalLlmTimeMs, avgLlmTimeMs,
            cacheHits, cacheMisses, cacheHitRate * 100, batchCount, resultCount
        );
    }
}
