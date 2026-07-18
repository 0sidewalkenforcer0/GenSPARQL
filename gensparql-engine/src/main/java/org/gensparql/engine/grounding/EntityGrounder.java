package org.gensparql.engine.grounding;

import org.apache.jena.query.Dataset;
import org.apache.jena.sparql.core.DatasetGraph;
import org.gensparql.core.model.GroundingResult;
import org.gensparql.engine.candidate.CandidateExtractor;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates entity grounding by mapping LLM free-text outputs to KG entities
 * using embedding similarity.
 *
 * Workflow:
 * 1. Extract candidate entities from KG (via CandidateExtractor)
 * 2. Build embedding index (lazy, per-relation)
 * 3. For each LLM output, find nearest KG entity
 * 4. Return grounded value if similarity >= threshold
 */
public class EntityGrounder {
    private static final Logger LOG = LoggerFactory.getLogger(EntityGrounder.class);

    private final CandidateExtractor candidateExtractor;
    private final LLMProvider embeddingProvider;

    // Per-relation embedding indexes (lazy initialization)
    private final Map<String, EntityEmbeddingIndex> relationIndexes = new ConcurrentHashMap<>();

    // Global index (for non-relation-specific grounding)
    private EntityEmbeddingIndex globalIndex;

    // Default configuration
    private double defaultThreshold = 0.8;
    private int batchSize = 50;

    /**
     * Create an EntityGrounder with a dataset for candidate extraction.
     *
     * @param dataset the RDF dataset containing KG entities
     */
    public EntityGrounder(Dataset dataset) {
        this(dataset, LLMProviderRegistry.getDefault());
    }

