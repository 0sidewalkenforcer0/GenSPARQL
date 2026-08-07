package org.gensparql.test;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.core.exception.ParseException;
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
 * What a GENOP produces when it generates nothing, and what happens to a mistyped placeholder.
 *
 * <p>Both used to end in an answer that looked like an ordinary one. A GENOP whose call came back
 * empty passed the input row through with the generated column missing, which is OPTIONAL's
 * behaviour rather than its own. A placeholder naming a variable the query never binds parsed
 * happily and then matched nothing, so a typo and a genuinely empty result were indistinguishable.
 */
@DisplayName("GENOP with nothing to bind")
public class GenOpEmptyOutputTest {

    private static final String TTL = """
            @prefix ex: <http://example.org/> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            ex:a a ex:T ; rdfs:label "A" .
            ex:b a ex:T ; rdfs:label "B" .
            """;

    private static final String PREFIXES =
            "PREFIX ex: <http://example.org/>\n"
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n";

    private static Model kg;
    private MockLLMProvider mock;

    @BeforeAll
    static void load() {
        GenSPARQL.init();
        kg = ModelFactory.createDefaultModel();
        RDFDataMgr.read(kg, new StringReader(TTL), null, Lang.TURTLE);
    }

    @BeforeEach
    void setUp() {
        GenSPARQLConfig.reset();
        GenSPARQLConfig.setBatchingEnabled(false);
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private List<QuerySolution> run(String response, String body) {
        mock = new MockLLMProvider().withDefaultResponse(response);
        LLMProviderRegistry.setDefault(mock);
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

    private static final String GENOP = "GENOP(\"say {?l}\", (?g), <model:mock:t>)";
    private static final String QUERY =
            "SELECT * WHERE { ?s a ex:T ; rdfs:label ?l . " + GENOP + " }";

    @Test
    @DisplayName("a generated value produces a row")
    void generatedValueProducesRow() {
        List<QuerySolution> rows = run("GEN", QUERY);

        assertEquals(2, rows.size());
        for (QuerySolution row : rows) {
            assertEquals("GEN", row.getLiteral("g").getString());
        }
    }

    @Test
    @DisplayName("nothing generated produces no row, rather than a row missing its column")
    void nothingGeneratedProducesNoRow() {
        assertTrue(run("", QUERY).isEmpty(),
                "the input rows match, but the GENOP binds nothing, so there is no result row");
    }

    @Test
    @DisplayName("the call still happens; it is the row that is dropped")
    void theCallIsStillMade() {
        run("", QUERY);
        assertEquals(2, mock.getRequestHistory().size(),
                "both input bindings are still generated for; only the empty results are dropped");
    }

    @Test
    @DisplayName("a placeholder naming a variable the query never binds is rejected")
    void unknownPlaceholderRejected() {
        ParseException e = assertThrows(ParseException.class,
                () -> GenSPARQLQueryFactory.create(PREFIXES
                        + "SELECT * WHERE { ?s a ex:T ; rdfs:label ?l . "
                        + "GENOP(\"say {?ll}\", (?g), <model:mock:t>) }"));

        assertTrue(e.getMessage().contains("?ll"), "the message must name the offender: " + e.getMessage());
        assertTrue(e.getMessage().contains("l"), "and list what the query does bind: " + e.getMessage());
    }

    @Test
    @DisplayName("a variable bound only inside OPTIONAL is accepted")
    void optionalBoundVariableAccepted() {
        // It may or may not be bound at run time, which is the query author's business. The
        // check only rejects a variable the query cannot ever bind.
        List<QuerySolution> rows = run("GEN",
                "SELECT * WHERE { ?s a ex:T . OPTIONAL { ?s rdfs:label ?l } "
                + "GENOP(\"say {?l}\", (?g), <model:mock:t>) }");

        assertEquals(2, rows.size());
    }

    @Test
    @DisplayName("a variable bound by an earlier GENOP is accepted")
    void chainedGenOpVariableAccepted() {
        List<QuerySolution> rows = run("GEN",
                "SELECT * WHERE { ?s rdfs:label ?l . " + GENOP
                + " GENOP(\"echo {?g}\", (?g2), <model:mock:t>) }");

        assertEquals(2, rows.size());
        for (QuerySolution row : rows) {
            assertNotNull(row.get("g2"), "the second GENOP reads what the first bound");
        }
    }
}
