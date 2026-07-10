package org.gensparql.llm.provider;

import org.gensparql.core.model.*;
import org.gensparql.llm.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Mock LLM provider for testing.
 *
 * Returns deterministic responses based on input patterns.
 */
public class MockLLMProvider implements LLMProvider {
    private static final Logger LOG = LoggerFactory.getLogger(MockLLMProvider.class);

    private final Map<String, String> responses = new HashMap<>();
    private final List<GenerateRequest> requestHistory = new ArrayList<>();
    private String defaultResponse = "Mock response";

    @Override
    public String getName() {
        return "mock";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getDefaultModel() {
        return "mock-model";
    }

    /**
     * Set a response for a specific prompt pattern.
     */
    public MockLLMProvider withResponse(String promptContains, String response) {
        responses.put(promptContains, response);
        return this;
    }

    /**
     * Set the default response.
     */
    public MockLLMProvider withDefaultResponse(String response) {
        this.defaultResponse = response;
        return this;
    }

    /**
     * Get request history for verification.
     */
    public List<GenerateRequest> getRequestHistory() {
        return Collections.unmodifiableList(requestHistory);
    }

    /**
     * Clear request history.
     */
    public void clearHistory() {
        requestHistory.clear();
    }

    @Override
    public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
        requestHistory.add(request);
        LOG.debug("Mock generate: {}", truncate(request.getPrompt(), 50));

        String prompt = request.getPrompt();
        String response = defaultResponse;

        // Find matching response
        for (Map.Entry<String, String> entry : responses.entrySet()) {
            if (prompt.contains(entry.getKey())) {
                response = entry.getValue();
                break;
            }
        }

        // Build bindings
        List<Map<String, String>> bindings = new ArrayList<>();
        List<String> outputVars = request.getOutputVariables();
        if (outputVars == null || outputVars.isEmpty()) {
            bindings.add(Map.of("result", response));
        } else if (outputVars.size() == 1) {
            bindings.add(Map.of(outputVars.get(0), response));
        } else {
            // For multiple vars, create a simple mapping
            Map<String, String> binding = new HashMap<>();
            for (int i = 0; i < outputVars.size(); i++) {
                binding.put(outputVars.get(i), response + "_" + i);
            }
            bindings.add(binding);
        }

        return CompletableFuture.completedFuture(
                GenerateResponse.success(response, bindings)
        );
    }

    @Override
    public CompletableFuture<EmbedResponse> embed(EmbedRequest request) {
        LOG.debug("Mock embed: {} texts", request.getTexts().size());

        List<float[]> embeddings = new ArrayList<>();
        for (String text : request.getTexts()) {
            // Generate deterministic embeddings based on text hash
            float[] embedding = generateDeterministicEmbedding(text, 128);
            embeddings.add(embedding);
        }

        return CompletableFuture.completedFuture(EmbedResponse.success(embeddings));
    }

    /**
     * Generate a deterministic embedding based on text content.
     */
    private float[] generateDeterministicEmbedding(String text, int dimensions) {
        float[] embedding = new float[dimensions];
        int hash = text.hashCode();
        Random random = new Random(hash);

        for (int i = 0; i < dimensions; i++) {
            embedding[i] = (random.nextFloat() * 2) - 1; // Range [-1, 1]
        }

        // Normalize
        float norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                embedding[i] /= norm;
            }
        }

        return embedding;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
