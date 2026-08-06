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
     * <p>Keeps only the k best rather than scoring everything into a list and sorting it, which
     * for the k=1 that grounding asks for was N allocations and an N log N sort to read one
     * element.
     */
    public List<GroundingCandidate> findNearest(String queryText, int k, LLMProvider provider) {
        if (!initialized || entityEmbeddings.isEmpty() || k <= 0) {
            return Collections.emptyList();
        }
        float[] query = normalizedQueryOrNull(queryText, provider);
        if (query == null) {
            return Collections.emptyList();
        }

        // Worst-of-the-best on top, so the weakest kept candidate is the one to displace.
        PriorityQueue<GroundingCandidate> kept =
                new PriorityQueue<>(Comparator.comparingDouble(GroundingCandidate::getSimilarity));
        for (Map.Entry<String, float[]> entry : normalizedEmbeddings.entrySet()) {
            double similarity = cosineSimilarity(query, entry.getValue());
            if (kept.size() < k) {
                kept.add(new GroundingCandidate(entry.getKey(), labelToUri.get(entry.getKey()), similarity));
            } else if (similarity > kept.peek().getSimilarity()) {
                kept.poll();
                kept.add(new GroundingCandidate(entry.getKey(), labelToUri.get(entry.getKey()), similarity));
            }
        }

        List<GroundingCandidate> out = new ArrayList<>(kept);
        out.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        return out;
    }

    /**
     * Find the single best match at or above {@code threshold}, or empty.
     *
     * <p>One pass, no list of every entity and no sort. The threshold is used while scanning
     * rather than only at the end: a candidate whose score cannot reach the best seen so far,
     * nor the threshold, is abandoned part way through its dot product.
     *
     * <p>The bound is exact. Vectors are unit length, so by Cauchy-Schwarz the remaining
     * dimensions can contribute at most the norm of the query's own remaining dimensions. A
     * candidate is dropped only when even that best case leaves it short, so the answer is the
     * same as scoring everything in full.
     */
    public Optional<GroundingCandidate> findBestMatch(String queryText, double threshold,
                                                      LLMProvider provider) {
        if (!initialized || entityEmbeddings.isEmpty()) {
            return Optional.empty();
        }
        float[] query = normalizedQueryOrNull(queryText, provider);
        if (query == null) {
            return Optional.empty();
        }
        double[] querySuffixNorm = suffixNorms(query);

        String bestLabel = null;
        double bestScore = -1;
        for (Map.Entry<String, float[]> entry : normalizedEmbeddings.entrySet()) {
            // Anything that cannot beat both the threshold and the incumbent is not wanted.
            double floor = Math.max(threshold, bestScore);
            double similarity = dotAtLeast(query, entry.getValue(), querySuffixNorm, floor);
            if (similarity > bestScore) {
                bestScore = similarity;
                bestLabel = entry.getKey();
            }
        }

        if (bestLabel == null || bestScore < threshold) {
            return Optional.empty();
        }
        return Optional.of(new GroundingCandidate(bestLabel, labelToUri.get(bestLabel), bestScore));
    }

    /**
     * Dot product of two unit vectors, abandoned once it cannot reach {@code floor}.
     *
     * @return the similarity, or a value below {@code floor} if it was abandoned. Either way it
     *         is never above the true similarity, so a caller comparing against floor is safe.
     */
    private double dotAtLeast(float[] query, float[] candidate, double[] querySuffixNorm,
                              double floor) {
        if (candidate == null || candidate.length != query.length) {
            return 0;
        }
        double partial = 0;
        for (int i = 0; i < query.length; i++) {
            partial += query[i] * candidate[i];
            // The rest can add at most ||query tail|| * ||candidate tail||, and the candidate is
            // unit length so its tail norm is at most 1.
            if (partial + querySuffixNorm[i + 1] < floor) {
                return Double.NEGATIVE_INFINITY;
            }
        }
        return Math.max(0, Math.min(1, partial));
    }

    /** {@code out[i]} is the norm of {@code v[i..]}, so out[length] is 0. */
    private static double[] suffixNorms(float[] v) {
        double[] norms = new double[v.length + 1];
        double sumSquares = 0;
        for (int i = v.length - 1; i >= 0; i--) {
            sumSquares += (double) v[i] * v[i];
            norms[i] = Math.sqrt(sumSquares);
        }
        return norms;
    }

    /** The query embedding, normalized, or null if it could not be obtained. */
    private float[] normalizedQueryOrNull(String queryText, LLMProvider provider) {
        float[] raw = getQueryEmbedding(queryText, provider);
        return raw == null ? null : normalize(raw);
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

        // Clamp to [0, 1]: similarity thresholds are expressed in [0, 1], and a negative
        // cosine (dissimilar) is never a grounding candidate. Matches EmbeddingSimText.
        return Math.max(0, Math.min(1, dot));
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
