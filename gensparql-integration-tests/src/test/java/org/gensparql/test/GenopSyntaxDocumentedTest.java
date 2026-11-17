package org.gensparql.test;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The GENOP syntax as the README gives it: GENOP(prompt, (?out1, ..., ?outk), model [, θ]) with
 * θ in [0, 1], θ0 = 0.85 without it, and placeholders {?var} in the prompt.
 */
class GenopSyntaxDocumentedTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    private static int rows(String body) {
        Model m = ModelFactory.createDefaultModel();
        Resource prize = m.createResource("http://example.org/economics");
        m.add(prize, RDF.type, m.createResource("http://example.org/Prize"))
                .add(prize, RDFS.label, "Sveriges Riksbank Prize in Economic Sciences");
        Context cxt = ARQ.getContext().copy();
        cxt.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
        int n = 0;
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + body), DatasetFactory.wrap(m), cxt)) {
            var rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                n++;
            }
        }
        return n;
    }

    @Test
    void theThresholdIsOptionalAndDefaultsToTheta0() {
        // Jaccard 6/7 = 0.86: maps at θ0 = 0.85 and not at 0.9.
        LLMProviderRegistry.setDefault(new MockLLMProvider()
                .withDefaultResponse("[\"Sveriges Riksbank Prize in the Economic Sciences\"]"));
        String q = "SELECT ?p WHERE { ?p a ex:Prize . GENOP(\"List the prizes.\", (?p), <model:mock:test>%s) }";
        assertEquals(1, rows(String.format(q, "")));
        assertEquals(1, rows(String.format(q, ", 0.85")));
        assertEquals(0, rows(String.format(q, ", 0.9")));
    }

    @Test
    void aThresholdOutsideZeroToOneIsRejected() {
        LLMProviderRegistry.setDefault(new MockLLMProvider().withDefaultResponse("[\"x\"]"));
        assertThrows(RuntimeException.class, () -> rows(
                "SELECT ?p WHERE { ?p a ex:Prize . GENOP(\"List the prizes.\", (?p), <model:mock:test>, 1.5) }"));
    }

    @Test
    void placeholdersAndSeveralOutputVariables() {
        LLMProviderRegistry.setDefault(new MockLLMProvider()
                .withDefaultResponse("[{\"field\": \"Economics\", \"year\": \"1969\"}]"));
        assertEquals(1, rows("SELECT * WHERE { ?p a ex:Prize ; rdfs:label ?name . "
                + "GENOP(\"Field and first year of {?name}?\", (?field, ?year), <model:mock:test>) }"));
    }
}
