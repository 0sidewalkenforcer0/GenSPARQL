package org.gensparql.test;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GENOP composed with the SPARQL operators that nest a pattern.
 *
 * <p>Each of these used to fail, and all but one failed silently. A GENOP inside GRAPH, a
 * sub-select, SERVICE or MINUS was not recognised as a GENOP at all, so the query was handed to
 * Jena's compiler, which rejects the operator. VALUES parsed and then discarded its rows. And
 * where the enclosing operator was one ARQ evaluated itself, ARQ evaluated the right side of the
 * join against the root binding, leaving the prompt's variable unbound, so every row was skipped
 * and the query returned nothing having issued no calls.
 *
 * <p>The call count is asserted alongside the rows: a GENOP that never ran is the failure these
 * cases had, and row counts alone would not distinguish it from one that ran and matched nothing.
 */
@DisplayName("GENOP composition")
public class GenOpCompositionTest {

    private static final String TTL = """
            @prefix ex: <http://example.org/> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            ex:a a ex:T ; rdfs:label "A" .
            ex:b a ex:T ; rdfs:label "B" .
            ex:c a ex:U ; rdfs:label "C" .
            """;

    private static final String NAMED_GRAPH = "http://example.org/g1";

    private static final String PREFIXES =
            "PREFIX ex: <http://example.org/>\n"
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n";

    private static final String GENOP = "GENOP(\"say {?l}\", (?g), <model:mock:t>)";

    private static Dataset dataset;
    private MockLLMProvider mock;

    @BeforeAll
    static void load() {
        GenSPARQL.init();
        dataset = DatasetFactory.create(parse());
        dataset.addNamedModel(NAMED_GRAPH, parse());
    }

    private static Model parse() {
        Model m = ModelFactory.createDefaultModel();
        RDFDataMgr.read(m, new StringReader(TTL), null, Lang.TURTLE);
        return m;
    }

    @BeforeEach
    void setUp() {
        mock = new MockLLMProvider().withDefaultResponse("GEN");
        LLMProviderRegistry.setDefault(mock);
        GenSPARQLConfig.reset();
        GenSPARQLConfig.setBatchingEnabled(false);
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private List<QuerySolution> solutions(String body) {
        mock.clearHistory();
        List<QuerySolution> out = new ArrayList<>();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + body), dataset)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                out.add(rs.next());
            }
        }
        return out;
    }

    private int llmCalls() {
        return mock.getRequestHistory().size();
    }

    private static void allGenerated(List<QuerySolution> rows) {
        for (QuerySolution row : rows) {
            assertNotNull(row.get("g"), "every row must carry the generated value");
            assertEquals("GEN", row.getLiteral("g").getString());
        }
    }

    @Test
    @DisplayName("inside GRAPH, named explicitly")
    void insideNamedGraph() {
        List<QuerySolution> rows = solutions(
                "SELECT ?s ?g WHERE { GRAPH <" + NAMED_GRAPH + "> { ?s a ex:T ; rdfs:label ?l . "
                + GENOP + " } }");

        assertEquals(2, rows.size());
        assertEquals(2, llmCalls(), "the GENOP must run inside the graph, once per row");
        allGenerated(rows);
    }

    @Test
    @DisplayName("inside GRAPH with a variable graph name")
    void insideGraphVariable() {
        List<QuerySolution> rows = solutions(
                "SELECT ?gr ?s ?g WHERE { GRAPH ?gr { ?s a ex:T ; rdfs:label ?l . " + GENOP + " } }");

        assertEquals(2, rows.size());
        assertEquals(2, llmCalls());
        allGenerated(rows);
        for (QuerySolution row : rows) {
            assertEquals(NAMED_GRAPH, row.getResource("gr").getURI());
        }
    }

    @Test
    @DisplayName("fed by VALUES")
    void fedByValues() {
        List<QuerySolution> rows =
                solutions("SELECT * WHERE { VALUES ?l { \"A\" \"B\" } " + GENOP + " }");

        assertEquals(2, rows.size(), "VALUES must contribute its rows, not an empty table");
        assertEquals(2, llmCalls());
        allGenerated(rows);
    }

    @Test
    @DisplayName("fed by a multi-variable VALUES")
    void fedByMultiVarValues() {
        List<QuerySolution> rows = solutions(
                "SELECT * WHERE { VALUES (?l ?k) { (\"A\" 1) (\"B\" 2) } " + GENOP + " }");

        assertEquals(2, rows.size());
        allGenerated(rows);
        for (QuerySolution row : rows) {
            assertNotNull(row.get("k"), "the second VALUES column must be bound too");
        }
    }

    @Test
    @DisplayName("VALUES with UNDEF leaves that variable unbound")
    void valuesWithUndef() {
        List<QuerySolution> rows = solutions(
                "SELECT * WHERE { VALUES (?l ?k) { (\"A\" UNDEF) (\"B\" 2) } " + GENOP + " }");

        assertEquals(2, rows.size());
        long unbound = rows.stream().filter(r -> r.get("k") == null).count();
        assertEquals(1, unbound, "UNDEF must leave ?k unbound in exactly one row");
    }

    @Test
    @DisplayName("inside a sub-select")
    void insideSubSelect() {
        List<QuerySolution> rows = solutions(
                "SELECT * WHERE { { SELECT ?g WHERE { ?s a ex:T ; rdfs:label ?l . " + GENOP + " } } }");

        assertEquals(2, rows.size(), "a sub-select projecting only the generated variable still runs");
        assertEquals(2, llmCalls());
        allGenerated(rows);
    }

    @Test
    @DisplayName("inside a sub-select with its own LIMIT")
    void insideSubSelectWithLimit() {
        List<QuerySolution> rows = solutions(
                "SELECT * WHERE { { SELECT ?g ?l WHERE { ?s a ex:T ; rdfs:label ?l . " + GENOP
                + " } LIMIT 1 } }");

        assertEquals(1, rows.size(), "the sub-select's own LIMIT applies");
        allGenerated(rows);
    }

    @Test
    @DisplayName("inside a sub-select that aggregates")
    void insideAggregatingSubSelect() {
        List<QuerySolution> rows = solutions(
                "SELECT * WHERE { { SELECT (COUNT(*) AS ?n) WHERE { ?s a ex:T ; rdfs:label ?l . "
                + GENOP + " } } }");

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).getLiteral("n").getInt(),
                "the aggregate must count the rows the GENOP produced");
        assertEquals(2, llmCalls());
    }

    @Test
    @DisplayName("a sub-select projecting one variable is not a special case")
    void oneVariableSubSelectRunsToo() {
        // ARQ's optimizer prunes patterns whose variables nothing else needs, and it reads a
        // GENOP's variables off an effectiveOp that reports none. The pattern binding ?l was
        // therefore dropped whenever ?l was not projected, and adding ?l to the projection
        // "fixed" the query, which is not a distinction the semantics should draw.
        List<QuerySolution> oneVar = solutions(
                "SELECT ?g WHERE { { SELECT ?g WHERE { ?s a ex:T ; rdfs:label ?l . " + GENOP + " } } }");
        int callsOneVar = llmCalls();

        List<QuerySolution> twoVars = solutions(
                "SELECT * WHERE { { SELECT ?g ?l WHERE { ?s a ex:T ; rdfs:label ?l . " + GENOP + " } } }");

        assertEquals(twoVars.size(), oneVar.size(),
                "projecting fewer variables must not change how many rows there are");
        assertEquals(llmCalls(), callsOneVar,
                "nor how many times the GENOP runs");
        assertEquals(2, callsOneVar);
    }
}
