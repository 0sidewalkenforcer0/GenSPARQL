package org.gensparql.test;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.engine.GenSPARQLConstants;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every GENOP has its own threshold θi, its argument or the default θ0 (Section 2.1:
 * Ti = Qi ⋈θi GENOPθi(...)). A GENOP without a threshold therefore takes θ0, also when an
 * earlier GENOP with a threshold generates the same variable. Before, the thresholds were kept
 * per variable for the whole query, and such a GENOP took the threshold of the earlier one.
 *
 * <p>All similarities are Jaccard similarities of lower-case word sets.
 */
class GenOpThresholdScopeTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;
    private static final String M = "<model:mock:test>";

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    private static Model prize() {
        Model m = ModelFactory.createDefaultModel();
        Resource prize = m.createResource("http://example.org/economics");
        m.add(prize, RDF.type, m.createResource("http://example.org/Prize"))
                .add(prize, RDFS.label, "Sveriges Riksbank Prize in Economic Sciences");
        return m;
    }

    private static int rows(String body) {
        Context cxt = ARQ.getContext().copy();
        cxt.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
        int n = 0;
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + body), DatasetFactory.wrap(prize()), cxt)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                n++;
            }
        }
        return n;
    }

    // Base mode. The generated name has the similarity 4/6 = 0.67 to the label of the prize, so it
    // maps at 0.5 and not at θ0 = 0.85. The second GENOP joins with the prize, so its θ decides.

    @Test
    void aGenopWithoutAThresholdTakesTheDefaultNotTheThresholdOfAnEarlierGenop() {
        LLMProviderRegistry.setDefault(new MockLLMProvider()
                .withDefaultResponse("[\"Riksbank Prize Economic Sciences\"]"));
        String implicit = "SELECT ?p WHERE { ?p a ex:Prize . GENOP(\"First: list the prizes.\", (?p), "
                + M + ", 0.5) GENOP(\"Second: list the prizes.\", (?p), " + M + ") }";
        String explicit = "SELECT ?p WHERE { ?p a ex:Prize . GENOP(\"First: list the prizes.\", (?p), "
                + M + ", 0.5) GENOP(\"Second: list the prizes.\", (?p), " + M + ", 0.85) }";
        assertEquals(rows(explicit), rows(implicit));
        assertEquals(0, rows(implicit));
    }

    @Test
    void theThresholdOfTheCurrentGenopDecidesInEitherOrder() {
        LLMProviderRegistry.setDefault(new MockLLMProvider()
                .withDefaultResponse("[\"Riksbank Prize Economic Sciences\"]"));
        assertEquals(1, rows("SELECT ?p WHERE { ?p a ex:Prize . GENOP(\"First: list the prizes.\", (?p), "
                + M + ") GENOP(\"Second: list the prizes.\", (?p), " + M + ", 0.5) }"));
    }

    // Context mode. The second GENOP compares its answer "Economic Sciences" with the value
    // "Economic Sciences award" that the first one bound: similarity 2/3 = 0.67.

    @Test
    void anAlreadyBoundOutputIsComparedWithTheThresholdOfTheGenopThatComparesIt() {
        LLMProviderRegistry.setDefault(new MockLLMProvider()
                .withResponse("First:", "[\"Economic Sciences award\"]")
                .withResponse("Second:", "[\"Economic Sciences\"]"));
        String implicit = "SELECT ?c WHERE { ?p a ex:Prize ; rdfs:label ?name . "
                + "GENOP(\"First: the field of {?name}?\", (?c), " + M + ", 0.5) "
                + "GENOP(\"Second: the field of {?name}?\", (?c), " + M + ") }";
        String explicit = implicit.replace(M + ") }", M + ", 0.85) }");
        String lenient = implicit.replace(M + ") }", M + ", 0.5) }");
        assertEquals(rows(explicit), rows(implicit));
        assertEquals(0, rows(implicit));
        assertEquals(1, rows(lenient));
    }

    // FILTER (Section 2.2): ?x = t compares the generated value with t at the threshold of the
    // GENOP that generated it. "Economic Sciences" against "Economic Sciences award": 0.67.

    @Test
    void aFilterComparesAGeneratedValueAtTheThresholdOfItsGenop() {
        LLMProviderRegistry.setDefault(new MockLLMProvider()
                .withDefaultResponse("[\"Economic Sciences\"]"));
        assertEquals(1, rows("SELECT ?x WHERE { GENOP(\"List the fields.\", (?x), " + M + ", 0.5) "
                + "FILTER(?x = \"Economic Sciences award\") }"));
        assertEquals(0, rows("SELECT ?x WHERE { GENOP(\"List the fields.\", (?x), " + M + ") "
                + "FILTER(?x = \"Economic Sciences award\") }"));
    }

    @Test
    void aFilterOnAVariableOfTwoGenopsTakesTheThresholdOfTheFirst() {
        // The second GENOP generates the same value (similarity 1), so the solution keeps the value
        // of the first one, and the filter compares it at the first one's threshold.
        LLMProviderRegistry.setDefault(new MockLLMProvider()
                .withDefaultResponse("[\"Economic Sciences\"]"));
        assertEquals(1, rows("SELECT ?x WHERE { GENOP(\"First: list the fields.\", (?x), " + M + ", 0.5) "
                + "GENOP(\"Second: list the fields.\", (?x), " + M + ") "
                + "FILTER(?x = \"Economic Sciences award\") }"));
        assertEquals(0, rows("SELECT ?x WHERE { GENOP(\"First: list the fields.\", (?x), " + M + ") "
                + "GENOP(\"Second: list the fields.\", (?x), " + M + ", 0.5) "
                + "FILTER(?x = \"Economic Sciences award\") }"));
    }
}
