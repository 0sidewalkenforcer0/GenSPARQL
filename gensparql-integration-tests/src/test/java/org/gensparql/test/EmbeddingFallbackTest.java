package org.gensparql.test;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.util.Context;
import org.gensparql.core.model.EmbedRequest;
import org.gensparql.core.model.EmbedResponse;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.engine.GenSPARQLConstants;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.engine.similarity.EmbeddingSimText;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 2.3 of the paper: the engine falls back to the Jaccard similarity if the embedding
 * model fails. The fallback applies to the rest of the query, so one threshold is not applied to
 * cosine scores for some pairs and Jaccard scores for others, and the engine reports it.
 */
class EmbeddingFallbackTest {

    private static final JaccardSimText JACCARD = new JaccardSimText();

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    /** Embeds like the mock for the first {@code working} requests, then fails. */
    private static final class FailingAfter extends MockLLMProvider {
        final AtomicInteger calls = new AtomicInteger();
        private final int working;

        FailingAfter(int working) {
            this.working = working;
        }

        @Override
        public CompletableFuture<EmbedResponse> embed(EmbedRequest request) {
            if (calls.incrementAndGet() > working) {
                return CompletableFuture.completedFuture(EmbedResponse.error("model 'bge-large' not found"));
            }
            return super.embed(request);
        }
    }

    /** Strings that no other test has embedded, so that the shared embedding cache misses. */
    private static String fresh(String words) {
        return words + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static EmbeddingSimText similarity(MockLLMProvider provider) {
        return new EmbeddingSimText(new JaccardSimText(), provider, ModelSpec.fromURI("model:mock:test"));
    }

    @Test
    void afterTheFirstFailureEveryPairUsesJaccardWithoutCallingTheModel() {
        FailingAfter provider = new FailingAfter(2);
        EmbeddingSimText sim = similarity(provider);

        String a1 = fresh("Korea Republic"), b1 = fresh("South Korea");
        sim.similarity(a1, b1);                       // two embeddings, both served
        assertFalse(sim.usedFallback());

        String a2 = fresh("United States"), b2 = fresh("USA");
        assertEquals(JACCARD.similarity(a2, b2), sim.similarity(a2, b2), 1e-12);
        assertTrue(sim.usedFallback());
        assertTrue(sim.fallbackReason().contains("not found"), sim.fallbackReason());
        int callsAtFailure = provider.calls.get();

        String a3 = fresh("Sveriges Riksbank Prize"), b3 = fresh("Economic Sciences");
        assertEquals(JACCARD.similarity(a3, b3), sim.similarity(a3, b3), 1e-12);
        assertEquals(callsAtFailure, provider.calls.get(), "no embedding request after the fallback");
    }

    @Test
    void aFailedWarmUpSwitchesTheQueryBeforeTheFirstPair() {
        FailingAfter provider = new FailingAfter(0);
        EmbeddingSimText sim = similarity(provider);
        sim.warmUpConfigured(List.of(fresh("Physics"), fresh("Chemistry")));
        assertTrue(sim.usedFallback());
        String a = fresh("Nobel Prize in Physics"), b = fresh("Physics");
        assertEquals(JACCARD.similarity(a, b), sim.similarity(a, b), 1e-12);
    }

    @Test
    void theEngineResetsTheFallbackWhenAQueryStarts() {
        EmbeddingSimText sim = similarity(new FailingAfter(0));
        sim.similarity(fresh("Brazil"), fresh("Argentina"));
        assertTrue(sim.usedFallback());

        Context cxt = ARQ.getContext().copy();
        cxt.set(GenSPARQLConstants.SIM_TEXT, sim);
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create("SELECT * WHERE { }"),
                DatasetFactory.wrap(ModelFactory.createDefaultModel()), cxt)) {
            qe.execSelect().forEachRemaining(row -> { });
        }
        assertFalse(sim.usedFallback(), "a new query starts with the embedding similarity");
        assertEquals(null, sim.fallbackReason());
    }
}
