package org.gensparql.function.similarity;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;
import org.gensparql.core.model.EmbedRequest;
import org.gensparql.core.model.EmbedResponse;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gen:similarity(?x, ?y) - Computes semantic similarity between two values.
 *
 * Returns a value between 0.0 and 1.0 where:
 * - 1.0 = identical or semantically equivalent
 * - 0.0 = completely different
 *
 * Uses embedding-based cosine similarity when LLM embeddings are available,
 * falls back to text-based similarity otherwise.
 */
public class SimilarityFunction extends FunctionBase2 {
    private static final Logger LOG = LoggerFactory.getLogger(SimilarityFunction.class);

    // Simple cache for embeddings to reduce API calls
    private static final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 10000;

    @Override
    public NodeValue exec(NodeValue v1, NodeValue v2) {
        String text1 = nodeValueToString(v1);
        String text2 = nodeValueToString(v2);

        // Quick exact match check
        if (text1.equals(text2)) {
            return NodeValue.makeDouble(1.0);
        }

        double similarity;
        try {
            // Try embedding-based similarity first
            similarity = computeEmbeddingSimilarity(text1, text2);
        } catch (Exception e) {
            LOG.debug("Embedding similarity failed, using text-based: {}", e.getMessage());
            similarity = computeTextSimilarity(text1, text2);
        }

        return NodeValue.makeDouble(similarity);
    }

    /**
     * Compute similarity using embeddings.
     */
    private double computeEmbeddingSimilarity(String text1, String text2) {
        float[] emb1 = getEmbedding(text1);
        float[] emb2 = getEmbedding(text2);

        if (emb1 == null || emb2 == null) {
            throw new RuntimeException("Could not get embeddings");
        }

        return cosineSimilarity(emb1, emb2);
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

        // Get embedding from LLM provider
        try {
            LLMProvider provider = LLMProviderRegistry.getDefault();
            if (!provider.supportsEmbedding()) {
                return null;
            }

            EmbedRequest request = new EmbedRequest(text, null);
            EmbedResponse response = provider.embedSync(request);

            if (response.isSuccess()) {
                float[] embedding = response.getFirstEmbedding();

                // Cache if not too large
                if (embeddingCache.size() < MAX_CACHE_SIZE) {
                    embeddingCache.put(text, embedding);
                }

                return embedding;
            }
        } catch (Exception e) {
            LOG.warn("Failed to get embedding: {}", e.getMessage());
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

    /**
     * Compute text-based similarity (fallback).
     */
    private double computeTextSimilarity(String text1, String text2) {
        // Use Jaccard similarity on words
        String[] words1 = text1.toLowerCase().split("\\s+");
        String[] words2 = text2.toLowerCase().split("\\s+");

        java.util.Set<String> set1 = new java.util.HashSet<>(java.util.Arrays.asList(words1));
        java.util.Set<String> set2 = new java.util.HashSet<>(java.util.Arrays.asList(words2));

        java.util.Set<String> intersection = new java.util.HashSet<>(set1);
        intersection.retainAll(set2);

        java.util.Set<String> union = new java.util.HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0;
        }

        return (double) intersection.size() / union.size();
    }

    private String nodeValueToString(NodeValue nv) {
        if (nv.isString()) {
            return nv.getString();
        } else if (nv.isLiteral()) {
            return nv.asNode().getLiteralLexicalForm();
        } else if (nv.isIRI()) {
            return nv.asNode().getURI();
        }
        return nv.toString();
    }

    /**
     * Clear the embedding cache.
     */
    public static void clearCache() {
        embeddingCache.clear();
    }
}
