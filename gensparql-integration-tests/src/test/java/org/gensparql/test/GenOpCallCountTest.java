package org.gensparql.test;

import org.apache.jena.query.QueryExecution;
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

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The LLM requests of a query are counted in the counter that the caller puts into the
 * context. The counts also pin down where GENOP is evaluated: after the patterns of its
 * group that do not mention its output variables, wherever the query writes it.
 */
class GenOpCallCountTest {

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

    /** Two Argentine players and one French player. */
    private static Model squads() {
        Model m = ModelFactory.createDefaultModel();
        Resource athlete = m.createResource("http://example.org/Athlete");
        Resource argentina = team(m, "Q79800", "Argentina");
        Resource france = team(m, "Q47774", "France");
        player(m, athlete, "Q615", "Lionel Messi", argentina);
        player(m, athlete, "Q28058687", "Cristian Romero", argentina);
        player(m, athlete, "Q21621995", "Kylian Mbappé", france);
        return m;
    }

    private static Resource team(Model m, String qid, String label) {
        Resource t = m.createResource("http://www.wikidata.org/entity/" + qid);
        m.add(t, RDF.type, m.createResource("http://example.org/Team"));
        m.add(t, RDFS.label, label);
        return t;
    }

    private static void player(Model m, Resource athlete, String qid, String label, Resource team) {
        Resource p = m.createResource("http://www.wikidata.org/entity/" + qid);
        m.add(p, RDF.type, athlete);
        m.add(p, RDFS.label, label);
        m.add(p, m.createProperty("http://example.org/playsFor"), team);
    }

    /** Runs the query and returns the number of rows; the counter receives the LLM requests. */
    private static int run(String where, AtomicLong counter) {
        LLMProviderRegistry.setDefault(new MockLLMProvider().withDefaultResponse("Lionel Messi"));
        String query = PREFIXES + "SELECT * WHERE { " + where + " }";
        try (QueryExecution qe = QueryExecution.create()
                .query(GenSPARQLQueryFactory.create(query))
                .model(squads())
                .set(GenSPARQL.LLM_CALL_COUNTER, counter)
                .build()) {
            ResultSet rs = qe.execSelect();
            int rows = 0;
            while (rs.hasNext()) {
                rs.next();
                rows++;
            }
            return rows;
        }
    }

    @Test
    void contextModeSendsOnePromptPerSolutionThatReachesGenop() {
        AtomicLong calls = new AtomicLong();
        int rows = run("?p ex:playsFor ?t . ?t rdfs:label \"Argentina\" . ?p rdfs:label ?n . "
                + "GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>)", calls);
        assertEquals(2, rows);
        assertEquals(2, calls.get());
    }

    @Test
    void genopWrittenFirstStillWaitsForTheRestrictingPatterns() {
        AtomicLong calls = new AtomicLong();
        int rows = run("GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>) "
                + "?p rdfs:label ?n . ?p ex:playsFor ?t . ?t rdfs:label \"Argentina\"", calls);
        assertEquals(2, rows);
        assertEquals(2, calls.get(), "the French player never reaches the LLM");
    }

    @Test
    void filterOnTheRestrictingPatternsRunsBeforeGenop() {
        AtomicLong calls = new AtomicLong();
        int rows = run("?p ex:playsFor ?t ; rdfs:label ?n . ?t rdfs:label ?tn . FILTER(?tn = \"France\") "
                + "GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>)", calls);
        assertEquals(1, rows);
        assertEquals(1, calls.get(), "only the French player reaches the LLM");
    }

    @Test
    void filterOnTheGeneratedValueRunsAfterGenop() {
        AtomicLong calls = new AtomicLong();
        int rows = run("?p ex:playsFor ?t ; rdfs:label ?n . "
                + "GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>) FILTER(?pos = \"Forward\")", calls);
        assertEquals(0, rows, "the mock answers Lionel Messi, never Forward");
        assertEquals(3, calls.get());
    }

    @Test
    void filterMentioningTheGeneratedValueStaysAfterGenop() {
        AtomicLong calls = new AtomicLong();
        int rows = run("?p ex:playsFor ?t ; rdfs:label ?n . ?t rdfs:label ?tn . "
                + "GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>) "
                + "FILTER(?tn = \"France\" || ?pos = \"Lionel Messi\")", calls);
        assertEquals(3, rows, "every player passes: the mock answers Lionel Messi for all");
        assertEquals(3, calls.get());
    }

    @Test
    void filterWithExistsStaysAfterGenop() {
        AtomicLong calls = new AtomicLong();
        int rows = run("?p ex:playsFor ?t ; rdfs:label ?n . "
                + "FILTER EXISTS { ?t rdfs:label \"France\" } "
                + "GENOP(\"Position of {?n}?\", (?pos), <model:mock:test>)", calls);
        assertEquals(1, rows);
        assertEquals(3, calls.get(), "an EXISTS filter is left at the end of the group");
    }

    @Test
    void baseModeWithAnEmptyPatternSendsItsPromptOnce() {
        AtomicLong calls = new AtomicLong();
        int rows = run("?p a ex:Athlete . "
                + "GENOP(\"List footballers who have won the World Cup.\", (?p), <model:mock:test>, 0.85)", calls);
        assertEquals(1, rows, "the generated name maps to wd:Q615");
        assertEquals(1, calls.get());
    }

    @Test
    void baseModeSendsItsPromptOnceAndExtendsEverySolution() {
        AtomicLong calls = new AtomicLong();
        int rows = run("?t a ex:Team . GENOP(\"Name a footballer.\", (?x), <model:mock:test>)", calls);
        assertEquals(2, rows, "the generated value extends both teams");
        assertEquals(1, calls.get(), "the constant prompt is sent once, not once per team");
    }
}
