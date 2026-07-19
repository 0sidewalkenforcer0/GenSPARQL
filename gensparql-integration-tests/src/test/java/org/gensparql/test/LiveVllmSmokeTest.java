package org.gensparql.test;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live end-to-end smoke test against a local OpenAI-compatible server (vLLM).
 *
 * Enabled only when OPENAI_BASE_URL is set, so it never runs in normal/offline CI.
 * Run it with, e.g.:
 *   OPENAI_API_KEY=dummy \
 *   OPENAI_BASE_URL=http://<node>:8000/v1 \
 *   GENSPARQL_TEST_MODEL=Qwen/Qwen3-8B \
 *   mvn -pl gensparql-integration-tests test -Dtest=LiveVllmSmokeTest
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_BASE_URL", matches = ".+")
public class LiveVllmSmokeTest {

    private static String model() {
        String m = System.getenv("GENSPARQL_TEST_MODEL");
        return (m != null && !m.isEmpty()) ? m : "Qwen/Qwen3-8B";
    }

    private static Model scientistModel() {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        for (String name : new String[]{"Albert Einstein", "Marie Curie"}) {
            m.add(ResourceFactory.createResource(ns + name.replace(' ', '_')),
                  ResourceFactory.createProperty(ns + "name"),
                  ResourceFactory.createPlainLiteral(name));
        }
        return m;
    }

    @Test
    void contextModeGenopReturnsDescriptions() {
        GenSPARQL.init();

        String q = """
                PREFIX ex: <http://example.org/>
                SELECT ?name ?desc WHERE {
                  ?s ex:name ?name .
                  GENOP("Write a concise one-sentence description of the scientist {?name}.",
                        (?desc),
                        <model:openai:%s>)
                }
                """.formatted(model());

        Query query = GenSPARQLQueryFactory.create(q);
        int rows = 0;
        try (QueryExecution qe = GenSPARQL.createQueryExecution(query, scientistModel())) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                String name = s.get("name").toString();
                String desc = s.get("desc") != null ? s.get("desc").toString() : null;
                System.out.println("[live] " + name + " -> " + desc);
                assertNotNull(desc, "description must be bound for " + name);
                assertFalse(desc.isBlank(), "description must be non-empty for " + name);
                rows++;
            }
        }
        assertTrue(rows >= 1, "expected at least one description row from the live model");
    }
}
