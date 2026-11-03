package org.gensparql.test;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An entity with several labels matches a generated name that reaches the threshold with any
 * of them. The host cities of the World Cup graph carry two labels, such as Arlington and
 * Dallas, and the FIFA name of the city is the second one.
 */
class MultiLabelSimJoinTest {

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

    /** Two host cities, one of them with two labels. */
    private static Model cities() {
        Model m = ModelFactory.createDefaultModel();
        Resource city = m.createResource("http://example.org/City");
        Resource arlington = m.createResource("http://www.wikidata.org/entity/Q17943");
        m.add(arlington, RDF.type, city);
        m.add(arlington, RDFS.label, "Arlington");
        m.add(arlington, RDFS.label, "Dallas");
        Resource houston = m.createResource("http://www.wikidata.org/entity/Q16555");
        m.add(houston, RDF.type, city);
        m.add(houston, RDFS.label, "Houston");
        return m;
    }

    /** Maps the generated names through a base-mode GENOP and returns the bound IRIs, sorted. */
    private static List<String> mapped(String answer, String threshold) {
        LLMProviderRegistry.setDefault(new MockLLMProvider().withDefaultResponse(answer));
        String query = PREFIXES + "SELECT ?c WHERE { ?c a ex:City . "
                + "GENOP(\"List the host cities.\", (?c), <model:mock:test>" + threshold + ") }";
        List<String> iris = new ArrayList<>();
        try (QueryExecution qe = QueryExecution.create()
                .query(GenSPARQLQueryFactory.create(query))
                .model(cities())
                .build()) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                iris.add(s.getResource("c").getURI());
            }
        }
        iris.sort(null);
        return iris;
    }

    @Test
    void theSecondLabelMatchesUnderTheDefaultThreshold() {
        assertEquals(List.of("http://www.wikidata.org/entity/Q17943"), mapped("[\"Dallas\"]", ""));
    }

    @Test
    void theFirstLabelStillMatches() {
        assertEquals(List.of("http://www.wikidata.org/entity/Q17943"), mapped("[\"Arlington\"]", ""));
    }

    @Test
    void bothLabelsBindTheEntityOnce() {
        assertEquals(List.of("http://www.wikidata.org/entity/Q16555", "http://www.wikidata.org/entity/Q17943"),
                mapped("[\"Dallas\", \"Houston\"]", ""));
    }

    @Test
    void theSecondLabelMatchesExactly() {
        assertEquals(List.of("http://www.wikidata.org/entity/Q17943"), mapped("[\"Dallas\"]", ", 1.0"));
    }

    @Test
    void aNameOfNoLabelDoesNotMatch() {
        assertEquals(List.of(), mapped("[\"Austin\"]", ""));
    }
}
