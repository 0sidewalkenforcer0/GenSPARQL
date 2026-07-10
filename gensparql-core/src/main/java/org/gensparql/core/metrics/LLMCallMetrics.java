package org.gensparql.core.metrics;

import java.time.Instant;

/**
 * Metrics for a single LLM API call.
 * Tracks timing, tokens, cache hit/miss, and prompt information.
 */
public class LLMCallMetrics {

    // Timing
    private long latencyMs;
    private Instant timestamp;

    // Token usage
    private int promptTokens;
    private int completionTokens;

    // Cache information
    private boolean cacheHit;

    // Prompt information
    private String promptHash;        // SHA-256 hash for identification
    private String promptPreview;     // First 100 chars for debugging
    private int promptLength;

    // Batch information (if part of a batch)
    private boolean partOfBatch;
    private int batchId;
    private int positionInBatch;

    // Model information
    private String modelName;
    private String providerName;

    // Request ID from provider (if available)
    private String requestId;

    public LLMCallMetrics() {
        this.timestamp = Instant.now();
    }

    // Getters
    public long getLatencyMs() {
        return latencyMs;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean isCacheHit() {
        return cacheHit;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public String getPromptPreview() {
        return promptPreview;
    }

    public int getPromptLength() {
        return promptLength;
    }

    public boolean isPartOfBatch() {
        return partOfBatch;
    }

    public int getBatchId() {
        return batchId;
    }

    public int getPositionInBatch() {
        return positionInBatch;
    }

    public String getModelName() {
        return modelName;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getRequestId() {
        return requestId;
    }

    // Setters
    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public void setPromptTokens(int promptTokens) {
        this.promptTokens = promptTokens;
    }

    public void setCompletionTokens(int completionTokens) {
        this.completionTokens = completionTokens;
    }

    public void setCacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public void setPromptHash(String promptHash) {
        this.promptHash = promptHash;
    }

    public void setPromptPreview(String promptPreview) {
        this.promptPreview = promptPreview;
    }

    public void setPromptLength(int promptLength) {
        this.promptLength = promptLength;
    }

    public void setPartOfBatch(boolean partOfBatch) {
        this.partOfBatch = partOfBatch;
    }

    public void setBatchId(int batchId) {
        this.batchId = batchId;
    }

    public void setPositionInBatch(int positionInBatch) {
        this.positionInBatch = positionInBatch;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    // Builder pattern for convenient construction
    public static class Builder {
        private LLMCallMetrics metrics = new LLMCallMetrics();

        public Builder latencyMs(long latencyMs) {
            metrics.latencyMs = latencyMs;
            return this;
        }

        public Builder promptTokens(int tokens) {
            metrics.promptTokens = tokens;
            return this;
        }

        public Builder completionTokens(int tokens) {
            metrics.completionTokens = tokens;
            return this;
        }

        public Builder cacheHit(boolean hit) {
            metrics.cacheHit = hit;
            return this;
        }

        public Builder promptHash(String hash) {
            metrics.promptHash = hash;
            return this;
        }

        public Builder promptPreview(String preview) {
            metrics.promptPreview = preview;
            return this;
        }

        public Builder promptLength(int length) {
            metrics.promptLength = length;
            return this;
        }

        public Builder modelName(String model) {
            metrics.modelName = model;
            return this;
        }

        public Builder providerName(String provider) {
            metrics.providerName = provider;
            return this;
        }

        public Builder requestId(String id) {
            metrics.requestId = id;
            return this;
        }

        public Builder partOfBatch(boolean inBatch) {
            metrics.partOfBatch = inBatch;
            return this;
        }

        public Builder batchId(int id) {
            metrics.batchId = id;
            return this;
        }

        public Builder positionInBatch(int position) {
            metrics.positionInBatch = position;
            return this;
        }

        public LLMCallMetrics build() {
            return metrics;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return String.format(
            "LLMCallMetrics{latencyMs=%d, tokens=%d/%d, cacheHit=%s, model=%s, batch=%s}",
            latencyMs, promptTokens, completionTokens, cacheHit,
            modelName != null ? modelName : "unknown",
            partOfBatch ? "batch#" + batchId : "no"
        );
    }
}
