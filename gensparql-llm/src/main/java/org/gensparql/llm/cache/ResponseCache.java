package org.gensparql.llm.cache;

/**
 * Interface for caching LLM responses.
 */
public interface ResponseCache {

    /**
     * Get a cached response for the given key.
     *
     * @param key the cache key
     * @return the cached response, or null if not found or expired
     */
    CachedResponse get(CacheKey key);

    /**
     * Store a response in the cache.
     *
     * @param key the cache key
     * @param response the response to cache
     */
    void put(CacheKey key, CachedResponse response);

    /**
     * Invalidate (remove) a specific cache entry.
     *
     * @param key the cache key
     */
    void invalidate(CacheKey key);

    /**
     * Clear all cache entries.
     */
    void clear();

    /**
     * Get the number of entries in the cache.
     *
     * @return the cache size
     */
    int size();

    /**
     * Get cache statistics.
     *
     * @return statistics about cache usage
     */
    CacheStatistics getStatistics();
}
