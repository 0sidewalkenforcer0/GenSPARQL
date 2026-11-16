package org.gensparql.test;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.util.Context;
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
 * The shapes that the code review of 2026-11-16 found evaluated against Section 2 of the paper,
 * run as the HTTP service runs a query (GenSPARQL.createQueryExecution with a MockLLMProvider):
 * an OPTIONAL, MINUS or BIND that reads a variable of Q1 applies after the join with the GENOP;
 * a FILTER on an output keeps its similarity when a later base-mode GENOP is joined; an output
 * mapped to an entity compares as that entity; and with the written order kept, an OPTIONAL or
 * MINUS between a GENOP and its pattern does not turn the similarity join into equality.
 * Without a SIM_TEXT in the context the engine uses the mock's embeddings, so "similar" means
 * equal up to case there.
 */
class GenOpReviewFixesTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            PREFIX wd: <http://www.wikidata.org/entity/>
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

    /**
     * The data of GenOpGroupSemanticsTest (Argentina and Mexico in Group A, France in Group B,
     * one player of each team, Messi's nickname), and Group A is seeded.
     */
    private static Model data() {
        Model m = ModelFactory.createDefaultModel();
        Property inGroup = m.createProperty(EX + "inGroup");
        Property playsFor = m.createProperty(EX + "playsFor");
        Resource gA = m.createResource(EX + "gA");
        Resource gB = m.createResource(EX + "gB");
        m.add(gA, RDF.type, m.createResource(EX + "Group")).add(gA, RDFS.label, "Group A");
        m.add(gB, RDF.type, m.createResource(EX + "Group")).add(gB, RDFS.label, "Group B");
        m.add(gA, m.createProperty(EX + "seeded"), m.createTypedLiteral(true));
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

    private List<Map<String, String>> run(String where, MockLLMProvider provider) {
        return run(where, provider, null);
    }

    /** The solutions, each a sorted map from variable to term, in a fixed order. */
    private List<Map<String, String>> run(String where, MockLLMProvider provider, Context context) {
        mock = provider;
        LLMProviderRegistry.setDefault(mock);
        List<Map<String, String>> rows = new ArrayList<>();
        Context cxt = context != null ? context : ARQ.getContext();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + "SELECT * WHERE { " + where + " }"),
                DatasetFactory.wrap(data()), cxt)) {
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

    private static MockLLMProvider teamList() {
        return new MockLLMProvider().withDefaultResponse("[\"argentina\", \"France\", \"Mexico\"]");
    }

    // ---- An OPTIONAL, MINUS or BIND that reads a variable of Q1 belongs to R ----

    @Test
    void aMinusThatReadsAVariableOfQ1AppliesAfterTheJoin() {
        // As in written order: France (Group B) is removed. Evaluated before G1, the MINUS would
        // share no variable with the empty solution and remove nothing.
        assertEquals(List.of(
                row("g", EX + "gA", "team", WD + "Q164089"),
                row("g", EX + "gA", "team", WD + "Q79800")),
                run("GENOP(\"List the teams.\", (?team), " + M + ") "
                        + "?team ex:inGroup ?g . MINUS { ?g rdfs:label \"Group B\" }", teamList()));
    }

    @Test
    void aBindThatReadsAVariableOfQ1AppliesAfterTheJoin() {
        // As in written order, ?u is the upper-case label, which ?team rdfs:label ?tn binds.
        assertEquals(List.of(
                row("team", WD + "Q164089", "tn", "Mexico", "u", "MEXICO"),
                row("team", WD + "Q47774", "tn", "France", "u", "FRANCE"),
                row("team", WD + "Q79800", "tn", "Argentina", "u", "ARGENTINA")),
                run("GENOP(\"List the teams.\", (?team), " + M + ") "
                        + "?team rdfs:label ?tn . BIND(UCASE(?tn) AS ?u)", teamList()));
    }

    @Test
    void anOptionalThatReadsAVariableOfQ1KeepsTheUnmatchedRows() {
        // As in written order: three teams, and France, whose group is not seeded, without ?s.
        assertEquals(List.of(
                row("g", EX + "gA", "s", "true", "team", WD + "Q164089"),
                row("g", EX + "gA", "s", "true", "team", WD + "Q79800"),
                row("g", EX + "gB", "team", WD + "Q47774")),
                run("GENOP(\"List the teams.\", (?team), " + M + ") "
                        + "?team ex:inGroup ?g . OPTIONAL { ?g ex:seeded ?s }", teamList()));
    }

    @Test
    void anOptionalThatReadsAVariableOfQ1DoesNotMultiplyTheContextModeCalls() {
        MockLLMProvider teams = new MockLLMProvider().withResponse("Lionel", "[\"Argentina\"]")
                .withResponse("Kylian", "[\"France\"]").withResponse("Raul", "[\"Mexico\"]");
        List<Map<String, String>> rows = run(
                "?p a ex:Athlete . ?p rdfs:label ?name . "
                        + "GENOP(\"Which national team does {?name} play for?\", (?team), " + M + ") "
                        + "?p ex:playsFor ?team . ?team ex:inGroup ?g . OPTIONAL { ?g rdfs:label ?gl }",
                teams);
        assertEquals(3, rows.size());
        // One prompt per athlete. Before G1, the OPTIONAL would join every labelled entity with
        // each athlete and send 24 prompts.
        assertEquals(3, calls());
    }

    // ---- A later similarity join keeps the generated types of earlier GENOPs ----

    private static MockLLMProvider positionsAndTeams() {
        return new MockLLMProvider().withResponse("Position of", "[\"Forward\"]")
                .withDefaultResponse("[\"Argentina\", \"France\", \"Mexico\"]");
    }

    @Test
    void aFilterOnAContextModeOutputUsesSimilarity() {
        // "Forward" and "forward" differ only in case (0.95).
        assertEquals(3, run("?p a ex:Athlete . ?p rdfs:label ?name . "
                + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") FILTER(?pos = \"forward\")",
                positionsAndTeams()).size());
    }

    @Test
    void aLaterBaseModeGenopWithItsPatternKeepsTheSimilarityOfTheFilter() {
        // ?pos is an output variable, so = compares by similarity also after the similarity join
        // of the base-mode GENOP that lists the teams.
        assertEquals(3, run("?p a ex:Athlete . ?p rdfs:label ?name . "
                + "GENOP(\"Position of {?name}?\", (?pos), " + M + ") "
                + "GENOP(\"List the teams.\", (?team), " + M + ") ?p ex:playsFor ?team "
                + "FILTER(?pos = \"forward\")", positionsAndTeams()).size());
    }

    // ---- An output mapped to an entity compares as that entity ----

    @Test
    void notEqualsOnAMappedOutputComparesTheEntity() {
        assertEquals(List.of(row("team", WD + "Q164089"), row("team", WD + "Q79800")),
                run("GENOP(\"List the teams.\", (?team), " + M + ") ?team a ex:Team "
                        + "FILTER(?team != wd:Q47774)", teamList()));
    }

    @Test
    void equalsOnAMappedOutputComparesTheEntity() {
        MockLLMProvider teams = new MockLLMProvider().withResponse("Lionel", "[\"Argentina\"]")
                .withResponse("Kylian", "[\"France\"]").withResponse("Raul", "[\"Mexico\"]");
        assertEquals(List.of(row("name", "Lionel Messi", "p", EX + "p1", "team", WD + "Q79800")),
                run("?p a ex:Athlete . ?p rdfs:label ?name . "
                        + "GENOP(\"Which national team does {?name} play for?\", (?team), " + M + ") "
                        + "?p ex:playsFor ?team FILTER(?team = wd:Q79800)", teams));
    }

    // ---- In written order, OPTIONAL or MINUS between a GENOP and its pattern ----

    @Test
    void withoutReorderingAnOptionalBetweenAGenopAndItsPatternKeepsTheJoin() {
        GenSPARQLConfig.setGenopReorderEnabled(false);
        // The OPTIONAL sees the generated names and matches nothing by equality; the similarity
        // join then maps the names: 3 rows without ?g.
        assertEquals(3, run("GENOP(\"List the teams.\", (?team), " + M + ") "
                + "OPTIONAL { ?team ex:inGroup ?g } ?team a ex:Team", teamList()).size());
    }

    @Test
    void withoutReorderingAMinusBetweenAGenopAndItsPatternKeepsTheJoin() {
        GenSPARQLConfig.setGenopReorderEnabled(false);
        // MINUS compares the names by equality and removes nothing; the similarity join maps them.
        assertEquals(3, run("GENOP(\"List the teams.\", (?team), " + M + ") "
                + "MINUS { ?team ex:inGroup ?g . ?g rdfs:label \"Group B\" } ?team a ex:Team",
                teamList()).size());
    }

    @Test
    void withCostBasedPlanningAnOptionalBetweenAGenopAndItsPatternKeepsTheJoin() {
        GenSPARQLConfig.setCostBasedPlanningEnabled(true);
        // Cost-based planning keeps the written order, as above.
        assertEquals(3, run("GENOP(\"List the teams.\", (?team), " + M + ") "
                + "OPTIONAL { ?team ex:inGroup ?g } ?team a ex:Team", teamList()).size());
    }
}
