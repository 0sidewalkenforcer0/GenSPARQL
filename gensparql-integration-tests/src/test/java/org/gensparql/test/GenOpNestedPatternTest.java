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
 * GENOP inside UNION, VALUES, a sub-select and EXISTS, run as the HTTP service runs it
 * ({@code GenSPARQL.createQueryExecution}).
 */
class GenOpNestedPatternTest {

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

    // ---- B1: UNION ----

    @Test
    void aBaseModeGenopInAUnionBranchIsMappedBySimilarity() {
        List<Map<String, String>> rows = run(
                "{ ?x a ex:Team . GENOP(\"List the teams.\", (?x), " + M + ") } UNION { ?x a ex:Athlete }",
                new MockLLMProvider().withDefaultResponse("[\"argentina\", \"France\", \"Mexico\"]"));

        assertEquals(1, calls());
        assertEquals(List.of(
                row("x", EX + "p1"), row("x", EX + "p2"), row("x", EX + "p3"),
                row("x", WD + "Q164089"), row("x", WD + "Q47774"), row("x", WD + "Q79800")), rows);
    }

    @Test
    void anOptionalInAUnionBranchIsEvaluatedByTheEngine() {
        List<Map<String, String>> rows = run(
                "{ ?p a ex:Athlete . ?p rdfs:label ?name "
                        + "OPTIONAL { GENOP(\"Position of {?name}?\", (?pos), " + M + ") } } "
                        + "UNION { ?p a ex:Group }",
                new MockLLMProvider().withResponse("Lionel", "[]").withDefaultResponse("[\"Forward\"]"));

        assertEquals(3, calls());
        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2", "pos", "Forward"),
                row("name", "Lionel Messi", "p", EX + "p1"),
                row("name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward"),
                row("p", EX + "gA"), row("p", EX + "gB")), rows);
    }

    // ---- B3: VALUES in the middle of a group ----

    @Test
    void valuesInTheMiddleOfAGroupKeepsTheSolutionsBeforeIt() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . VALUES ?p { ex:p1 ex:p3 } "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ")",
                new MockLLMProvider().withDefaultResponse("[\"Forward\"]"));

        assertEquals(2, calls(), "a prompt for each of the two athletes that VALUES admits");
        assertEquals(List.of(
                row("name", "Lionel Messi", "p", EX + "p1", "pos", "Forward"),
                row("name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward")), rows);
    }

    // ---- B4: a sub-select joined with the patterns of the outer group ----

    @Test
    void aSubSelectIsJoinedWithTheOuterPatternsByEquality() {
        List<Map<String, String>> rows = run(
                "?p rdfs:label ?name . { SELECT ?p ?pos WHERE { ?p a ex:Athlete ; rdfs:label ?n . "
                        + "GENOP(\"Position of {?n}?\", (?pos), " + M + ") } }",
                new MockLLMProvider().withDefaultResponse("[\"Forward\"]"));

        assertEquals(3, calls());
        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2", "pos", "Forward"),
                row("name", "Lionel Messi", "p", EX + "p1", "pos", "Forward"),
                row("name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward")), rows,
                "the outer ?name stays in every solution");
    }

    @Test
    void aVariableThatTheSubSelectDoesNotSelectIsLocalToIt() {
        List<Map<String, String>> rows = run(
                "?p rdfs:label ?name . { SELECT ?p WHERE { ?p a ex:Athlete . ?p ex:playsFor ?name } } "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ")",
                new MockLLMProvider().withDefaultResponse("[\"Forward\"]"));

        assertEquals(3, calls(), "the ?name of the sub-select does not constrain the outer one");
        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2", "pos", "Forward"),
                row("name", "Lionel Messi", "p", EX + "p1", "pos", "Forward"),
                row("name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward")), rows);
    }
}
