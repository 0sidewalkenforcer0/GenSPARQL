package org.gensparql.test;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The query forms other than SELECT.
 *
 * <p>CONSTRUCT did not parse at all: its template productions reused the graph-pattern triple
 * rules, which write into the group stack, and a template is read before any group is pushed, so
 * the first triple failed on a null group. Nothing set the template on the query either, so the
 * form was unimplemented rather than merely broken. Execution of all three forms then threw
 * "not yet supported".
 *
 * <p>Results are checked against Jena wherever the query has no GENOP in it.
 */
@DisplayName("query forms")
public class QueryFormsTest {

    private static final String TTL = """
            @prefix ex: <http://example.org/> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            ex:a a ex:T ; rdfs:label "A" .
            ex:b a ex:T ; rdfs:label "B" .
            """;

    private static final String PREFIXES =
            "PREFIX ex: <http://example.org/>\n"
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n";

    private static final String GENOP = "GENOP(\"say {?l}\", (?g), <model:mock:t>)";

    private static Model kg;
    private MockLLMProvider mock;

    @BeforeAll
    static void load() {
        GenSPARQL.init();
        kg = ModelFactory.createDefaultModel();
        RDFDataMgr.read(kg, new StringReader(TTL), null, Lang.TURTLE);
    }

    @BeforeEach
    void setUp() {
        mock = new MockLLMProvider().withDefaultResponse("GEN");
        LLMProviderRegistry.setDefault(mock);
        GenSPARQLConfig.reset();
        GenSPARQLConfig.setBatchingEnabled(false);
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private Query parse(String body) {
        return GenSPARQLQueryFactory.create(PREFIXES + body);
    }

    private Model construct(String body) {
        mock.clearHistory();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(parse(body), kg)) {
            return qe.execConstruct();
        }
    }

    private boolean ask(String body) {
        mock.clearHistory();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(parse(body), kg)) {
            return qe.execAsk();
        }
    }

    private Model describe(String body) {
        try (QueryExecution qe = GenSPARQL.createQueryExecution(parse(body), kg)) {
            return qe.execDescribe();
        }
    }

    /** The same query run by Jena, for the cases that have no GENOP. */
    private Model jenaConstruct(String body) {
        try (QueryExecution qe = QueryExecutionFactory.create(
                QueryFactory.create(PREFIXES + body), kg)) {
            return qe.execConstruct();
        }
    }

    @Test
    @DisplayName("CONSTRUCT parses and carries its template")
    void constructParses() {
        Query q = parse("CONSTRUCT { ?s ex:copy ?l } WHERE { ?s rdfs:label ?l }");

        assertTrue(q.isConstructType());
        assertNotNull(q.getConstructTemplate(), "the template must be attached to the query");
        assertEquals(1, q.getConstructTemplate().getTriples().size());
        assertNotNull(q.getQueryPattern());
    }

    @Test
    @DisplayName("CONSTRUCT agrees with Jena")
    void constructMatchesJena() {
        String body = "CONSTRUCT { ?s ex:copy ?l } WHERE { ?s rdfs:label ?l }";
        Model mine = construct(body);

        assertEquals(2, mine.size());
        assertTrue(mine.isIsomorphicWith(jenaConstruct(body)));
    }

    @Test
    @DisplayName("CONSTRUCT WHERE uses the pattern as the template")
    void constructWhereShortForm() {
        String body = "CONSTRUCT WHERE { ?s rdfs:label ?l }";
        Model mine = construct(body);

        assertEquals(2, mine.size());
        assertTrue(mine.isIsomorphicWith(jenaConstruct(body)),
                "the short form must produce the matched triples themselves");
    }

    @Test
    @DisplayName("CONSTRUCT builds triples from generated values")
    void constructOverGenOp() {
        Model m = construct("CONSTRUCT { ?s ex:gen ?g } WHERE { ?s rdfs:label ?l . " + GENOP + " }");

        assertEquals(2, m.size());
        assertEquals(2, mock.getRequestHistory().size(), "one call per matched row");
        assertTrue(m.listObjectsOfProperty(
                        m.createProperty("http://example.org/gen")).toList().stream()
                        .allMatch(n -> n.asLiteral().getString().equals("GEN")),
                "the generated value must reach the constructed triples");
    }

    @Test
    @DisplayName("a property path is rejected in a CONSTRUCT template")
    void pathInTemplateRejected() {
        // A template is a set of triples. Accepting a path here and dropping it would lose part
        // of the output without saying so.
        assertThrows(RuntimeException.class,
                () -> parse("CONSTRUCT { ?s ex:p/ex:q ?o } WHERE { ?s rdfs:label ?o }"));
    }

    @Test
    @DisplayName("ASK answers both ways")
    void askBothWays() {
        assertTrue(ask("ASK WHERE { ?s a ex:T }"));
        assertFalse(ask("ASK WHERE { ?s a ex:NoSuchType }"));
    }

    @Test
    @DisplayName("ASK stops at the first solution")
    void askIsLazy() {
        assertTrue(ask("ASK WHERE { ?s rdfs:label ?l . " + GENOP + " }"));
        assertEquals(1, mock.getRequestHistory().size(),
                "two rows match, but one is enough to answer, so only one call is made");
    }

    @Test
    @DisplayName("DESCRIBE covers named and bound resources")
    void describe() {
        assertEquals(4, describe("DESCRIBE ?s WHERE { ?s a ex:T }").size(),
                "both subjects, two triples each");
        assertEquals(2, describe("DESCRIBE ex:a").size(),
                "an IRI named directly needs no pattern");
    }
}
