package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A GENOP inside OPTIONAL reads variables of the required part and is evaluated once per
 * solution of that part. A solution without a generated value stays in the answer.
 */
class GenOpOptionalTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private static Model squad() {
        Model m = ModelFactory.createDefaultModel();
        Resource team = m.createResource("http://www.wikidata.org/entity/Q79800");
        m.add(team, RDF.type, m.createResource("http://example.org/Team"));
        m.add(team, RDFS.label, "Argentina");
        for (String[] p : new String[][]{{"Q615", "Lionel Messi"}, {"Q51115574", "Cristian Romero"}}) {
            Resource player = m.createResource("http://www.wikidata.org/entity/" + p[0]);
            m.add(player, m.createProperty("http://example.org/playsFor"), team);
            m.add(player, RDFS.label, p[1]);
        }
        return m;
    }

    /** Maps each player name to the generated position, or to null when the row has none. */
    private static Map<String, String> positions(MockLLMProvider llm, String where) {
        LLMProviderRegistry.setDefault(llm);
        String query = PREFIXES + "SELECT ?n ?pos WHERE { ?p ex:playsFor ?t ; rdfs:label ?n . " + where + " }";
        Map<String, String> out = new HashMap<>();
        try (QueryExecution qe = QueryExecutionFactory.create(GenSPARQLQueryFactory.create(query), squad())) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                RDFNode pos = s.get("pos");
                assertNull(out.put(s.getLiteral("n").getString(), pos == null ? null : pos.asLiteral().getString()),
                        "one row per player");
            }
        }
        return out;
    }

    private static MockLLMProvider llm() {
        return new MockLLMProvider().withResponse("Lionel", "Forward").withDefaultResponse("Defender");
    }

    @Test
    void optionalGenopGeneratesForEverySolution() {
        Map<String, String> pos = positions(llm(),
                "OPTIONAL { GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>) }");
        assertEquals(Map.of("Lionel Messi", "Forward", "Cristian Romero", "Defender"), pos);
    }

    @Test
    void filterInsideOptionalKeepsTheOtherRowsWithoutValue() {
        Map<String, String> pos = positions(llm(),
                "OPTIONAL { GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>) FILTER(?pos = \"Forward\") }");
        assertEquals(2, pos.size());
        assertEquals("Forward", pos.get("Lionel Messi"));
        assertNull(pos.get("Cristian Romero"));
    }

    @Test
    void rowWithoutGeneratedValueStaysInTheAnswer() {
        MockLLMProvider llm = new MockLLMProvider().withResponse("Lionel", "Forward").withResponse("Cristian", "");
        Map<String, String> pos = positions(llm,
                "OPTIONAL { GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>) }");
        assertEquals(2, pos.size());
        assertEquals("Forward", pos.get("Lionel Messi"));
        assertNull(pos.get("Cristian Romero"));
    }

    @Test
    void promptMayNameSeveralVariablesOfTheRequiredPart() {
        Map<String, String> pos = positions(llm(),
                "?t rdfs:label ?team . OPTIONAL { GENOP(\"Position of {?n} in {?team}?\", (?pos), <model:mock:test>) }");
        assertEquals(Map.of("Lionel Messi", "Forward", "Cristian Romero", "Defender"), pos);
    }
}
