package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.gensparql.core.model.EmbedRequest;
import org.gensparql.core.model.EmbedResponse;
import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LIVE demo of C3 dedup against a real OpenRouter free model. Gated on OPENROUTER_API_KEY,
 * so it is skipped in normal CI. Shows that dedup issues fewer REAL LLM calls; result equality
 * is NOT asserted (a live LLM is non-deterministic across runs — that guarantee is covered by
 * the record-replay tests). Call counts come from a wrapper that counts before delegating, so
 * they are exact even if the free tier returns 429s.
 */
@EnabledIfEnvironmentVariable(named = "OPENROUTER_API_KEY", matches = ".+")
public class LiveDedupOpenRouterTest {

    private static final String MODEL = "openai/gpt-oss-20b:free";
    private static final String QUERY = ("""
            PREFIX ex: <http://example.org/>
            SELECT ?s ?field ?desc WHERE {
              ?s ex:researchField ?field .
              GENOP("Describe the research field {?field} in one short sentence.",
                    ?desc, <model:openrouter:%s>)
            }
            """).formatted(MODEL);

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void reset() {
        GenSPARQLConfig.reset();
    }

    /** 6 bindings, 2 distinct field values (Physics x3, Chemistry x3). */
    private static Model model() {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        String[][] rows = {
            {"p1", "Physics"}, {"p2", "Physics"}, {"p3", "Physics"},
            {"c1", "Chemistry"}, {"c2", "Chemistry"}, {"c3", "Chemistry"}};
        for (String[] r : rows) {
            m.add(ResourceFactory.createResource(ns + r[0]),
                  ResourceFactory.createProperty(ns + "researchField"),
                  ResourceFactory.createPlainLiteral(r[1]));
        }
        return m;
    }

    private static int countRows(Model m) {
        Query q = GenSPARQLQueryFactory.create(QUERY);
        int n = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, m)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                n++;
            }
        }
        return n;
    }

    @Test
    void dedupReducesLiveLlmCalls() {
        CountingProvider counting = new CountingProvider(LLMProviderRegistry.get("openrouter"));
        LLMProviderRegistry.setDefault(counting);

        GenSPARQLConfig.setBatchDedupEnabled(false);
        counting.calls.set(0);
        long t0 = System.currentTimeMillis();
        int rowsOff = countRows(model());
        long msOff = System.currentTimeMillis() - t0;
        int callsOff = counting.calls.get();

        GenSPARQLConfig.setBatchDedupEnabled(true);
        counting.calls.set(0);
        long t1 = System.currentTimeMillis();
        int rowsOn = countRows(model());
        long msOn = System.currentTimeMillis() - t1;
        int callsOn = counting.calls.get();

        System.out.printf(
            "[LIVE-C3] model=%s%n  dedup OFF: %d LLM calls, %d rows, %d ms%n"
            + "  dedup ON : %d LLM calls, %d rows, %d ms%n",
            MODEL, callsOff, rowsOff, msOff, callsOn, rowsOn, msOn);

        // Exact even under 429s (counted before delegating): 6 bindings, 2 distinct prompts.
        assertEquals(6, callsOff, "no dedup -> one call per binding");
        assertEquals(2, callsOn, "dedup -> one call per distinct field prompt");
    }

    /** Counts generate() invocations to the wrapped provider; transparent name for routing. */
    private static final class CountingProvider implements LLMProvider {
        private final LLMProvider delegate;
        final AtomicInteger calls = new AtomicInteger();

        CountingProvider(LLMProvider delegate) {
            this.delegate = delegate;
        }

        @Override public String getName() { return delegate.getName(); }
        @Override public boolean isAvailable() { return delegate.isAvailable(); }

        @Override
        public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
            calls.incrementAndGet();
            return delegate.generate(request);
        }

        @Override public CompletableFuture<EmbedResponse> embed(EmbedRequest request) {
            return delegate.embed(request);
        }
        @Override public boolean supportsEmbedding() { return delegate.supportsEmbedding(); }
        @Override public String getDefaultModel() { return delegate.getDefaultModel(); }
        @Override public String getDefaultEmbeddingModel() { return delegate.getDefaultEmbeddingModel(); }
        @Override public void close() { delegate.close(); }
    }
}
