package org.gensparql.llm.cache;

import org.gensparql.core.model.*;
import org.gensparql.llm.LLMProvider;

import java.util.concurrent.CompletableFuture;

/**
 * Decorator that adds caching to any LLMProvider.
 * Uses the Decorator pattern to transparently cache LLM responses.
 */
public class CachingLLMProvider implements LLMProvider {

    private final LLMProvider delegate;
    private final ResponseCache cache;
    private final CacheStatistics statistics;

    /**
     * Create a caching provider with the default in-memory cache.
     */
    public CachingLLMProvider(LLMProvider delegate) {
        this(delegate, new InMemoryResponseCache());
    }

    /**
     * Create a caching provider with a custom cache implementation.
     */
    public CachingLLMProvider(LLMProvider delegate, ResponseCache cache) {
        this.delegate = delegate;
        this.cache = cache;
        this.statistics = cache.getStatistics();
    }

    @Override
    public String getName() {
        return delegate.getName() + "-cached";
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
        // Create cache key
        CacheKey key = createCacheKey(request);

        // Check cache
        CachedResponse cached = cache.get(key);
        if (cached != null && !cached.isExpired()) {
            // Cache hit - return immediately
            return CompletableFuture.completedFuture(cached.getResponse());
        }

        // Cache miss - call delegate and cache result
        return delegate.generate(request)
            .thenApply(response -> {
                if (response != null && response.isSuccess()) {
                    cache.put(key, new CachedResponse(response));
                }
                return response;
            });
    }

    @Override
    public CompletableFuture<EmbedResponse> embed(EmbedRequest request) {
        // For now, don't cache embeddings (they're already cached by EmbeddingSimText)
        // Could add embedding caching here in the future
        return delegate.embed(request);
    }

    @Override
    public boolean supportsEmbedding() {
        return delegate.supportsEmbedding();
    }

    @Override
    public String getDefaultModel() {
        return delegate.getDefaultModel();
    }

    @Override
    public String getDefaultEmbeddingModel() {
        return delegate.getDefaultEmbeddingModel();
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * Create a cache key from a generation request.
     */
    private CacheKey createCacheKey(GenerateRequest request) {
        ModelSpec modelSpec = request.getModelSpec();
        String modelName = modelSpec != null ? modelSpec.getModel() : getDefaultModel();

        return new CacheKey(
            getName(),
            modelName,
            request.getPrompt(),
            request.getTemperature(),
            request.getMaxTokens(),
            request.getOutputVariables()
        );
    }

    /**
     * Get the underlying cache.
     */
    public ResponseCache getCache() {
        return cache;
    }

    /**
     * Get cache statistics.
     */
    public CacheStatistics getStatistics() {
        return statistics;
    }

    /**
     * Clear the cache.
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * Get the number of cached entries.
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Get the underlying (non-cached) provider.
     */
    public LLMProvider getDelegate() {
        return delegate;
    }

    @Override
    public String toString() {
        return String.format("CachingLLMProvider{delegate=%s, cacheSize=%d, %s}",
            delegate.getName(), cache.size(), statistics);
    }
}