    /**
     * Create an EntityGrounder with custom LLM provider.
     *
     * @param dataset the RDF dataset containing KG entities
     * @param embeddingProvider LLM provider for embeddings
     */
    public EntityGrounder(Dataset dataset, LLMProvider embeddingProvider) {
        this.candidateExtractor = new CandidateExtractor(dataset);
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Create an EntityGrounder with a DatasetGraph for candidate extraction.
     *
     * @param dsg the DatasetGraph containing KG entities
     */
    public EntityGrounder(DatasetGraph dsg) {
        this(dsg, LLMProviderRegistry.getDefault());
    }

    /**
     * Create an EntityGrounder with DatasetGraph and custom LLM provider.
     *
     * @param dsg the DatasetGraph containing KG entities
     * @param embeddingProvider LLM provider for embeddings
     */
    public EntityGrounder(DatasetGraph dsg, LLMProvider embeddingProvider) {
        this.candidateExtractor = new CandidateExtractor(dsg);
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * Ground an LLM output to a KG entity using embedding similarity.
     *
     * @param llmOutput the free-text output from LLM
     * @param relationUri the relation URI for candidate extraction (can be null)
     * @return grounding result with matched entity or ungrounded status
     */
    public GroundingResult ground(String llmOutput, String relationUri) {
        return ground(llmOutput, relationUri, defaultThreshold);
    }

    /**
     * Ground an LLM output to a KG entity with custom threshold.
     *
     * @param llmOutput the free-text output from LLM
     * @param relationUri the relation URI for candidate extraction (can be null)
     * @param threshold minimum similarity threshold
     * @return grounding result with matched entity or ungrounded status
     */
    public GroundingResult ground(String llmOutput, String relationUri, double threshold) {
        if (llmOutput == null || llmOutput.trim().isEmpty()) {
            return GroundingResult.ungrounded(llmOutput);
        }

        // Get or build the embedding index
        EntityEmbeddingIndex index = getOrBuildIndex(relationUri);

        if (index == null || !index.isInitialized() || index.size() == 0) {
            LOG.debug("No index available for relation: {}", relationUri);
            return GroundingResult.ungrounded(llmOutput);
        }

        // Find best match
        Optional<EntityEmbeddingIndex.GroundingCandidate> match =
                index.findBestMatch(llmOutput.trim(), threshold, embeddingProvider);

        if (match.isPresent()) {
            EntityEmbeddingIndex.GroundingCandidate candidate = match.get();
            LOG.info("Grounded '{}' -> '{}' (sim={:.4f}, uri={})",
                    llmOutput, candidate.getLabel(), candidate.getSimilarity(), candidate.getUri());

            if (candidate.getUri() != null) {
                return GroundingResult.groundedWithUri(
                        llmOutput,
                        candidate.getLabel(),
                        candidate.getUri(),
                        candidate.getSimilarity()
                );
            } else {
                return GroundingResult.grounded(
                        llmOutput,
                        candidate.getLabel(),
                        candidate.getSimilarity()
                );
            }
        }

        LOG.debug("No match found for '{}' above threshold {}", llmOutput, threshold);
        return GroundingResult.ungrounded(llmOutput);
    }

    /**
     * Ground multiple LLM outputs in batch.
     *
     * @param llmOutputs list of LLM outputs to ground
     * @param relationUri the relation URI for candidate extraction
     * @param threshold minimum similarity threshold
     * @return list of grounding results
     */
    public List<GroundingResult> groundBatch(List<String> llmOutputs, String relationUri, double threshold) {
        List<GroundingResult> results = new ArrayList<>();
        for (String output : llmOutputs) {
            results.add(ground(output, relationUri, threshold));
        }
        return results;
    }

    /**
     * Initialize the index for a relation (pre-build before grounding).
     *
     * @param relationUri the relation URI
     */
    public void initializeForRelation(String relationUri) {
        getOrBuildIndex(relationUri);
    }

    /**
     * Get or lazily build the embedding index for a relation.
     */
    private EntityEmbeddingIndex getOrBuildIndex(String relationUri) {
        if (relationUri == null || relationUri.isEmpty()) {
            // Use global index if no relation specified
            return getOrBuildGlobalIndex();
        }

        return relationIndexes.computeIfAbsent(relationUri, this::buildIndexForRelation);
    }

    /**
     * Build embedding index for a specific relation.
     */
    private EntityEmbeddingIndex buildIndexForRelation(String relationUri) {
        LOG.info("Building embedding index for relation: {}", relationUri);

        // Extract candidates with URIs
        Map<String, String> labelToUri = candidateExtractor.extractCandidatesWithUris(relationUri);

        if (labelToUri.isEmpty()) {
            LOG.warn("No candidates found for relation: {}", relationUri);
            return new EntityEmbeddingIndex();
        }

        LOG.info("Found {} candidate entities for relation: {}", labelToUri.size(), relationUri);

        EntityEmbeddingIndex index = new EntityEmbeddingIndex();
        index.buildIndexBatched(labelToUri.keySet(), labelToUri, embeddingProvider, batchSize);

        return index;
    }

    /**
     * Get or build a global index (all entities).
     */
    private EntityEmbeddingIndex getOrBuildGlobalIndex() {
        if (globalIndex == null) {
            synchronized (this) {
                if (globalIndex == null) {
                    LOG.info("Building global embedding index...");

                    // Extract all entities with their URIs, so a grounded result
                    // carries the KG node IRI, not just a label string. Without the
                    // URI map, grounding binds a literal that fails any join
                    // requiring the variable to be a KG entity.
                    Map<String, String> labelToUri = candidateExtractor.extractAllEntitiesWithUris();

                    if (labelToUri.isEmpty()) {
                        LOG.warn("No entities found in dataset");
                        globalIndex = new EntityEmbeddingIndex();
                    } else {
                        LOG.info("Found {} total entities", labelToUri.size());
                        globalIndex = new EntityEmbeddingIndex();
                        globalIndex.buildIndex(labelToUri, embeddingProvider);
                    }
                }
            }
        }
        return globalIndex;
    }

    /**
     * Set the default similarity threshold.
     */
    public void setDefaultThreshold(double threshold) {
        this.defaultThreshold = threshold;
    }

    /**
     * Get the default similarity threshold.
     */
    public double getDefaultThreshold() {
        return defaultThreshold;
    }

    /**
     * Set the embedding batch size.
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * Check if an index exists for a relation.
     */
    public boolean hasIndexForRelation(String relationUri) {
        return relationIndexes.containsKey(relationUri);
    }

    /**
     * Get statistics about the indexes.
     */
    public Map<String, Integer> getIndexStats() {
        Map<String, Integer> stats = new HashMap<>();
        for (Map.Entry<String, EntityEmbeddingIndex> entry : relationIndexes.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().size());
        }
        if (globalIndex != null) {
            stats.put("_global", globalIndex.size());
        }
        return stats;
    }

    /**
     * Clear all indexes.
     */
    public void clearIndexes() {
        for (EntityEmbeddingIndex index : relationIndexes.values()) {
            index.clear();
        }
        relationIndexes.clear();
        if (globalIndex != null) {
            globalIndex.clear();
            globalIndex = null;
        }
    }
}
