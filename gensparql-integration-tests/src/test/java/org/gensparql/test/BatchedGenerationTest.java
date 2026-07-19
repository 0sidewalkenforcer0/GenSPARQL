package org.gensparql.test;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the batched-generation iterator dropping remaining input when an
 * intermediate batch produces zero rows.
 */
public class BatchedGenerationTest {

    private static final String QUERY = """
            PREFIX ex: <http://example.org/>
            SELECT ?field ?tool WHERE {
              ?p ex:field ?field .
              GENOP("List one tool used in {?field}", ?tool, <model:mock:test>)
            }
            """;

    @BeforeAll
    static void initEngine() {
        GenSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private static Model modelWith(int rows) {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        for (int i = 0; i < rows; i++) {
            m.add(ResourceFactory.createResource(ns + "p" + i),
                  ResourceFactory.createProperty(ns + "field"),
                  ResourceFactory.createPlainLiteral("Field" + i));
        }
        return m;
    }

    /**
     * A batch provider whose FIRST batch call returns an empty JSON array (no rows) and whose
     * subsequent calls return one valid row. This models an intermediate batch that yields
     * nothing while input still remains.
     */
    private static final class FirstBatchEmptyProvider extends MockLLMProvider {
        final AtomicInteger calls = new AtomicInteger(0);

        @Override
        public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
            int n = calls.incrementAndGet();
            String raw = (n == 1)
                    ? "[]"
                    : "[{\"prompt_id\": 1, \"tool\": \"beaker\"}]";
            return CompletableFuture.completedFuture(GenerateResponse.success(raw, List.of()));
        }
    }

    @Test
    void emptyIntermediateBatchDoesNotDropRemainingInput() {
        FirstBatchEmptyProvider provider = new FirstBatchEmptyProvider();
        LLMProviderRegistry.setDefault(provider);

        GenSPARQLConfig.setBatchingEnabled(true);
        GenSPARQLConfig.setBatchSize(2);

        // 3 inputs, batchSize 2 => batch#1 (2 prompts) returns "[]" (empty),
        // batch#2 (1 prompt) returns one row. The row from batch#2 must survive.
        Query query = org.gensparql.parser.GenSPARQLQueryFactory.create(QUERY);
        List<String> rows = new ArrayList<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(query, modelWith(3))) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                rows.add(s.get("field") + "|" + s.get("tool"));
            }
        }

        // Pre-fix: the empty first batch terminated the iterator and this was 0.
        assertEquals(1, rows.size(),
                "row produced by the second batch must not be dropped after an empty first batch");
        assertEquals(2, provider.calls.get(), "both batches should have been executed");
    }
}
