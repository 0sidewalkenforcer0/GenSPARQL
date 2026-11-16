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
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.GenSPARQLConstants;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default threshold θ0 of Section 2.1 of the paper is an engine parameter: a GENOP without a
 * threshold of its own takes it, it is 0.85 unless the system property gensparql.theta0 sets
 * another value, and an invalid value keeps 0.85. The query has the shape of Figure 1(b): a
 * base-mode GENOP that a graph pattern maps by similarity. The Jaccard similarity of the generated
 * name and the label is 6/7 ≈ 0.857, so the name maps at 0.85 and not at 0.9.
 */
class DefaultThresholdTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            """;
    private static final String M = "<model:mock:test>";

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("gensparql.theta0");
        GenSPARQLConfig.reset();
    }

    private static Model data() {
        Model m = ModelFactory.createDefaultModel();
        Resource prize = m.createResource("http://example.org/economics");
        m.add(prize, RDF.type, m.createResource("http://example.org/Prize"))
                .add(prize, RDFS.label, "Sveriges Riksbank Prize in Economic Sciences");
        return m;
    }

    /** The number of prizes that the generated name maps to. */
    private static int mapped(String threshold) {
        LLMProviderRegistry.setDefault(new MockLLMProvider()
                .withDefaultResponse("[\"Sveriges Riksbank Prize in the Economic Sciences\"]"));
        Context cxt = ARQ.getContext().copy();
        cxt.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
        String options = threshold == null ? "" : ", " + threshold;
        int rows = 0;
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + "SELECT ?p WHERE { ?p a ex:Prize . "
                        + "GENOP(\"List the prizes.\", (?p), " + M + options + ") }"),
                DatasetFactory.wrap(data()), cxt)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                rows++;
            }
        }
        return rows;
    }

    @Test
    void theDefaultIs085() {
        assertEquals(0.85, GenSPARQLConfig.getDefaultThreshold());
        assertEquals(1, mapped(null));
    }

    @Test
    void aDefaultOf09AppliesToAGenopWithoutAThreshold() {
        GenSPARQLConfig.setDefaultThreshold(0.9);
        assertEquals(0, mapped(null));
        // A threshold of the GENOP itself takes precedence over the default.
        assertEquals(1, mapped("0.85"));
    }

    @Test
    void theSystemPropertySetsTheDefault() {
        System.setProperty("gensparql.theta0", "0.9");
        GenSPARQLConfig.loadFromSystemProperties();
        assertEquals(0.9, GenSPARQLConfig.getDefaultThreshold());
        assertEquals(0, mapped(null));
    }

    @Test
    void anInvalidSystemPropertyKeepsTheDefault() {
        for (String invalid : new String[] {"high", "1.5", "-0.1", "NaN"}) {
            System.setProperty("gensparql.theta0", invalid);
            GenSPARQLConfig.loadFromSystemProperties();
            assertEquals(0.85, GenSPARQLConfig.getDefaultThreshold(), invalid);
        }
    }

    @Test
    void theJavaInterfaceSetsTheDefaultAsWell() {
        GenSPARQL.setSimilarityThreshold(0.9);
        assertEquals(0.9, GenSPARQL.getSimilarityThreshold());
        assertEquals(0, mapped(null));
    }
}
