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
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.GenSPARQLConstants;
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
 * Shapes of Section 2 of the paper that the code review of 2026-11-16 found evaluated otherwise.
 * A8: an element of Qi that contains a GENOP of its own, two GENOPs with the same output
 * variable, and an OPTIONAL whose base-mode GENOP generates a variable that the required part
 * binds. A7: a comparison of a generated value inside ||, && and ! of a FILTER. Each test states
 * the solutions that Section 2 defines. The queries run
 * as the HTTP service runs them (GenSPARQL.createQueryExecution with a MockLLMProvider). Without
 * a SIM_TEXT in the context the engine uses the mock's embeddings, so "similar" means equal up
 * to case here.
 */
class GenOpPaperShapesTest {

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
     * The data of GenOpGroupSemanticsTest: Argentina and Mexico in Group A, France in Group B,
     * and one player of each team.
     */
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

    // ---- An element of Qi that contains a GENOP of its own ----

    @Test
    void aNestedGroupWithABaseModeGenopIsQ1AndJoinedBySimilarity() {
        // Q1 is the nested group, translated on its own: the three teams, each with the
        // generated group name. It is joined with the generated team names by similarity on
        // ?team, the output of G1.
        assertEquals(List.of(
                        row("grp", "Group A", "team", WD + "Q164089"),
                        row("grp", "Group A", "team", WD + "Q47774"),
                        row("grp", "Group A", "team", WD + "Q79800")),
                run("GENOP(\"List the teams.\", (?team), " + M + ") "
                                + "{ ?team a ex:Team . GENOP(\"List the groups.\", (?grp), " + M + ") }",
                        teamList().withResponse("List the groups", "[\"Group A\"]")));
        assertEquals(2, calls());
    }

    @Test
    void aNestedGroupWithAContextModeGenopIsQ1AndJoinedBySimilarity() {
        // The nested group binds ?tn itself, so its GENOP asks for the coach of each team of
        // the graph, and the result is joined with the generated team names on ?team.
        List<Map<String, String>> rows = run("GENOP(\"List the teams.\", (?team), " + M + ") "
                        + "{ ?team a ex:Team . ?team rdfs:label ?tn . "
                        + "GENOP(\"Who coaches {?tn}?\", (?coach), " + M + ") }",
                teamList().withResponse("Who coaches", "[\"Someone\"]"));
        assertEquals(List.of(
                        row("coach", "Someone", "team", WD + "Q164089", "tn", "Mexico"),
                        row("coach", "Someone", "team", WD + "Q47774", "tn", "France"),
                        row("coach", "Someone", "team", WD + "Q79800", "tn", "Argentina")),
                rows);
        assertEquals(4, calls());
    }

    // ---- Two GENOPs with the same output variable ----

    @Test
    void aLaterGenopWithTheSameOutputExtendsTheSolutionsOfTheEarlierOne() {
        // T1 holds the names of G1. G2 extends each of them when one of its names is similar
        // to the bound name and keeps that name, and Q2 then maps the names to the teams.
        assertEquals(List.of(row("team", WD + "Q164089"), row("team", WD + "Q47774"),
                        row("team", WD + "Q79800")),
                run("GENOP(\"List the teams.\", (?team), " + M + ") "
                                + "GENOP(\"Name the teams again.\", (?team), " + M + ") ?team a ex:Team",
                        teamList()));
        assertEquals(2, calls());
    }

    @Test
    void aLaterGenopWithTheSameOutputKeepsOnlyTheNamesItAlsoGenerates() {
        // G2 names Mexico only, so only the solution of T1 that binds a name similar to it
        // survives.
        assertEquals(List.of(row("team", WD + "Q164089")),
                run("GENOP(\"List the teams.\", (?team), " + M + ") "
                                + "GENOP(\"Name the teams again.\", (?team), " + M + ") ?team a ex:Team",
                        teamList().withResponse("again", "[\"mexico\"]")));
    }

    // ---- OPTIONAL with a base-mode GENOP whose output the required part binds ----

    @Test
    void aBaseModeGenopInTheOptionalPartComparesTheBoundOutputBySimilarity() {
        // B is evaluated for each solution of A. The GENOP extends a solution when one of its
        // names is similar to the label of the bound team, and the BIND marks the team. A
        // solution without an extension is kept unchanged. The prompt is sent once.
        assertEquals(List.of(
                        row("inA", "true", "team", WD + "Q164089"),
                        row("inA", "true", "team", WD + "Q79800"),
                        row("team", WD + "Q47774")),
                run("?team a ex:Team OPTIONAL { GENOP(\"List the teams in Group A.\", (?team), " + M
                                + ") BIND(true AS ?inA) }",
                        new MockLLMProvider().withDefaultResponse("[\"argentina\", \"Mexico\"]")));
        assertEquals(1, calls());
    }

    // ---- A comparison of a generated value inside ||, && and ! ----

    private static Context jaccard() {
        Context c = ARQ.getContext().copy();
        c.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
        return c;
    }

    /** The position of each player, written as the model writes it: capitalized. */
    private static MockLLMProvider positions() {
        return new MockLLMProvider().withResponse("Lionel", "[\"Forward\"]")
                .withResponse("Kylian", "[\"Defender\"]").withResponse("Raul", "[\"Goalkeeper\"]");
    }

    /** The names of the players whose generated position passes the filter. */
    private List<String> playersWhere(String filter) {
        List<String> names = new ArrayList<>();
        for (Map<String, String> r : run("?p a ex:Athlete . ?p rdfs:label ?name . "
                + "GENOP(\"Which position does {?name} play?\", (?pos), " + M + ") FILTER(" + filter + ")",
                positions(), jaccard())) {
            names.add(r.get("name"));
        }
        return names;
    }

    @Test
    void equalsAndNotEqualsCompareAGeneratedValueBySimilarity() {
        // Jaccard compares the lower-case word sets, so "Defender" is similar to "defender".
        assertEquals(List.of("Kylian Mbappe"), playersWhere("?pos = \"defender\""));
        assertEquals(List.of("Kylian Mbappe"), playersWhere("\"defender\" = ?pos"));
        assertEquals(List.of("Lionel Messi", "Raul Jimenez"), playersWhere("?pos != \"defender\""));
    }

    @Test
    void aDisjunctionOfComparisonsComparesEachBySimilarity() {
        assertEquals(List.of("Kylian Mbappe", "Raul Jimenez"),
                playersWhere("?pos = \"defender\" || ?pos = \"goalkeeper\""));
    }

    @Test
    void aNegatedComparisonIsTheOppositeOfTheComparison() {
        assertEquals(List.of("Lionel Messi", "Raul Jimenez"), playersWhere("!(?pos = \"defender\")"));
    }

    @Test
    void aConjunctionWithAnOrdinaryConditionCombinesBoth() {
        assertEquals(List.of("Lionel Messi"),
                playersWhere("?pos = \"forward\" && STRSTARTS(?name, \"L\")"));
        assertEquals(List.of(),
                playersWhere("?pos = \"forward\" && STRSTARTS(?name, \"K\")"));
    }

    @Test
    void anUnboundOperandIsAnErrorThatTheLogicalOperatorsCombineAsSparqlDoes() {
        // error || true is true, error || false is an error, and !error is an error; a filter
        // keeps a solution only when its expression is true.
        assertEquals(List.of("Kylian Mbappe"), playersWhere("?pos = ?unbound || ?pos = \"defender\""));
        assertEquals(List.of(), playersWhere("!(?pos = ?unbound)"));
        assertEquals(List.of(), playersWhere("?pos != ?unbound"));
    }
}
