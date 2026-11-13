package org.gensparql.test;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Joins around GENOP that lost or kept the wrong solutions, run as the HTTP service runs them
 * ({@code GenSPARQL.createQueryExecution}).
 *
 * <ul>
 *   <li>A similarity join below another join read only the first incoming solution.</li>
 *   <li>Once a GENOP was in the query, every later join of graph patterns took the path of the
 *       similarity join, and so did the same.</li>
 *   <li>{@code !=} kept the solutions in which a variable was unbound, and {@code =} compared
 *       stored typed values as terms instead of values.</li>
 *   <li>A base-mode GENOP after a context-mode one sent its constant prompt once per solution.</li>
 * </ul>
 */
class GenOpJoinRegressionTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;
    private static final String M = "<model:mock:test>";

    private MockLLMProvider mock;

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    /** Three players of three teams, two of the teams in Group A. */
    private static Model data() {
        Model m = ModelFactory.createDefaultModel();
        String ex = "http://example.org/";
        Property inGroup = m.createProperty(ex + "inGroup");
        Property playsFor = m.createProperty(ex + "playsFor");
        Property nickname = m.createProperty(ex + "nickname");
        Property caps = m.createProperty(ex + "caps");
        Resource gA = m.createResource(ex + "gA");
        Resource gB = m.createResource(ex + "gB");
        m.add(gA, RDF.type, m.createResource(ex + "Group")).add(gA, RDFS.label, "Group A");
        m.add(gB, RDF.type, m.createResource(ex + "Group")).add(gB, RDFS.label, "Group B");
        Resource team = m.createResource(ex + "Team");
        Resource arg = m.createResource("http://www.wikidata.org/entity/Q79800");
        Resource fra = m.createResource("http://www.wikidata.org/entity/Q47774");
        Resource mex = m.createResource("http://www.wikidata.org/entity/Q164089");
        m.add(arg, RDF.type, team).add(arg, RDFS.label, "Argentina").add(arg, inGroup, gA);
        m.add(fra, RDF.type, team).add(fra, RDFS.label, "France").add(fra, inGroup, gB);
        m.add(mex, RDF.type, team).add(mex, RDFS.label, "Mexico").add(mex, inGroup, gA);
        Resource athlete = m.createResource(ex + "Athlete");
        Resource p1 = m.createResource(ex + "p1");
        Resource p2 = m.createResource(ex + "p2");
        Resource p3 = m.createResource(ex + "p3");
        m.add(p1, RDF.type, athlete).add(p1, RDFS.label, "Lionel Messi").add(p1, playsFor, arg);
        m.add(p2, RDF.type, athlete).add(p2, RDFS.label, "Kylian Mbappe").add(p2, playsFor, fra);
        m.add(p3, RDF.type, athlete).add(p3, RDFS.label, "Raul Jimenez").add(p3, playsFor, mex);
        m.add(p1, nickname, "La Pulga");
        m.add(p1, caps, m.createTypedLiteral(100));
        m.add(p2, caps, m.createTypedLiteral(80));
        m.add(p3, caps, m.createTypedLiteral(60));
        return m;
    }

    /** The solutions of the query, each as a sorted map from variable to term. */
    private List<Map<String, String>> run(String where, MockLLMProvider provider) {
        mock = provider;
        LLMProviderRegistry.setDefault(mock);
        List<Map<String, String>> rows = new ArrayList<>();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + "SELECT * WHERE { " + where + " }"), data())) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                Map<String, String> row = new TreeMap<>();
                s.varNames().forEachRemaining(v -> {
                    RDFNode n = s.get(v);
                    row.put(v, n.isResource() ? n.asResource().getURI() : n.asLiteral().getLexicalForm());
                });
                rows.add(row);
            }
        }
        return rows;
    }

    private int calls() {
        return mock.getRequestHistory().size();
    }

    private static MockLLMProvider positionsAndTeams(String position) {
        return new MockLLMProvider()
                .withResponse("Position of", position)
                .withDefaultResponse("[\"Argentina\", \"France\", \"Mexico\"]");
    }

    @Test
    void aSimilarityJoinBelowAnotherJoinKeepsEverySolution() {
        List<Map<String, String>> rows = run(
                "?p rdfs:label ?name . ?p ex:playsFor ?team . "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "{ ?team a ex:Team . GENOP(\"List the teams.\", (?team), " + M + ") }",
                positionsAndTeams("[\"Forward\"]"));

        assertEquals(3, rows.size(), "every player of a mapped team");
        assertEquals(4, calls(), "three positions and one list of teams");
        for (Map<String, String> row : rows) {
            assertEquals("Forward", row.get("pos"));
        }
    }

    @Test
    void aSimilarityJoinBelowAnotherJoinGivesNothingWhenNothingComesIn() {
        List<Map<String, String>> rows = run(
                "?p rdfs:label ?name . ?p ex:playsFor ?team . "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "{ ?team a ex:Team . GENOP(\"List the teams.\", (?team), " + M + ") }",
                positionsAndTeams("[]"));

        assertEquals(List.of(), rows);
    }

    @Test
    void aJoinOfGraphPatternsAfterAGenopComparesTermsByEquality() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "{ ?t ex:inGroup ?g FILTER(?g != ex:gB) ?g rdfs:label ?gl }",
                positionsAndTeams("[\"Forward\"]"));

        assertEquals(6, rows.size(), "three players times the two teams of Group A");
    }

    @Test
    void notEqualsRemovesTheSolutionsInWhichAVariableIsUnbound() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name OPTIONAL { ?p ex:nickname ?nick } "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "FILTER(?nick != \"La Pulga\")",
                positionsAndTeams("[\"Forward\"]"));

        assertEquals(List.of(), rows, "?nick is unbound for two players and La Pulga for the third");
    }

    @Test
    void equalityOnStoredValuesComparesValues() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . ?p ex:caps ?caps "
                        + "OPTIONAL { ?p ex:nickname ?nick } "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "FILTER(?caps = 100)",
                positionsAndTeams("[\"Forward\"]"));

        assertEquals(1, rows.size(), "\"100\"^^xsd:int equals 100");
        assertEquals("http://example.org/p1", rows.get(0).get("p"));
    }

    @Test
    void aBaseModeGenopSendsItsPromptOnce() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "GENOP(\"Name the four positions.\", (?all), " + M + ")",
                new MockLLMProvider().withResponse("Position of", "[\"Forward\"]")
                        .withDefaultResponse("[\"goalkeeper\"]"));

        assertEquals(3, rows.size());
        assertEquals(4, calls(), "three context-mode calls and one base-mode call");
    }
}
