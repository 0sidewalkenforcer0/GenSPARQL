package org.gensparql.llm.cache;

import org.gensparql.core.model.GenerateResponse;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Wrapper for cached LLM responses with expiration support.
 */
public class CachedResponse {

    private final GenerateResponse response;
    private final Instant cachedAt;
    private final long ttlMillis;

    /**
     * Default TTL: 7 days
     */
    public static final long DEFAULT_TTL_DAYS = 7;
    public static final long DEFAULT_TTL_MILLIS = TimeUnit.DAYS.toMillis(DEFAULT_TTL_DAYS);

    public CachedResponse(GenerateResponse response) {
        this(response, DEFAULT_TTL_MILLIS);
    }

    public CachedResponse(GenerateResponse response, long ttlMillis) {
        this.response = response;
        this.cachedAt = Instant.now();
        this.ttlMillis = ttlMillis;
    }

    /**
     * Create a cached response with custom cached timestamp (for deserialization).
     */
    public CachedResponse(GenerateResponse response, Instant cachedAt, long ttlMillis) {
        this.response = response;
        this.cachedAt = cachedAt;
        this.ttlMillis = ttlMillis;
    }

    public GenerateResponse getResponse() {
        return response;
    }

    public Instant getCachedAt() {
        return cachedAt;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    /**
     * Check if this cached response has expired.
     */
    public boolean isExpired() {
        long ageMillis = Instant.now().toEpochMilli() - cachedAt.toEpochMilli();
        return ageMillis > ttlMillis;
    }

    /**
     * Get remaining time until expiration in milliseconds.
     */
    public long getRemainingTtlMillis() {
        long ageMillis = Instant.now().toEpochMilli() - cachedAt.toEpochMilli();
        long remaining = ttlMillis - ageMillis;
        return Math.max(0, remaining);
    }

    /**
     * Get age of cached response in milliseconds.
     */
    public long getAgeMillis() {
        return Instant.now().toEpochMilli() - cachedAt.toEpochMilli();
    }

    @Override
    public String toString() {
        return String.format("CachedResponse{cachedAt=%s, age=%dms, expired=%s}",
            cachedAt, getAgeMillis(), isExpired());
    }
}
