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
 * A group with GENOP evaluated as the semantics defines it, run as the HTTP service runs it
 * ({@code GenSPARQL.createQueryExecution}). The pattern P of a GENOP consists of the patterns
 * of the group that do not mention its output variables, and the patterns that mention them
 * are joined with its solutions.
 */
class GenOpGroupSemanticsTest {

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

    /** Argentina and Mexico in Group A, France in Group B, one player of each team. */
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
        m.add(p1, m.createProperty(EX + "nickname"), "La Pulga");
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

    // ---- A1: the planner moves single patterns, not blocks ----

    @Test
    void aBlockThatBindsThePlaceholderAndMentionsTheOutputIsSplit() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . ?p ex:playsFor ?team . "
                        + "GENOP(\"Which national team does {?name} play for?\", (?team), " + M + ")",
                new MockLLMProvider().withDefaultResponse("[\"Argentina\"]"));

        assertEquals(3, calls(), "the label of every athlete reaches the prompt");
        assertEquals(List.of(), rows, "a generated name does not equal the IRI of a team");
    }

    @Test
    void aPatternThatMentionsTheOutputIsJoinedByEquality() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . ?p ex:nickname ?nick . "
                        + "GENOP(\"Nickname of {?name}?\", (?nick), " + M + ")",
                new MockLLMProvider().withResponse("Lionel", "[\"La Pulga\"]")
                        .withDefaultResponse("[\"Someone\"]"));

        assertEquals(3, calls());
        assertEquals(List.of(row("name", "Lionel Messi", "nick", "La Pulga", "p", EX + "p1")), rows,
                "only the stored nickname that equals the generated one survives");
    }

    // ---- A2: an output variable that the input already binds is joined, not overwritten ----

    @Test
    void contextModeJoinsAnOutputThatTheInputBindsByEquality() {
        String where = "?p a ex:Athlete . ?p rdfs:label ?name . ?p ex:nickname ?x . BIND(1 AS ?one) "
                + "GENOP(\"Nickname of {?name}?\", (?x), " + M + ")";

        assertEquals(List.of(), run(where, new MockLLMProvider().withDefaultResponse("[\"The Flea\"]")),
                "the generated nickname differs from the stored one");
        assertEquals(List.of(row("name", "Lionel Messi", "one", "1", "p", EX + "p1", "x", "La Pulga")),
                run(where, new MockLLMProvider().withDefaultResponse("[\"La Pulga\"]")));
    }

    @Test
    void anOptionalGenopKeepsTheValueThatTheRequiredPartBinds() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . ?p ex:nickname ?x "
                        + "OPTIONAL { GENOP(\"Nickname of {?name}?\", (?x), " + M + ") }",
                new MockLLMProvider().withDefaultResponse("[\"The Flea\"]"));

        assertEquals(List.of(row("name", "Lionel Messi", "p", EX + "p1", "x", "La Pulga")), rows);
    }

    @Test
    void baseModeJoinsAnOutputThatTheInputBindsBySimilarity() {
        List<Map<String, String>> rows = run(
                "?team a ex:Team . BIND(1 AS ?one) "
                        + "GENOP(\"Describe {?one}.\", (?d), " + M + ") "
                        + "GENOP(\"List the teams.\", (?team), " + M + ")",
                new MockLLMProvider().withResponse("Describe", "[\"a team\"]")
                        .withDefaultResponse("[\"argentina\", \"France\", \"Mexico\"]"));

        assertEquals(List.of(
                row("d", "a team", "one", "1", "team", WD + "Q164089"),
                row("d", "a team", "one", "1", "team", WD + "Q47774"),
                row("d", "a team", "one", "1", "team", WD + "Q79800")), rows,
                "each team keeps its IRI and matches only the name similar to its label");
    }
}
