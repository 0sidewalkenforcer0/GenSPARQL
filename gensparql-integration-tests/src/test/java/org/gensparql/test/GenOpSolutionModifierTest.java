package org.gensparql.test;

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
 * Solution modifiers over a pattern containing a GENOP.
 *
 * <p>A query with a GENOP is compiled by GenSPARQL rather than by Jena, so every modifier has
 * to be applied on that path too. Group and aggregation were missing from it, which made
 * GROUP BY not group and aggregate variables come back unbound, with no error. SELECT * was
 * expanded from the variables Jena can see in the pattern, which excluded the generated ones.
 */
@DisplayName("solution modifiers with GENOP")
public class GenOpSolutionModifierTest {

    private static final String TTL = """
            @prefix ex: <http://example.org/> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            ex:a a ex:T ; rdfs:label "A" .
            ex:b a ex:T ; rdfs:label "B" .
            """;

    private static final String PREFIXES =
            "PREFIX ex: <http://example.org/>\n"
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n";

    /** Constant response, so every row carries the same generated value. */
    private static final String GENOP = "GENOP(\"say {?l}\", (?g), <model:mock:t>)";
    private static final String TWO_ROWS = "?s a ex:T ; rdfs:label ?l . " + GENOP;

    private static Model kg;

    @BeforeAll
    static void load() {
        GenSPARQL.init();
        kg = ModelFactory.createDefaultModel();
        RDFDataMgr.read(kg, new StringReader(TTL), null, Lang.TURTLE);
    }

    @BeforeEach
    void setUp() {
        LLMProviderRegistry.setDefault(new MockLLMProvider().withDefaultResponse("GEN"));
        GenSPARQLConfig.reset();
        GenSPARQLConfig.setBatchingEnabled(false);
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private List<QuerySolution> solutions(String body) {
        List<QuerySolution> out = new ArrayList<>();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + body), kg)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                out.add(rs.next());
            }
        }
        return out;
    }

    @Test
    @DisplayName("SELECT * projects the generated variable")
    void selectStarIncludesGeneratedVar() {
        List<QuerySolution> rows = solutions("SELECT * WHERE { " + TWO_ROWS + " }");

        assertEquals(2, rows.size());
        for (QuerySolution row : rows) {
            assertNotNull(row.get("s"), "?s comes from the pattern");
            assertNotNull(row.get("l"), "?l comes from the pattern");
            assertNotNull(row.get("g"),
                    "?g is bound by the GENOP and must be part of SELECT *");
            assertEquals("GEN", row.getLiteral("g").getString());
        }
    }

    @Test
    @DisplayName("GROUP BY groups on the generated variable")
    void groupByGeneratedVar() {
        List<QuerySolution> rows =
                solutions("SELECT ?g (COUNT(*) AS ?n) WHERE { " + TWO_ROWS + " } GROUP BY ?g");

        assertEquals(1, rows.size(), "both rows share one generated value, so one group");
        assertEquals("GEN", rows.get(0).getLiteral("g").getString());
        assertEquals(2, rows.get(0).getLiteral("n").getInt());
    }

    @Test
    @DisplayName("an aggregate without GROUP BY collapses to one row")
    void aggregateWithoutGroupBy() {
        List<QuerySolution> rows = solutions("SELECT (COUNT(*) AS ?n) WHERE { " + TWO_ROWS + " }");

        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).getLiteral("n").getInt());
    }

    @Test
    @DisplayName("GROUP BY a pattern variable keeps the groups apart")
    void groupByPatternVar() {
        List<QuerySolution> rows =
                solutions("SELECT ?l (COUNT(*) AS ?n) WHERE { " + TWO_ROWS + " } GROUP BY ?l");

        assertEquals(2, rows.size(), "two distinct labels, two groups");
        for (QuerySolution row : rows) {
            assertEquals(1, row.getLiteral("n").getInt());
        }
    }

    @Test
    @DisplayName("HAVING filters grouped rows")
    void having() {
        assertEquals(1, solutions("SELECT ?g (COUNT(*) AS ?n) WHERE { " + TWO_ROWS
                + " } GROUP BY ?g HAVING(COUNT(*) > 1)").size());
        assertEquals(0, solutions("SELECT ?g (COUNT(*) AS ?n) WHERE { " + TWO_ROWS
                + " } GROUP BY ?g HAVING(COUNT(*) > 5)").size());
    }

    @Test
    @DisplayName("ORDER BY can sort on a variable that is not selected")
    void orderByUnselectedVar() {
        List<QuerySolution> rows =
                solutions("SELECT ?s WHERE { " + TWO_ROWS + " } ORDER BY DESC(?l)");

        assertEquals(2, rows.size());
        assertNull(rows.get(0).get("l"), "?l is sorted on but not selected");
        assertTrue(rows.get(0).getResource("s").getURI().endsWith("/b"),
                "DESC(?l) puts \"B\" first, which requires ordering before projection");
    }

    @Test
    @DisplayName("DISTINCT deduplicates the selected columns")
    void distinctAfterProjection() {
        assertEquals(1, solutions("SELECT DISTINCT ?g WHERE { " + TWO_ROWS + " }").size(),
                "two rows share one generated value, so DISTINCT leaves one");
        assertEquals(2, solutions("SELECT DISTINCT ?s ?g WHERE { " + TWO_ROWS + " }").size(),
                "adding a distinguishing column brings both rows back");
    }

    @Test
    @DisplayName("LIMIT and OFFSET apply last")
    void limitOffset() {
        assertEquals(1, solutions("SELECT * WHERE { " + TWO_ROWS + " } LIMIT 1").size());
        assertEquals(1, solutions("SELECT * WHERE { " + TWO_ROWS + " } LIMIT 5 OFFSET 1").size());
        assertEquals(0, solutions("SELECT * WHERE { " + TWO_ROWS + " } OFFSET 2").size());
    }
}
