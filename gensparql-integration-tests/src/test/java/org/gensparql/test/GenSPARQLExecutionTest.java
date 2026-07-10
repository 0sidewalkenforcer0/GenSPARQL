package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GenSPARQL query execution using GENOP syntax.
 */
public class GenSPARQLExecutionTest {

    private static Model testModel;
    private MockLLMProvider mockProvider;

    @BeforeAll
    static void setUpClass() {
        // Initialize GenSPARQL
        GenSPARQL.init();

        // Create test model
        testModel = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";

        // Add test data
        testModel.add(
            ResourceFactory.createResource(ns + "person1"),
            ResourceFactory.createProperty(ns + "name"),
            ResourceFactory.createPlainLiteral("Alice")
        );
        testModel.add(
            ResourceFactory.createResource(ns + "person2"),
            ResourceFactory.createProperty(ns + "name"),
            ResourceFactory.createPlainLiteral("Bob")
        );
        testModel.add(
            ResourceFactory.createResource(ns + "doc1"),
            ResourceFactory.createProperty(ns + "content"),
            ResourceFactory.createPlainLiteral("This is a test document about AI.")
        );
    }

    @BeforeEach
    void setUp() {
        // Set up mock provider for each test
        mockProvider = new MockLLMProvider();
        LLMProviderRegistry.setDefault(mockProvider);
    }

    @Test
    void testSimpleGenOpExecution() {
        // Configure mock response
        mockProvider.withResponse("Alice", "アリス");
        mockProvider.withResponse("Bob", "ボブ");

        String queryString = """
            PREFIX ex: <http://example.org/>
            SELECT ?name ?translation WHERE {
              ?person ex:name ?name .
              GENOP("Translate '{?name}' to Japanese", ?translation, <model:mock:test>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, testModel)) {
            ResultSet results = qexec.execSelect();

            int count = 0;
            while (results.hasNext()) {
                QuerySolution soln = results.next();
                assertNotNull(soln.get("name"));
                assertNotNull(soln.get("translation"));
                count++;
            }

            assertEquals(2, count, "Should have 2 results");
        }
    }

    @Test
    void testBaseModeExecution() {
        mockProvider.withDefaultResponse("The sky is blue because of Rayleigh scattering.");

        String queryString = """
            SELECT ?fact WHERE {
              GENOP("Tell me a random science fact", ?fact, <model:mock:test>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, testModel)) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext(), "Should have at least one result");
            QuerySolution soln = results.next();
            assertNotNull(soln.get("fact"));
            assertTrue(soln.get("fact").toString().contains("Rayleigh"));
        }
    }

    @Test
    void testCombinedWithFilter() {
        mockProvider.withResponse("AI", "artificial_intelligence");
        mockProvider.withDefaultResponse("other_topic");

        String queryString = """
            PREFIX ex: <http://example.org/>
            SELECT ?content ?topic WHERE {
              ?doc ex:content ?content .
              GENOP("Extract main topic from: {?content}", ?topic, <model:mock:test>)
              FILTER(CONTAINS(?content, "AI"))
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, testModel)) {
            ResultSet results = qexec.execSelect();

            assertTrue(results.hasNext());
            QuerySolution soln = results.next();
            assertNotNull(soln.get("topic"));
        }
    }

    @Test
    void testMockProviderRequestHistory() {
        mockProvider.withDefaultResponse("test response");

        String queryString = """
            PREFIX ex: <http://example.org/>
            SELECT ?name ?result WHERE {
              ?person ex:name ?name .
              GENOP("Process: {?name}", ?result, <model:mock:test>)
            }
            """;

        Query query = GenSPARQLQueryFactory.create(queryString);

        try (QueryExecution qexec = QueryExecutionFactory.create(query, testModel)) {
            ResultSet results = qexec.execSelect();
            ResultSetFormatter.consume(results);
        }

        // Verify requests were made
        assertFalse(mockProvider.getRequestHistory().isEmpty(),
                "Should have recorded requests");
    }
}
