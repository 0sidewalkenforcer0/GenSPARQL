package org.gensparql.test;

import org.apache.jena.query.QueryCancelledException;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancelling a query that is generating.
 *
 * <p>requestCancel used to do nothing, with a comment saying cancellation was not supported for
 * LLM calls. ARQ checks between bindings whether to stop, and that check never came round while a
 * call was in flight, so a timeout could not interrupt a slow model and a cancelled query went on
 * issuing calls until its input ran out.
 */
@DisplayName("cancelling a generating query")
public class GenOpCancellationTest {

    private static final String QUERY = """
            PREFIX ex: <http://example.org/>
            SELECT ?field ?tool WHERE {
              ?p ex:field ?field .
              GENOP("List one tool used in {?field}", ?tool, <model:mock:test>)
            }
            """;

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private static Model model(int rows) {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        for (int i = 0; i < rows; i++) {
            m.add(ResourceFactory.createResource(ns + "p" + i),
                  ResourceFactory.createProperty(ns + "field"),
                  ResourceFactory.createPlainLiteral("Field" + i));
        }
        return m;
    }

    /** Takes its time over every call, the way a real model does. */
    private static final class SlowProvider extends MockLLMProvider {
        final AtomicInteger started = new AtomicInteger();
        private final long millis;

        SlowProvider(long millis) {
            this.millis = millis;
        }

        @Override
        public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
            started.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return GenerateResponse.success("a tool", List.of());
            });
        }
    }

    @Test
    @DisplayName("a timeout interrupts a call in flight")
    void timeoutInterruptsCall() {
        SlowProvider provider = new SlowProvider(30_000);
        LLMProviderRegistry.setDefault(provider);
        GenSPARQLConfig.setBatchingEnabled(false);

        long start = System.currentTimeMillis();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(QUERY), model(5))) {
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                qe.abort();
            }).start();

            assertThrows(QueryCancelledException.class, () -> {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    rs.next();
                }
            });
        }
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 10_000,
                "must not sit out the 30s call; took " + elapsed + "ms");
    }

    @Test
    @DisplayName("no further calls are started after a cancel")
    void noFurtherCallsAfterCancel() throws Exception {
        SlowProvider provider = new SlowProvider(200);
        LLMProviderRegistry.setDefault(provider);
        GenSPARQLConfig.setBatchingEnabled(false);

        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(QUERY), model(50))) {
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                qe.abort();
            }).start();

            try {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    rs.next();
                }
            } catch (QueryCancelledException expected) {
                // the point of the test
            }
        }

        int atCancel = provider.started.get();
        Thread.sleep(1_000);
        assertEquals(atCancel, provider.started.get(),
                "the query kept generating after it was cancelled");
        assertTrue(atCancel < 50, "it should not have got through all 50 inputs");
    }

    @Test
    @DisplayName("an uncancelled query still completes")
    void normalRunUnaffected() {
        SlowProvider provider = new SlowProvider(0);
        LLMProviderRegistry.setDefault(provider);
        GenSPARQLConfig.setBatchingEnabled(false);

        int rows = 0;
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(QUERY), model(3))) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                rows++;
            }
        }
        assertEquals(3, rows);
    }
}
