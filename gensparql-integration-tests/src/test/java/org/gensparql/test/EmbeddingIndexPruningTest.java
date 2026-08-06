package org.gensparql.test;

import org.gensparql.core.model.EmbedRequest;
import org.gensparql.core.model.EmbedResponse;
import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
import org.gensparql.engine.grounding.EntityEmbeddingIndex;
import org.gensparql.llm.LLMProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Searching the entity index must give the same answer whether or not it prunes.
 *
 * <p>findBestMatch used to score every entity into a list and sort all of it to read one
 * element, with the threshold applied only afterwards, so the work was the same at every
 * threshold. It now stops a candidate's dot product once even its best case cannot reach the
 * threshold or the incumbent. That bound is exact, and these tests are what says so: the pruned
 * search is compared against scoring everything in full, over many queries and thresholds.
 */
@DisplayName("entity index pruning")
public class EmbeddingIndexPruningTest {

    /** Deterministic embeddings, so a test can reason about what the right answer is. */
    private static final class HashingEmbedder implements LLMProvider {
        static final int DIMS = 64;

        @Override
        public CompletableFuture<EmbedResponse> embed(EmbedRequest request) {
            List<float[]> out = new ArrayList<>();
            for (String text : request.getTexts()) {
                out.add(vector(text));
            }
            return CompletableFuture.completedFuture(EmbedResponse.success(out));
        }

        /** Character trigrams hashed into buckets: similar spellings give similar vectors. */
        static float[] vector(String text) {
            float[] v = new float[DIMS];
            String t = text.toLowerCase();
            for (int i = 0; i + 2 < t.length(); i++) {
                v[Math.floorMod(t.substring(i, i + 3).hashCode(), DIMS)] += 1f;
            }
            v[Math.floorMod(t.length(), DIMS)] += 0.5f;
            return v;
        }

        @Override
        public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public String getName() {
            return "hashing";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getDefaultModel() {
            return "hashing";
        }
    }

    private static final List<String> LABELS = List.of(
            "Brazil", "Argentina", "France", "Japan", "United States", "Mexico", "Turkey",
            "Czechia", "Uruguay", "Uzbekistan", "Colombia", "Tunisia", "Saudi Arabia",
            "South Korea", "South Africa", "New Zealand", "Ivory Coast", "Cape Verde",
            "Bosnia and Herzegovina", "DR Congo", "Curacao", "Paraguay", "Panama", "Peru");

    private static final List<String> QUERIES = List.of(
            "Brazil", "Argentina", "Turkiye", "Czech Republic", "Italy", "Russia", "Serbia",
            "United States of America", "Mexico", "Korea", "zzzzzzzz", "");

    private static EntityEmbeddingIndex index;
    private static final HashingEmbedder EMBEDDER = new HashingEmbedder();

    @BeforeAll
    static void buildIndex() {
        Map<String, String> labelToUri = new LinkedHashMap<>();
        for (String label : LABELS) {
            labelToUri.put(label, "http://example.org/" + label.replace(' ', '_'));
        }
        index = new EntityEmbeddingIndex();
        index.buildIndex(labelToUri, EMBEDDER);
        assertTrue(index.isInitialized());
        assertEquals(LABELS.size(), index.size());
    }

    /** Score everything in full, the way the index used to, as the reference answer. */
    private static Optional<String> bruteForceBest(String query, double threshold) {
        float[] q = unit(HashingEmbedder.vector(query));
        String bestLabel = null;
        double best = -1;
        for (String label : LABELS) {
            double dot = Math.max(0, Math.min(1, dot(q, unit(HashingEmbedder.vector(label)))));
            if (dot > best) {
                best = dot;
                bestLabel = label;
            }
        }
        return (bestLabel != null && best >= threshold) ? Optional.of(bestLabel) : Optional.empty();
    }

    private static float[] unit(float[] v) {
        double n = 0;
        for (float x : v) {
            n += (double) x * x;
        }
        n = Math.sqrt(n);
        if (n == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / n);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += (double) a[i] * b[i];
        }
        return s;
    }

    @Test
    @DisplayName("the pruned search agrees with scoring everything, at every threshold")
    void prunedSearchAgreesWithFullScan() {
        for (double threshold = 0.0; threshold <= 1.0001; threshold += 0.05) {
            for (String query : QUERIES) {
                Optional<String> expected = bruteForceBest(query, threshold);
                Optional<String> actual = index.findBestMatch(query, threshold, EMBEDDER)
                        .map(EntityEmbeddingIndex.GroundingCandidate::getLabel);

                assertEquals(expected, actual,
                        "query \"" + query + "\" at threshold " + String.format("%.2f", threshold));
            }
        }
    }

    @Test
    @DisplayName("the score returned is the true score, not a bound")
    void reportedScoreIsExact() {
        for (String query : QUERIES) {
            index.findBestMatch(query, 0.0, EMBEDDER).ifPresent(match -> {
                float[] q = unit(HashingEmbedder.vector(query));
                double exact = Math.max(0, Math.min(1, dot(q, unit(HashingEmbedder.vector(match.getLabel())))));
                assertEquals(exact, match.getSimilarity(), 1e-6,
                        "abandoning a dot product must not leak a partial sum into the result");
            });
        }
    }

    @Test
    @DisplayName("an exact label grounds to itself")
    void exactLabelGroundsToItself() {
        for (String label : LABELS) {
            assertEquals(Optional.of(label),
                    index.findBestMatch(label, 0.99, EMBEDDER)
                            .map(EntityEmbeddingIndex.GroundingCandidate::getLabel));
        }
    }

    @Test
    @DisplayName("top-k still comes back in descending order")
    void topKOrdered() {
        List<EntityEmbeddingIndex.GroundingCandidate> top =
                index.findNearest("Brazil", 5, EMBEDDER);

        assertEquals(5, top.size());
        assertEquals("Brazil", top.get(0).getLabel());
        for (int i = 1; i < top.size(); i++) {
            assertTrue(top.get(i - 1).getSimilarity() >= top.get(i).getSimilarity(),
                    "results must be ordered best first");
        }
    }

    @Test
    @DisplayName("asking for more than the index holds returns everything")
    void topKBeyondIndexSize() {
        assertEquals(LABELS.size(), index.findNearest("Brazil", LABELS.size() + 10, EMBEDDER).size());
    }
}
