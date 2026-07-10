package org.gensparql.engine.similarity;

import org.gensparql.core.model.EmbedRequest;
import org.gensparql.core.model.EmbedResponse;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.core.similarity.SimText;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding-based text similarity using LLM embeddings.
 *
 * Uses cosine similarity between embedding vectors when available,
 * falls back to Jaccard similarity for robustness.
 *
 * Includes caching for efficiency.
 */
public class EmbeddingSimText implements SimText {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingSimText.class);

    private static final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 10000;

    private final SimText fallback;

    public EmbeddingSimText() {
        this.fallback = new JaccardSimText();
    }

    public EmbeddingSimText(SimText fallback) {
        this.fallback = fallback != null ? fallback : new JaccardSimText();
    }

    @Override
    public double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return s1 == s2 ? 1.0 : 0.0;
        }

        // Quick exact match check
        if (s1.equals(s2)) {
            LOG.debug("Exact match: \"{}\" == \"{}\" -> 1.0", s1, s2);
            return 1.0;
        }

        // Case-insensitive match (consistent with JaccardSimText)
        if (s1.equalsIgnoreCase(s2)) {
            LOG.debug("Case-insensitive match: \"{}\" ~= \"{}\" -> 0.95", s1, s2);
            return 0.95;
        }

        try {
            // Try embedding-based similarity
            float[] emb1 = getEmbedding(s1);
            float[] emb2 = getEmbedding(s2);

            if (emb1 != null && emb2 != null) {
                double similarity = cosineSimilarity(emb1, emb2);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Embedding similarity: s1=\"{}\" emb1={}", truncate(s1, 50), formatEmbedding(emb1));
                    LOG.debug("Embedding similarity: s2=\"{}\" emb2={}", truncate(s2, 50), formatEmbedding(emb2));
                    LOG.debug("Embedding cosine_similarity={}", String.format("%.6f", similarity));
                }
                return similarity;
            }
        } catch (Exception e) {
            LOG.debug("Embedding similarity failed, using fallback: {}", e.getMessage());
        }

        // Fall back to text-based similarity
        LOG.debug("Using fallback (Jaccard) for: \"{}\" vs \"{}\"", truncate(s1, 30), truncate(s2, 30));
        return fallback.similarity(s1, s2);
    }

    /**
     * Format embedding for display (show first and last few dimensions).
     */
    private String formatEmbedding(float[] emb) {
        if (emb == null) return "null";
        if (emb.length <= 10) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < emb.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("%.4f", emb[i]));
            }
            sb.append("] (len=").append(emb.length).append(")");
            return sb.toString();
        }
        // Show first 3 and last 2 dimensions
        return String.format("[%.4f, %.4f, %.4f, ..., %.4f, %.4f] (len=%d)",
                emb[0], emb[1], emb[2], emb[emb.length-2], emb[emb.length-1], emb.length);
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Get embedding for text (with caching).
     */
    private float[] getEmbedding(String text) {
        // Check cache first
        float[] cached = embeddingCache.get(text);
        if (cached != null) {
            return cached;
        }

        try {
            LLMProvider provider = LLMProviderRegistry.getDefault();
            if (provider == null || !provider.supportsEmbedding()) {
                return null;
            }

            EmbedRequest request = new EmbedRequest(text, null);
            EmbedResponse response = provider.embedSync(request);

            if (response.isSuccess()) {
                float[] embedding = response.getFirstEmbedding();

                // Cache if not too large
                if (embedding != null && embeddingCache.size() < MAX_CACHE_SIZE) {
                    embeddingCache.put(text, embedding);
                }

                return embedding;
            }
        } catch (Exception e) {
            LOG.debug("Failed to get embedding: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Compute cosine similarity between two vectors.
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same length");
        }

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0;
        }

        double similarity = dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        // Normalize to [0, 1] range (cosine similarity is [-1, 1])
        return (similarity + 1) / 2;
    }

    @Override
    public String getName() {
        return "Embedding";
    }

    /**
     * Clear the embedding cache.
     */
    public static void clearCache() {
        embeddingCache.clear();
    }

    /**
     * Get the current cache size.
     */
    public static int getCacheSize() {
        return embeddingCache.size();
    }
}
