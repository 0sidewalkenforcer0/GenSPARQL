package org.gensparql.test;

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
 * The batched path: one LLM call carries many prompts.
 *
 * <p>The response parser used to fail a batch as soon as one prompt went unanswered, and the
 * caller then discarded the batch, so a single omission cost every other binding in it. Batching
 * also sent duplicate prompts, which meant turning it on gave up the cross-binding deduplication
 * the non-batched path does.
 */
@DisplayName("batched generation")
public class BatchedPathTest {

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

    /** {@code rows} bindings over {@code distinct} distinct field values. */
    private static Model model(int rows, int distinct) {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        for (int i = 0; i < rows; i++) {
            m.add(ResourceFactory.createResource(ns + "p" + i),
                  ResourceFactory.createProperty(ns + "field"),
                  ResourceFactory.createPlainLiteral("Field" + (i % distinct)));
        }
        return m;
    }

    /** Answers the first {@code answered} prompt ids of each batch and ignores the rest. */
    private static final class AnswersSome extends MockLLMProvider {
        final AtomicInteger batches = new AtomicInteger();
        private final int answered;

        AnswersSome(int answered) {
            this.answered = answered;
        }

        @Override
        public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
            batches.incrementAndGet();
            StringBuilder sb = new StringBuilder("[");
            for (int id = 1; id <= answered; id++) {
                if (id > 1) {
                    sb.append(',');
                }
                sb.append("{\"prompt_id\": ").append(id).append(", \"tool\": \"t").append(id).append("\"}");
            }
            return CompletableFuture.completedFuture(
                    GenerateResponse.success(sb.append(']').toString(), List.of()));
        }
    }

    /** Returns prose where a JSON array was asked for. */
    private static final class AnswersNonsense extends MockLLMProvider {
        final AtomicInteger batches = new AtomicInteger();

        @Override
        public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
            batches.incrementAndGet();
            return CompletableFuture.completedFuture(
                    GenerateResponse.success("sorry, I cannot help with that", List.of()));
        }
    }

    private static int rowCount(Model kg) {
        int n = 0;
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(QUERY), kg)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("an unanswered prompt costs its own row and no others")
    void partialBatchKeepsTheAnswers() {
        AnswersSome provider = new AnswersSome(2);
        LLMProviderRegistry.setDefault(provider);
        GenSPARQLConfig.setBatchingEnabled(true);
        GenSPARQLConfig.setBatchSize(4);

        assertEquals(2, rowCount(model(4, 4)),
                "two prompts were answered, so two rows; the other two are dropped, not all four");
        assertEquals(1, provider.batches.get());
    }

    @Test
    @DisplayName("a fully answered batch produces every row")
    void fullBatch() {
        AnswersSome provider = new AnswersSome(4);
        LLMProviderRegistry.setDefault(provider);
        GenSPARQLConfig.setBatchingEnabled(true);
        GenSPARQLConfig.setBatchSize(4);

        assertEquals(4, rowCount(model(4, 4)));
    }

    @Test
    @DisplayName("a response that cannot be read drops that batch and no more")
    void unreadableResponse() {
        AnswersNonsense provider = new AnswersNonsense();
        LLMProviderRegistry.setDefault(provider);
        GenSPARQLConfig.setBatchingEnabled(true);
        GenSPARQLConfig.setBatchSize(2);

        assertEquals(0, rowCount(model(4, 4)),
                "nothing usable came back, and a GENOP that generated nothing produces no row");
        assertEquals(2, provider.batches.get(),
                "the second batch is still attempted: one bad response must not end the query");
    }

    @Test
    @DisplayName("duplicate prompts in a batch are sent once and fanned back out")
    void batchDeduplicatesPrompts() {
        // Four bindings over two distinct field values, so two distinct prompts. Answering ids
        // 1 and 2 is enough for all four rows only if the batch was deduplicated; had it sent
        // four prompts, ids 3 and 4 would have gone unanswered and two rows would be missing.
        AnswersSome provider = new AnswersSome(2);
        LLMProviderRegistry.setDefault(provider);
        GenSPARQLConfig.setBatchingEnabled(true);
        GenSPARQLConfig.setBatchSize(4);

        assertEquals(4, rowCount(model(4, 2)),
                "each distinct prompt's answer applies to every binding that asked for it");
        assertEquals(1, provider.batches.get());
    }
}
