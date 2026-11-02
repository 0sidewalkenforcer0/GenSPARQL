package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.engine.similarity.GraphLabels;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A generated name grounds to the KG entity whose label it matches, also when the entity's IRI
 * carries no readable name, and in both ways a query can place the GENOP: in the same group as
 * the KG pattern (the form the paper uses) or in a group of its own.
 */
class SimJoinLabelGroundingTest {

    private static final String EX = "http://example.org/";

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    /** Teams whose IRIs are opaque, as in a KG built from Wikidata. */
    private static Model teams() {
        Model m = ModelFactory.createDefaultModel();
        String[][] teams = {{"Q414", "Argentina"}, {"Q83459", "Brazil"}, {"Q47774", "France"}};
        for (String[] t : teams) {
            Resource r = m.createResource(EX + t[0]);
            m.add(r, RDF.type, m.createResource(EX + "Team"));
            m.add(r, RDFS.label, t[1]);
        }
        return m;
    }

    private static List<String> teamsFor(String generated, String query) {
        LLMProviderRegistry.setDefault(new MockLLMProvider().withDefaultResponse(generated));
        List<String> out = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(GenSPARQLQueryFactory.create(query), teams())) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                out.add(String.valueOf(rs.next().get("team")));
            }
        }
        return out;
    }

    private static final String SAME_GROUP = """
            PREFIX ex: <http://example.org/>
            SELECT ?team WHERE {
              ?team a ex:Team .
              GENOP("Which team won the 2022 FIFA World Cup?", (?team), <model:mock:test>, 0.85)
            }
            """;

    private static final String OWN_GROUP = """
            PREFIX ex: <http://example.org/>
            SELECT ?team WHERE {
              { ?team a ex:Team }
              { GENOP("Which team won the 2022 FIFA World Cup?", (?team), <model:mock:test>, 0.85) }
            }
            """;

    @Test
    void sameGroupGroundsByLabel() {
        assertEquals(List.of(EX + "Q414"), teamsFor("Argentina", SAME_GROUP));
    }

    @Test
    void ownGroupGroundsByLabel() {
        assertEquals(List.of(EX + "Q414"), teamsFor("Argentina", OWN_GROUP));
    }

    @Test
    void nameAbsentFromTheKgIsDropped() {
        assertEquals(List.of(), teamsFor("Italy", SAME_GROUP));
        assertEquals(List.of(), teamsFor("Italy", OWN_GROUP));
    }

    @Test
    void graphLabelsPreferAnUntaggedLabelThenEnglish() {
        Model m = ModelFactory.createDefaultModel();
        RDFParser.fromString("""
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
                <http://example.org/a> rdfs:label "Argentinien"@de , "Argentina"@en .
                <http://example.org/b> rdfs:label "Brasilien"@de , "Brazil" .
                <http://example.org/c> skos:prefLabel "France" .
                <http://example.org/d> a <http://example.org/Team> .
                """).lang(Lang.TURTLE).parse(m);
        GraphLabels labels = new GraphLabels(m.getGraph());

        assertEquals("Argentina", labels.apply(m.createResource(EX + "a").asNode()));
        assertEquals("Brazil", labels.apply(m.createResource(EX + "b").asNode()));
        assertEquals("France", labels.apply(m.createResource(EX + "c").asNode()));
        assertNull(labels.apply(m.createResource(EX + "d").asNode()));
    }
}
