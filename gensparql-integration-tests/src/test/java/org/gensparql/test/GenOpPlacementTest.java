package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the engine places a GENOP, measured in LLM calls against the WC2026 KG.
 *
 * <p>Without cost-based planning the algebra generator pushes GENOP after every KG pattern.
 * That is the cheap order when the later patterns are selective, and the expensive one when a
 * later pattern fans out: 48 teams joined to their squads is 825 rows, so a GENOP that only
 * needs the team name fires 825 times instead of 48. The cost planner is what tells the two
 * cases apart, so these tests pin both.
 */
@DisplayName("GENOP placement")
public class GenOpPlacementTest {

    private static Model kg;
    private MockLLMProvider mock;

    /** GENOP needs only ?name; ?p ex:playsFor ?t afterwards multiplies rows by ~17. */
    private static final String FAN_OUT = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?name ?nick ?p WHERE {
              ?t a ex:Team ; rdfs:label ?name .
              GENOP("nickname of {?name}", (?nick), <model:mock:test>)
              ?p ex:playsFor ?t .
            }
            """;

    /** GENOP needs ?player; the later patterns cut 825 athletes down to one squad. */
    private static final String SELECTIVE = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?player WHERE {
              ?p a ex:Athlete ; rdfs:label ?player .
              GENOP("position of {?player}", (?pos), <model:mock:test>)
              ?p ex:playsFor ?t . ?t rdfs:label "Uruguay" .
              FILTER(?pos = "Midfielder")
            }
            """;

    @BeforeAll
    static void load() {
        GenSPARQL.init();
        kg = ModelFactory.createDefaultModel();
        String p = "../gensparql-example/data/worldcup2026.ttl";
        if (!new File(p).exists()) {
            p = "gensparql-example/data/worldcup2026.ttl";
        }
        RDFDataMgr.read(kg, p);
    }

    @BeforeEach
    void setUp() {
        mock = new MockLLMProvider().withDefaultResponse("Midfielder");
        LLMProviderRegistry.setDefault(mock);
        GenSPARQLConfig.reset();
        GenSPARQLConfig.setBatchingEnabled(false); // one LLM call per binding, so calls are countable
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private int llmCalls(String queryString) {
        mock.clearHistory();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(queryString), kg)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
            }
        }
        return mock.getRequestHistory().size();
    }

    /**
     * Solutions as canonical strings. Variables are emitted in name order because
     * QuerySolution.toString() lists them in binding order, which follows the execution order
     * and would report a pure reordering as a difference.
     */
    private List<String> rows(String queryString) {
        List<String> out = new ArrayList<>();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(queryString), kg)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                List<String> names = new ArrayList<>();
                sol.varNames().forEachRemaining(names::add);
                names.sort(String::compareTo);
                StringBuilder sb = new StringBuilder();
                for (String n : names) {
                    sb.append(n).append('=').append(sol.get(n)).append(' ');
                }
                out.add(sb.toString());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static int solutions(String pattern) {
        String q = "PREFIX ex: <http://example.org/>\n"
                 + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
                 + "SELECT (COUNT(*) AS ?n) WHERE { " + pattern + " }";
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), kg)) {
            return (int) qe.execSelect().next().getLiteral("n").getLong();
        }
    }

    @Test
    @DisplayName("cost planning runs the GENOP before a fan-out pattern")
    void fanOutPatternIsPlacedAfterGenOp() {
        int teams = solutions("?t a ex:Team ; rdfs:label ?name .");
        int pairs = solutions("?t a ex:Team ; rdfs:label ?name . ?p ex:playsFor ?t .");
        assertTrue(pairs > teams * 5, "the second pattern must genuinely fan out for this test to mean anything");

        GenSPARQLConfig.setCostBasedPlanningEnabled(true);
        assertEquals(teams, llmCalls(FAN_OUT),
                "GENOP needs only ?name, so it must fire once per team, not once per team-player pair");
    }

    @Test
    @DisplayName("cost planning keeps the GENOP after selective patterns")
    void selectivePatternsStayBeforeGenOp() {
        int squad = solutions("?p a ex:Athlete . ?p ex:playsFor ?t . ?t rdfs:label \"Uruguay\" .");

        GenSPARQLConfig.setCostBasedPlanningEnabled(true);
        assertEquals(squad, llmCalls(SELECTIVE),
                "selective patterns must still run first, so the GENOP fires only on that squad");
    }

    @Test
    @DisplayName("planning changes the plan, not the answers")
    void resultsAreUnchanged() {
        for (String query : List.of(FAN_OUT, SELECTIVE)) {
            GenSPARQLConfig.reset();
            GenSPARQLConfig.setBatchingEnabled(false);
            List<String> withoutPlanning = rows(query);

            GenSPARQLConfig.setCostBasedPlanningEnabled(true);
            List<String> withPlanning = rows(query);

            assertEquals(withoutPlanning, withPlanning,
                    "reordering is only legal if it is result-preserving");
            assertFalse(withoutPlanning.isEmpty(), "the query should return something to compare");
        }
    }

    @Test
    @DisplayName("without cost planning the fan-out case is still the expensive order")
    void heuristicAloneMissesTheFanOutCase() {
        // Pins the gap the planner closes. If the default heuristic ever learns this case,
        // this test should be updated rather than deleted.
        int teams = solutions("?t a ex:Team ; rdfs:label ?name .");
        assertTrue(llmCalls(FAN_OUT) > teams,
                "GENOP-last fires once per joined row; documented here so the difference is visible");
    }
}
