package org.gensparql.engine.grounding;

import org.gensparql.core.model.EmbedRequest;
import org.gensparql.core.model.EmbedResponse;
import org.gensparql.llm.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding index for KG entities enabling fast nearest-neighbor lookup.
 *
 * Builds and caches embeddings for entity labels extracted from KG.
 * Supports lazy initialization and batch embedding for efficiency.
 */
public class EntityEmbeddingIndex {
    private static final Logger LOG = LoggerFactory.getLogger(EntityEmbeddingIndex.class);

    // Entity label -> embedding mapping
    private final Map<String, float[]> entityEmbeddings = new ConcurrentHashMap<>();

    // Entity label -> URI mapping for grounding back to URIs
    private final Map<String, String> labelToUri = new ConcurrentHashMap<>();

    // Cache for normalized embeddings (for faster cosine similarity)
    private final Map<String, float[]> normalizedEmbeddings = new ConcurrentHashMap<>();

    // Index metadata
    private int dimensions = 0;
    private boolean initialized = false;

    // Default batch size for embedding API calls
    private static final int DEFAULT_BATCH_SIZE = 50;

    /**
     * Build the index from a set of entity labels.
     *
     * @param entityLabels set of entity labels to index
     * @param provider LLM provider for embedding generation
     */
    public void buildIndex(Set<String> entityLabels, LLMProvider provider) {
        buildIndexBatched(entityLabels, null, provider, DEFAULT_BATCH_SIZE);
    }

    /**
     * Build the index with label to URI mapping.
     *
     * @param labelUriMap mapping from labels to URIs
     * @param provider LLM provider for embedding generation
     */
    public void buildIndex(Map<String, String> labelUriMap, LLMProvider provider) {
        buildIndexBatched(labelUriMap.keySet(), labelUriMap, provider, DEFAULT_BATCH_SIZE);
    }

    /**
     * Build the index with batched embedding API calls.
     *
     * @param entityLabels set of entity labels to index
     * @param labelUriMap optional mapping from labels to URIs
     * @param provider LLM provider for embedding generation
     * @param batchSize number of texts per API call
     */
    public void buildIndexBatched(Set<String> entityLabels, Map<String, String> labelUriMap,
                                   LLMProvider provider, int batchSize) {
        if (entityLabels == null || entityLabels.isEmpty()) {
            LOG.warn("No entities to index");
            return;
        }

        LOG.info("Building embedding index for {} entities (batch size: {})",
                entityLabels.size(), batchSize);

        // Store label to URI mapping if provided
        if (labelUriMap != null) {
            labelToUri.putAll(labelUriMap);
        }

        // Filter out already indexed entities
        List<String> toIndex = new ArrayList<>();
        for (String label : entityLabels) {
            if (!entityEmbeddings.containsKey(label)) {
                toIndex.add(label);
            }
        }

        if (toIndex.isEmpty()) {
            LOG.info("All entities already indexed");
            initialized = true;
            return;
        }

        // Process in batches
        int totalBatches = (toIndex.size() + batchSize - 1) / batchSize;
        int processed = 0;

        LOG.debug("Building embedding index: {} batches, provider: {}",
                totalBatches, provider.getName());

        for (int i = 0; i < toIndex.size(); i += batchSize) {
            int end = Math.min(i + batchSize, toIndex.size());
            List<String> batch = toIndex.subList(i, end);

            try {
                // Call embedding API
                EmbedRequest request = new EmbedRequest(batch, null);
                EmbedResponse response = provider.embedSync(request);

                if (response.isSuccess()) {
                    List<float[]> embeddings = response.getEmbeddings();

                    // Store embeddings
                    for (int j = 0; j < batch.size() && j < embeddings.size(); j++) {
                        String label = batch.get(j);
                        float[] embedding = embeddings.get(j);

                        entityEmbeddings.put(label, embedding);
                        normalizedEmbeddings.put(label, normalize(embedding));

                        if (dimensions == 0 && embedding != null) {
                            dimensions = embedding.length;
                        }
                    }

                    processed += batch.size();
                    LOG.debug("Indexed batch {}/{} ({} entities)",
                            (i / batchSize) + 1, totalBatches, batch.size());
                } else {
                    LOG.error("Embedding API error: {}", response.getErrorMessage());
                }
            } catch (Exception e) {
                LOG.error("Error indexing batch: {}", e.getMessage(), e);
            }
        }

        initialized = true;
        LOG.info("Embedding index built: {} entities, {} dimensions",
                entityEmbeddings.size(), dimensions);
    }

