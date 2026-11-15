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
 * are joined with its solutions by similarity, in both modes.
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

    /** Argentina and Mexico in Group A, France in Group B, one player of each team, two capitals. */
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
        Resource city = m.createResource(EX + "City");
        m.add(m.createResource(WD + "Q1486"), RDF.type, city)
                .add(m.createResource(WD + "Q1486"), RDFS.label, "Buenos Aires");
        m.add(m.createResource(WD + "Q1489"), RDF.type, city)
                .add(m.createResource(WD + "Q1489"), RDFS.label, "Mexico City");
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
        assertEquals(List.of(row("name", "Lionel Messi", "p", EX + "p1", "team", WD + "Q79800")), rows,
                "only the team whose label is similar to the generated name joins, and keeps its IRI");
    }

    @Test
    void aPatternThatMentionsTheOutputIsJoinedBySimilarity() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . ?p ex:nickname ?nick . "
                        + "GENOP(\"Nickname of {?name}?\", (?nick), " + M + ")",
                new MockLLMProvider().withResponse("Lionel", "[\"la pulga\"]")
                        .withDefaultResponse("[\"Someone\"]"));

        assertEquals(3, calls());
        assertEquals(List.of(row("name", "Lionel Messi", "nick", "La Pulga", "p", EX + "p1")), rows,
                "the stored nickname similar to the generated one survives, with its own term");
    }

    // ---- A2: an output variable that the input already binds is joined, not overwritten ----

    @Test
    void contextModeJoinsAnOutputThatTheInputBindsBySimilarity() {
        String where = "?p a ex:Athlete . ?p rdfs:label ?name . ?p ex:nickname ?x . BIND(1 AS ?one) "
                + "GENOP(\"Nickname of {?name}?\", (?x), " + M + ")";

        assertEquals(List.of(), run(where, new MockLLMProvider().withDefaultResponse("[\"The Flea\"]")),
                "the generated nickname is not similar to the stored one");
        assertEquals(List.of(row("name", "Lionel Messi", "one", "1", "p", EX + "p1", "x", "La Pulga")),
                run(where, new MockLLMProvider().withDefaultResponse("[\"la pulga\"]")),
                "a nickname that differs only in case joins, and the row keeps the stored term");
    }

    @Test
    void aContextModeGenopComparesAnOutputThatTheInputBindsWithTheLabelsOfItsTerm() {
        GenSPARQLConfig.setGenopReorderEnabled(false);
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . ?p ex:playsFor ?team . "
                        + "GENOP(\"Which national team does {?name} play for?\", (?team), " + M + ")",
                new MockLLMProvider().withResponse("Lionel Messi", "[\"argentina\"]")
                        .withResponse("Kylian Mbappe", "[\"Spain\"]")
                        .withDefaultResponse("[]"));

        assertEquals(3, calls(), "in written order the GENOP receives the team of every athlete");
        assertEquals(List.of(row("name", "Lionel Messi", "p", EX + "p1", "team", WD + "Q79800")), rows,
                "the generated name is compared with the label of the team that the input binds");
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

    // ---- Both modes: the patterns that mention the output are joined by similarity ----

    @Test
    void aContextModeGenopMapsAGeneratedNameToTheEntityOfThePattern() {
        String where = "?p rdfs:label ?name . ?p ex:playsFor ?team . "
                + "GENOP(\"Which national team does {?name} play for?\", (?team), " + M + ")";

        assertEquals(List.of(row("name", "Lionel Messi", "p", EX + "p1", "team", WD + "Q79800")),
                run(where, new MockLLMProvider().withResponse("Lionel Messi", "[\"Argentina\"]")
                        .withDefaultResponse("[]")),
                "the generated name is similar to the label of his team, and the row keeps its IRI");
        assertEquals(List.of(),
                run(where, new MockLLMProvider().withResponse("Lionel Messi", "[\"Brazil\"]")
                        .withDefaultResponse("[]")),
                "a name that is not similar to the label of his team removes the row");
    }

    @Test
    void aContextModeGenopMatchesALiteralLabelBySimilarity() {
        List<Map<String, String>> rows = run(
                "?team ex:inGroup ?g . ?g rdfs:label \"Group A\" . ?team rdfs:label ?tn . "
                        + "GENOP(\"Capital of {?tn}?\", (?city), " + M + ") "
                        + "?c a ex:City . ?c rdfs:label ?city",
                new MockLLMProvider().withResponse("Mexico", "[\"Mexico city\"]")
                        .withResponse("Argentina", "[\"Buenos Aires\"]")
                        .withDefaultResponse("[]"));

        assertEquals(List.of(
                row("c", WD + "Q1486", "city", "Buenos Aires", "g", EX + "gA", "team", WD + "Q79800",
                        "tn", "Argentina"),
                row("c", WD + "Q1489", "city", "Mexico City", "g", EX + "gA", "team", WD + "Q164089",
                        "tn", "Mexico")), rows,
                "\"Mexico city\" matches the label \"Mexico City\", and the row keeps the label");
    }

    @Test
    void anOptionalContextModeGenopJoinsItsPatternsBySimilarity() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name "
                        + "OPTIONAL { ?p ex:playsFor ?team . "
                        + "GENOP(\"Which national team does {?name} play for?\", (?team), " + M + ") }",
                new MockLLMProvider().withResponse("Lionel Messi", "[\"Argentina\"]")
                        .withResponse("Raul Jimenez", "[\"mexico\"]")
                        .withDefaultResponse("[]"));

        assertEquals(3, calls(), "the optional part is evaluated for every athlete");
        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2"),
                row("name", "Lionel Messi", "p", EX + "p1", "team", WD + "Q79800"),
                row("name", "Raul Jimenez", "p", EX + "p3", "team", WD + "Q164089")), rows,
                "a matched athlete has the IRI of his team, one without an answer is kept without it");
    }

    @Test
    void aContextModeGenopIsJoinedWithItsPatternsAcrossOptionalAndMinus() {
        MockLLMProvider teams = new MockLLMProvider().withResponse("Lionel", "[\"Argentina\"]")
                .withResponse("Kylian", "[\"france\"]").withResponse("Raul", "[\"Mexico\"]");
        String genop = "GENOP(\"Team of {?name}?\", (?team), " + M + ") ";

        assertEquals(List.of(
                row("n", "La Pulga", "name", "Lionel Messi", "p", EX + "p1", "team", WD + "Q79800"),
                row("name", "Kylian Mbappe", "p", EX + "p2", "team", WD + "Q47774"),
                row("name", "Raul Jimenez", "p", EX + "p3", "team", WD + "Q164089")),
                run("?p a ex:Athlete . ?p rdfs:label ?name . " + genop
                        + "OPTIONAL { ?p ex:nickname ?n } ?p ex:playsFor ?team", teams));
        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2", "team", WD + "Q47774"),
                row("name", "Raul Jimenez", "p", EX + "p3", "team", WD + "Q164089")),
                run("?p a ex:Athlete . ?p rdfs:label ?name . " + genop
                        + "MINUS { ?p ex:nickname ?n } ?p ex:playsFor ?team", teams));
    }

    // ---- A3: a later GENOP reads the solutions of an earlier one ----

    private static MockLLMProvider teamsPositionsCoaches() {
        return new MockLLMProvider()
                .withResponse("Position of", "[\"Forward\"]")
                .withResponse("Coach of", "[\"Someone\"]")
                .withDefaultResponse("[\"Argentina\", \"France\", \"Mexico\"]");
    }

    @Test
    void aContextModeGenopReadsTheEntitiesOfAnEarlierBaseModeGenop() {
        List<Map<String, String>> rows = run(
                "?p rdfs:label ?name ; ex:playsFor ?team . "
                        + "GENOP(\"List the teams.\", (?team), " + M + ") "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ")",
                teamsPositionsCoaches());

        assertEquals(4, calls(), "one list of teams and one position for each player");
        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2", "pos", "Forward", "team", WD + "Q47774"),
                row("name", "Lionel Messi", "p", EX + "p1", "pos", "Forward", "team", WD + "Q79800"),
                row("name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward", "team", WD + "Q164089")), rows);
    }

    @Test
    void thePromptOfTheLaterGenopNamesTheMappedEntities() {
        List<Map<String, String>> rows = run(
                "?team a ex:Team . GENOP(\"List the teams.\", (?team), " + M + ") "
                        + "?team rdfs:label ?tn . GENOP(\"Coach of {?tn}?\", (?coach), " + M + ")",
                teamsPositionsCoaches());

        assertEquals(4, calls());
        assertEquals(3, rows.size());
        assertEquals(List.of("Coach of Argentina?", "Coach of France?", "Coach of Mexico?"),
                mock.getRequestHistory().stream().map(r -> r.getPrompt())
                        .filter(p -> p.startsWith("Coach of")).sorted().toList());
    }

    @Test
    void aBaseModeGenopAfterAContextModeOneIsStillMappedBySimilarity() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . "
                        + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                        + "?p ex:playsFor ?team . GENOP(\"List the teams.\", (?team), " + M + ")",
                teamsPositionsCoaches());

        assertEquals(4, calls());
        assertEquals(List.of(
                row("name", "Kylian Mbappe", "p", EX + "p2", "pos", "Forward", "team", WD + "Q47774"),
                row("name", "Lionel Messi", "p", EX + "p1", "pos", "Forward", "team", WD + "Q79800"),
                row("name", "Raul Jimenez", "p", EX + "p3", "pos", "Forward", "team", WD + "Q164089")), rows);
    }

    @Test
    void anOptionalPartWithBothModesSendsTheBaseModePromptOnce() {
        List<Map<String, String>> rows = run(
                "?g a ex:Group OPTIONAL { ?team ex:inGroup ?g . "
                        + "GENOP(\"List the teams.\", (?team), " + M + ") "
                        + "?team rdfs:label ?tn . GENOP(\"Coach of {?tn}?\", (?coach), " + M + ") }",
                teamsPositionsCoaches());

        assertEquals(4, calls(), "one list of teams for both groups and one coach for each team");
        assertEquals(List.of(
                row("coach", "Someone", "g", EX + "gA", "team", WD + "Q164089", "tn", "Mexico"),
                row("coach", "Someone", "g", EX + "gA", "team", WD + "Q79800", "tn", "Argentina"),
                row("coach", "Someone", "g", EX + "gB", "team", WD + "Q47774", "tn", "France")), rows);
    }

    // ---- A4: the patterns that mention the output of a base-mode GENOP join it together ----

    @Test
    void twoBlocksThatMentionTheOutputOfABaseModeGenopAreBothJoined() {
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . "
                        + "GENOP(\"List footballers who have won the World Cup.\", (?p), " + M + ") "
                        + "?p ex:playsFor ?t . ?t rdfs:label \"Argentina\"",
                new MockLLMProvider().withDefaultResponse("[\"Lionel Messi\", \"Kylian Mbappe\"]"));

        assertEquals(1, calls());
        assertEquals(List.of(row("p", EX + "p1", "t", WD + "Q79800")), rows,
                "Messi is mapped to his entity and plays for Argentina");
    }

    @Test
    void aPatternAfterTheMappingComparesTheMappedEntitiesByEquality() {
        List<Map<String, String>> rows = run(
                "?team a ex:Team . GENOP(\"List the teams.\", (?team), " + M + ") "
                        + "?team ex:inGroup ?g . ?g rdfs:label \"Group A\"",
                new MockLLMProvider().withDefaultResponse("[\"Argentina\", \"France\", \"Mexico\"]"));

        assertEquals(List.of(
                row("g", EX + "gA", "team", WD + "Q164089"),
                row("g", EX + "gA", "team", WD + "Q79800")), rows);
    }
}
