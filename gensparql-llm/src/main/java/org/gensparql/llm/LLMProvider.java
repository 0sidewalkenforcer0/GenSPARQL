package org.gensparql.llm;

import org.gensparql.core.model.*;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for LLM providers in GenSPARQL.
 *
 * Implementations provide text generation and embedding capabilities
 * for different LLM services (OpenAI, Anthropic, OpenRouter, etc.).
 */
public interface LLMProvider {

    /**
     * Get the provider name (e.g., "openai", "anthropic").
     */
    String getName();

    /**
     * Check if the provider is available and configured.
     */
    boolean isAvailable();

    /**
     * Generate text from a prompt.
     *
     * @param request the generation request
     * @return future containing the generation response
     */
    CompletableFuture<GenerateResponse> generate(GenerateRequest request);

    /**
     * Generate text from a prompt (blocking).
     *
     * @param request the generation request
     * @return the generation response
     */
    default GenerateResponse generateSync(GenerateRequest request) {
        return generate(request).join();
    }

    /**
     * Generate embeddings for text.
     *
     * @param request the embedding request
     * @return future containing the embedding response
     */
    CompletableFuture<EmbedResponse> embed(EmbedRequest request);

    /**
     * Generate embedding for text (blocking).
     *
     * @param request the embedding request
     * @return the embedding response
     */
    default EmbedResponse embedSync(EmbedRequest request) {
        return embed(request).join();
    }

    /**
     * Check if this provider supports embedding generation.
     */
    default boolean supportsEmbedding() {
        return true;
    }

    /**
     * Get the default model for this provider.
     */
    String getDefaultModel();

    /**
     * Get the default embedding model for this provider.
     */
    default String getDefaultEmbeddingModel() {
        return getDefaultModel();
    }

    /**
     * Close and release resources.
     */
    default void close() {
        // Default no-op
    }
}