    /**
     * Find the top-K nearest entities to a query text.
     *
     * @param queryText the query text
     * @param k number of results to return
     * @param provider LLM provider for query embedding
     * @return list of grounding candidates sorted by similarity (descending)
     */
    public List<GroundingCandidate> findNearest(String queryText, int k, LLMProvider provider) {
        if (!initialized || entityEmbeddings.isEmpty()) {
            return Collections.emptyList();
        }

        // Get embedding for query
        float[] queryEmbedding = getQueryEmbedding(queryText, provider);
        if (queryEmbedding == null) {
            return Collections.emptyList();
        }

        float[] normalizedQuery = normalize(queryEmbedding);

        // Compute similarity against all entities
        List<GroundingCandidate> candidates = new ArrayList<>();

        for (Map.Entry<String, float[]> entry : normalizedEmbeddings.entrySet()) {
            String label = entry.getKey();
            float[] normalizedEntity = entry.getValue();

            double similarity = cosineSimilarity(normalizedQuery, normalizedEntity);
            String uri = labelToUri.get(label);

            candidates.add(new GroundingCandidate(label, uri, similarity));
        }

        // Sort by similarity descending and take top-K
        candidates.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));

        if (candidates.size() > k) {
            return candidates.subList(0, k);
        }
        return candidates;
    }

    /**
     * Find the best matching entity above a similarity threshold.
     *
     * @param queryText the query text
     * @param threshold minimum similarity threshold
     * @param provider LLM provider for query embedding
     * @return the best matching candidate, or empty if none above threshold
     */
    public Optional<GroundingCandidate> findBestMatch(String queryText, double threshold,
                                                       LLMProvider provider) {
        List<GroundingCandidate> nearest = findNearest(queryText, 1, provider);

        if (nearest.isEmpty()) {
            return Optional.empty();
        }

        GroundingCandidate best = nearest.get(0);
        if (best.getSimilarity() >= threshold) {
            return Optional.of(best);
        }

        return Optional.empty();
    }

    /**
     * Get embedding for a query text.
     */
    private float[] getQueryEmbedding(String text, LLMProvider provider) {
        try {
            EmbedRequest request = new EmbedRequest(text, null);
            EmbedResponse response = provider.embedSync(request);

            if (response.isSuccess()) {
                return response.getFirstEmbedding();
            } else {
                LOG.error("Failed to get query embedding: {}", response.getErrorMessage());
                return null;
            }
        } catch (Exception e) {
            LOG.error("Error getting query embedding: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Normalize a vector to unit length.
     */
    private float[] normalize(float[] vector) {
        if (vector == null || vector.length == 0) {
            return vector;
        }

        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);

        if (norm == 0) {
            return vector;
        }

        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }

    /**
     * Compute cosine similarity between two normalized vectors.
     * For normalized vectors, this is just the dot product.
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }

        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }

        // Clamp to [-1, 1] to handle floating point errors
        return Math.max(-1, Math.min(1, dot));
    }

    /**
     * Check if the index is initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get the number of indexed entities.
     */
    public int size() {
        return entityEmbeddings.size();
    }

    /**
     * Get the embedding dimensions.
     */
    public int getDimensions() {
        return dimensions;
    }

    /**
     * Clear the index.
     */
    public void clear() {
        entityEmbeddings.clear();
        normalizedEmbeddings.clear();
        labelToUri.clear();
        initialized = false;
        dimensions = 0;
    }

    /**
     * Check if a label is indexed.
     */
    public boolean contains(String label) {
        return entityEmbeddings.containsKey(label);
    }

    /**
     * Get the URI for a label.
     */
    public String getUri(String label) {
        return labelToUri.get(label);
    }

    /**
     * A candidate for entity grounding.
     */
    public static class GroundingCandidate {
        private final String label;
        private final String uri;
        private final double similarity;

        public GroundingCandidate(String label, String uri, double similarity) {
            this.label = label;
            this.uri = uri;
            this.similarity = similarity;
        }

        public String getLabel() {
            return label;
        }

        public String getUri() {
            return uri;
        }

        public double getSimilarity() {
            return similarity;
        }

        @Override
        public String toString() {
            return String.format("GroundingCandidate{label='%s', uri='%s', sim=%.4f}",
                    label, uri, similarity);
        }
    }
}
