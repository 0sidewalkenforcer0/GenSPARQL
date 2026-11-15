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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An optional part that holds more than a lone context-mode GENOP, run as the HTTP service
 * runs it ({@code GenSPARQL.createQueryExecution}).
 *
 * <ul>
 *   <li>A BIND after the GENOP hid the GENOP from the engine, so ARQ evaluated the optional
 *       part without the bindings of the required part and no prompt was sent.</li>
 *   <li>A base-mode GENOP in the optional part was joined with the graph pattern next to it by
 *       equality, so no generated name matched an entity.</li>
 *   <li>A FILTER of the optional part compared a generated value by its term, unlike a FILTER
 *       of the group, which compares it by similarity, whether the GENOP stood in the optional
 *       part or in the required part.</li>
 * </ul>
 */
class GenOpOptionalCompositionTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;
    private static final String M = "<model:mock:test>";
    private static final String EX = "http://example.org/";
    private static final String WD = "http://www.wikidata.org/entity/";

    private MockLLMProvider mock;

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    /** Argentina and Mexico in Group A, France in Group B, and one player of each team. */
    private static Model data() {
        Model m = ModelFactory.createDefaultModel();
        Property inGroup = m.createProperty(EX + "inGroup");
        Property playsFor = m.createProperty(EX + "playsFor");
        Resource gA = m.createResource(EX + "gA");
        Resource gB = m.createResource(EX + "gB");
        m.add(gA, RDF.type, m.createResource(EX + "Group")).add(gA, RDFS.label, "Group A");
        m.add(gB, RDF.type, m.createResource(EX + "Group")).add(gB, RDFS.label, "Group B");
        Resource team = m.createResource(EX + "Team");
        Resource arg = m.createResource(WD + "Q79800");
        Resource fra = m.createResource(WD + "Q47774");
        Resource mex = m.createResource(WD + "Q164089");
        m.add(arg, RDF.type, team).add(arg, RDFS.label, "Argentina").add(arg, inGroup, gA);
        m.add(fra, RDF.type, team).add(fra, RDFS.label, "France").add(fra, inGroup, gB);
        m.add(mex, RDF.type, team).add(mex, RDFS.label, "Mexico").add(mex, inGroup, gA);
        Resource athlete = m.createResource(EX + "Athlete");
        Resource p1 = m.createResource(EX + "p1");
        Resource p2 = m.createResource(EX + "p2");
        Resource p3 = m.createResource(EX + "p3");
        m.add(p1, RDF.type, athlete).add(p1, RDFS.label, "Lionel Messi").add(p1, playsFor, arg);
        m.add(p2, RDF.type, athlete).add(p2, RDFS.label, "Kylian Mbappe").add(p2, playsFor, fra);
        m.add(p3, RDF.type, athlete).add(p3, RDFS.label, "Raul Jimenez").add(p3, playsFor, mex);
        m.add(p1, m.createProperty(EX + "role"), "forward");
        return m;
    }

    /** The solutions of the query, each as a sorted map from variable to term, in a fixed order. */
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
        rows.sort(Comparator.comparing(Map::toString));
        return rows;
    }

    private int calls() {
        return mock.getRequestHistory().size();
    }

    private static Map<String, String> row(String... keyValues) {
        Map<String, String> row = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put(keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static final String PLAYERS = "?p a ex:Athlete . ?p rdfs:label ?name ";

    @Test
    void aBindAfterTheGenopReadsTheGeneratedValue() {
        List<Map<String, String>> rows = run(PLAYERS
                        + "OPTIONAL { GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "BIND(CONCAT(?name, \": \", ?pos) AS ?label) }",
                new MockLLMProvider().withResponse("Lionel", "[]").withDefaultResponse("[\"Forward\"]"));

        assertEquals(3, calls(), "one prompt for every player");
        assertEquals(List.of(
                row("label", "Kylian Mbappe: Forward", "name", "Kylian Mbappe", "p", EX + "p2", "pos", "Forward"),
                row("label", "Raul Jimenez: Forward", "name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward"),
                row("name", "Lionel Messi", "p", EX + "p1")), rows,
                "a player without an answer keeps neither the position nor the label");
    }

    @Test
    void aBaseModeGenopIsJoinedBySimilarityWithinTheOptionalPart() {
        List<Map<String, String>> rows = run(
                "?g a ex:Group OPTIONAL { ?team ex:inGroup ?g . "
                        + "GENOP(\"List the teams.\", (?team), " + M + ") }",
                new MockLLMProvider().withDefaultResponse("[\"argentina\", \"France\", \"Mexico\"]"));

        assertEquals(1, calls(), "a base-mode prompt is sent once");
        assertEquals(List.of(
                row("g", EX + "gA", "team", WD + "Q164089"),
                row("g", EX + "gA", "team", WD + "Q79800"),
                row("g", EX + "gB", "team", WD + "Q47774")), rows);
    }

    @Test
    void aSolutionWithoutAMatchKeepsItsBindingsOnly() {
        List<Map<String, String>> rows = run(
                "?g a ex:Group OPTIONAL { ?team ex:inGroup ?g . "
                        + "GENOP(\"List the teams.\", (?team), " + M + ") }",
                new MockLLMProvider().withDefaultResponse("[\"Argentina\", \"Mexico\"]"));

        assertEquals(1, calls());
        assertEquals(List.of(
                row("g", EX + "gA", "team", WD + "Q164089"),
                row("g", EX + "gA", "team", WD + "Q79800"),
                row("g", EX + "gB")), rows, "no generated name matches the team of Group B");
    }

    @Test
    void aFilterOfTheOptionalPartComparesGeneratedValuesBySimilarity() {
        List<Map<String, String>> equal = run(PLAYERS
                        + "OPTIONAL { GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "FILTER(?pos = \"forward\") }",
                new MockLLMProvider().withDefaultResponse("[\"Forward\"]"));

        assertEquals(3, calls());
        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2", "pos", "Forward"),
                row("name", "Lionel Messi", "p", EX + "p1", "pos", "Forward"),
                row("name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward")), equal);

        List<Map<String, String>> notEqual = run(PLAYERS
                        + "OPTIONAL { GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "FILTER(?pos != \"forward\") }",
                new MockLLMProvider().withDefaultResponse("[\"Forward\"]"));

        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2"),
                row("name", "Lionel Messi", "p", EX + "p1"),
                row("name", "Raul Jimenez", "p", EX + "p3")), notEqual,
                "the filter removes every generated value and each player stays without one");
    }

    @Test
    void aFilterOfTheOptionalPartComparesAGeneratedValueOfTheRequiredPartBySimilarity() {
        List<Map<String, String>> rows = run(PLAYERS
                        + ". GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "OPTIONAL { ?p ex:role ?role FILTER(?role = ?pos) }",
                new MockLLMProvider().withDefaultResponse("[\"Forward\"]"));

        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2", "pos", "Forward"),
                row("name", "Lionel Messi", "p", EX + "p1", "pos", "Forward", "role", "forward"),
                row("name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward")), rows,
                "the stored role forward matches the generated Forward");
    }
}
