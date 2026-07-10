package org.gensparql.llm.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * Cache key for LLM responses.
 * Combines provider, model, prompt, and parameters into a unique identifier.
 */
public class CacheKey {

    private final String provider;
    private final String model;
    private final String promptHash;      // SHA-256 hash of prompt
    private final String promptPreview;   // First 100 chars for debugging
    private final double temperature;
    private final int maxTokens;
    private final List<String> outputVariables;

    public CacheKey(String provider, String model, String prompt, double temperature,
                   int maxTokens, List<String> outputVariables) {
        this.provider = provider;
        this.model = model;
        this.promptHash = sha256(prompt);
        this.promptPreview = prompt.length() > 100 ? prompt.substring(0, 100) : prompt;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.outputVariables = outputVariables != null ? List.copyOf(outputVariables) : List.of();
    }

    /**
     * Compute SHA-256 hash of a string.
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Generate a string representation suitable for file names.
     */
    public String toStringKey() {
        return String.format("%s:%s:%s:%.2f:%d:%s",
            provider != null ? provider : "unknown",
            model != null ? model : "unknown",
            promptHash,
            temperature,
            maxTokens,
            String.join(",", outputVariables)
        );
    }

    /**
     * Generate a shorter key for in-memory maps (excludes provider/model for brevity).
     */
    public String toShortKey() {
        return String.format("%s:%.2f:%d",
            promptHash,
            temperature,
            maxTokens
        );
    }

    // Getters
    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getPromptHash() {
        return promptHash;
    }

    public String getPromptPreview() {
        return promptPreview;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public List<String> getOutputVariables() {
        return outputVariables;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CacheKey cacheKey = (CacheKey) o;
        return Double.compare(cacheKey.temperature, temperature) == 0 &&
               maxTokens == cacheKey.maxTokens &&
               Objects.equals(provider, cacheKey.provider) &&
               Objects.equals(model, cacheKey.model) &&
               Objects.equals(promptHash, cacheKey.promptHash) &&
               Objects.equals(outputVariables, cacheKey.outputVariables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model, promptHash, temperature, maxTokens, outputVariables);
    }

    @Override
    public String toString() {
        return String.format("CacheKey{provider=%s, model=%s, promptHash=%s, " +
                           "promptPreview='%s...', temp=%.2f, maxTokens=%d, vars=%s}",
            provider, model, promptHash.substring(0, 8), promptPreview,
            temperature, maxTokens, outputVariables);
    }
}
