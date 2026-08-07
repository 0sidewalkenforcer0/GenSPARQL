package org.gensparql.test;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

/**
 * X4 — efficiency: does pushing the KG's selective predicate before GENOP minimize LLM calls?
 *
 * Same query semantics ("midfielders of one team"), two authorings:
 *   good: selective BGP first  -> GENOP should fire only on that team's squad
 *   bad : GENOP first          -> without reordering, GENOP fires on ALL athletes
 * We count LLM invocations with a Mock provider (deterministic, no server). Both spellings must
 * cost the same: the engine places the GENOP after the selective patterns either way. A gap here
 * means placement has started depending on how the query was written.
 *
 * See GenOpPlacementTest for the case this shape cannot show, where a later pattern fans out
 * instead of filtering and the cheapest plan runs the GENOP first.
 */
public class CostReorderTest {

    private static Model kg;
    private MockLLMProvider mock;

    @BeforeAll
    static void load() {
        GenSPARQL.init();
        kg = ModelFactory.createDefaultModel();
        String p = System.getenv().getOrDefault("WC_KG",
                "../gensparql-example/data/worldcup2026.ttl");
        if (!new File(p).exists()) p = "gensparql-example/data/worldcup2026.ttl";
        RDFDataMgr.read(kg, p);
    }

    @BeforeEach
    void setUp() {
        mock = new MockLLMProvider().withDefaultResponse("Midfielder");
        LLMProviderRegistry.setDefault(mock);
        GenSPARQLConfig.reset();
        GenSPARQLConfig.setBatchingEnabled(false);   // count one call per binding
    }

    @AfterEach
    void tearDown() { GenSPARQLConfig.reset(); }

    private int runCountCalls(String q) {
        mock.clearHistory();
        Query query = GenSPARQLQueryFactory.create(q);
        try (QueryExecution qe = GenSPARQL.createQueryExecution(query, kg)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) rs.next();
        }
        return mock.getRequestHistory().size();
    }

    @Test
    void selectivePredicatePlacementVsLlmCallCount() {
        String good = """
                PREFIX ex: <http://example.org/>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT ?player WHERE {
                  ?t a ex:Team ; rdfs:label "Uruguay" .
                  ?p ex:playsFor ?t ; rdfs:label ?player .
                  GENOP("position of {?player}", (?pos), <model:mock:test>)
                  FILTER(?pos = "Midfielder")
                }
                """;
        String bad = """
                PREFIX ex: <http://example.org/>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT ?player WHERE {
                  ?p a ex:Athlete ; rdfs:label ?player .
                  GENOP("position of {?player}", (?pos), <model:mock:test>)
                  ?p ex:playsFor ?t . ?t rdfs:label "Uruguay" .
                  FILTER(?pos = "Midfielder")
                }
                """;
        int callsGood = runCountCalls(good);
        int callsBad = runCountCalls(bad);

        int squad = 26; // Uruguay's squad in worldcup2026.ttl
        assertEquals(squad, callsGood,
                "the selective-first authoring must fire the GENOP only on that squad");
        assertEquals(squad, callsBad,
                "authoring order must not change cost here: the selective patterns still run "
                + "before the GENOP, so both spellings cost the same");
    }
}
