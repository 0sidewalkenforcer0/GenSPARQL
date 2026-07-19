package org.gensparql.test;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.function.FunctionRegistry;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.function.GenSPARQLFunctions;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the {@code gen:*} extension functions.
 *
 * <p>Also serves as the regression guard that the functions are actually reachable: they are
 * auto-registered via {@link org.gensparql.function.GenSPARQLFunctionsSubsystem} on
 * {@code GenSPARQL.init()} (which triggers ARQ init). Before that wiring existed, writing
 * {@code gen:validate(...)} in a query resolved to nothing.
 */
public class GenFunctionsTest {

    private static final String GEN = "http://gensparql.org/function#";
    private static final String PREFIX = "PREFIX gen: <" + GEN + ">\n";

    private MockLLMProvider mock;

    @BeforeAll
    static void initEngine() {
        GenSPARQL.init();
    }

    @BeforeEach
    void setUp() {
        mock = new MockLLMProvider();
        LLMProviderRegistry.setDefault(mock);
    }

    private RDFNode evalBind(String expr, String var) {
        Query q = QueryFactory.create(PREFIX + "SELECT ?" + var + " WHERE { BIND(" + expr + " AS ?" + var + ") }");
        Model empty = ModelFactory.createDefaultModel();
        try (QueryExecution qe = QueryExecutionFactory.create(q, empty)) {
            ResultSet rs = qe.execSelect();
            assertTrue(rs.hasNext(), "expected exactly one solution row");
            QuerySolution sol = rs.next();
            return sol.get(var);
        }
    }

    @Test
    void functionsAutoRegisteredOnInit() {
        assertTrue(GenSPARQLFunctions.isRegistered(), "GenSPARQLFunctions should be registered after init");
        FunctionRegistry reg = FunctionRegistry.get();
        for (String fn : new String[]{"similarity", "approxEq", "embedding", "classify",
                "extract", "ground", "validate", "entail", "parseJSON", "tokenCost"}) {
            assertTrue(reg.isRegistered(GEN + fn), "gen:" + fn + " must be registered");
        }
    }

    // ---- gen:validate ---------------------------------------------------------------

    @Test
    void validateTreatsInvalidAsFalse() {
        // Regression: the word "invalid" contains the substring "valid" and must NOT be
        // scored as valid.
        mock.withResponse("VALUE_A", "invalid");
        RDFNode r = evalBind("gen:validate(\"VALUE_A\", \"anything\")", "v");
        assertFalse(r.asLiteral().getBoolean(), "'invalid' must map to false");
    }

    @Test
    void validateTreatsValidAsTrue() {
        mock.withResponse("VALUE_B", "valid");
        RDFNode r = evalBind("gen:validate(\"VALUE_B\", \"anything\")", "v");
        assertTrue(r.asLiteral().getBoolean(), "'valid' must map to true");
    }

    // ---- gen:classify ---------------------------------------------------------------

    @Test
    void classifyPrefersMostSpecificOverlappingLabel() {
        // Labels "cat" and "category" overlap; a non-exact response mentioning "category"
        // must resolve to "category", not to the shorter "cat".
        mock.withResponse("ANIMAL_TEXT", "This is a category.");
        RDFNode r = evalBind("gen:classify(\"ANIMAL_TEXT\", \"cat,category\")", "c");
        assertEquals("category", r.asLiteral().getString());
    }

    // ---- gen:ground -----------------------------------------------------------------

    @Test
    void groundReturnsIriNode() {
        mock.withResponse("PARIS_MENTION", "Q90");
        RDFNode r = evalBind("gen:ground(\"PARIS_MENTION\")", "e");
        assertTrue(r.isURIResource(), "gen:ground must return an IRI, not a string literal");
        assertEquals("http://www.wikidata.org/entity/Q90", r.asResource().getURI());
    }

    @Test
    void groundLeavesVariableUnboundWhenNoEntity() {
        mock.withResponse("NO_SUCH_ENTITY", "NONE");
        Query q = QueryFactory.create(PREFIX
                + "SELECT ?e WHERE { BIND(gen:ground(\"NO_SUCH_ENTITY\") AS ?e) }");
        Model empty = ModelFactory.createDefaultModel();
        try (QueryExecution qe = QueryExecutionFactory.create(q, empty)) {
            ResultSet rs = qe.execSelect();
            assertTrue(rs.hasNext());
            assertNull(rs.next().get("e"), "unresolved grounding must leave the var unbound");
        }
    }
}
