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
    void hybridWorldCupQuery_bgpPlusGenopPlusJoin() {
        // Use-case figure query: closed-world BGP (KG: teams in a group) + open-world GENOP
        // (LLM: a star player) + join back to the KG (?p a ex:Athlete). The join keeps only
        // generated names that are real KG squad players — SPARQL alone has no star-player
        // relation, so it would return nothing.
        String kg = System.getenv().getOrDefault("WC_KG",
                "gensparql-example/data/worldcup2026.ttl");
        org.apache.jena.rdf.model.Model model = ModelFactory.createDefaultModel();
        org.apache.jena.riot.RDFDataMgr.read(model, kg);
        GenSPARQL.init();

        String group = System.getenv().getOrDefault("WC_GROUP", "Group A");
        if ("1".equals(System.getenv("WC_BGP_ONLY"))) {
            String bq = """
                    PREFIX ex: <http://example.org/>
                    PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                    SELECT ?teamName WHERE {
                      ?team a ex:Team ; rdfs:label ?teamName ; ex:inGroup ?g .
                      ?g rdfs:label "%s" .
                    }""".formatted(group);
            try (QueryExecution qe = GenSPARQL.createQueryExecution(GenSPARQLQueryFactory.create(bq), model)) {
                ResultSet rs = qe.execSelect();
                int n = 0;
                while (rs.hasNext()) { System.out.println("[bgp] " + rs.next().get("teamName")); n++; }
                System.out.println("[bgp] teams in " + group + " = " + n);
            }
            return;
        }
        // Sim-join form (cf. query9): the KG binds each team's squad players' labels to ?star,
        // GENOP generates a star name into the SAME ?star, and the engine sim-joins them
        // (approximate match on the shared variable), so near-misses like
        // "Santiago Gimenez" -> "Santiago Giménez" resolve. Closed-world constraint:
        // ex:inGroup + ex:playsFor keep only real squad members of the group's teams.
        String q = """
                PREFIX ex: <http://example.org/>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT DISTINCT ?teamName ?star WHERE {
                  ?team a ex:Team ; rdfs:label ?teamName ; ex:inGroup ?g .
                  ?g rdfs:label "%s" .
                  ?p a ex:Athlete ; ex:playsFor ?team ; rdfs:label ?star .
                  GENOP("Name one well-known player in the %s 2026 World Cup squad. Reply with just the player's full name.",
                        (?star), <model:openai:%s>)
                }
                """.formatted(group, "{?teamName}", model());

        Query query = GenSPARQLQueryFactory.create(q);
        int rows = 0;
        try (QueryExecution qe = GenSPARQL.createQueryExecution(query, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                System.out.println("[hybrid] " + s.get("teamName") + " -> " + s.get("star")
                        + "  (KG-validated)");
                rows++;
            }
        }
        System.out.println("[hybrid] validated rows = " + rows + " for " + group);
        // At least one Group-A team's generated star should be a real KG squad member.
        assertTrue(rows >= 1, "expected >=1 KG-validated star player from the hybrid query");
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
