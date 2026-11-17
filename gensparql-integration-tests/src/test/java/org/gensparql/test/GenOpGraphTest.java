package org.gensparql.test;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A GENOP inside GRAPH: the group inside is translated as every group is (Section 2.1) and
 * evaluated over the named graph, whose labels the similarity join reads (Section 2.2). Before,
 * ARQ evaluated the GRAPH and joined generated names with the IRIs by equality, so no name
 * mapped to an entity.
 *
 * <p>Jaccard similarities: the generated name "Sveriges Riksbank Prize in the Economic Sciences"
 * has 6/7 = 0.86 to the label "Sveriges Riksbank Prize in Economic Sciences" and 1/7 to "Economics".
 */
class GenOpGraphTest {

    private static final String PREFIXES = "PREFIX ex: <http://example.org/>\n";
    private static final String GENOP = "GENOP(\"List the prizes.\", (?p), <model:mock:test>, 0.85)";
    private static MockLLMProvider llm;

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @BeforeEach
    void provider() {
        llm = new MockLLMProvider().withDefaultResponse(
                "[\"Sveriges Riksbank Prize in the Economic Sciences\", \"Nobel Prize in Physics\"]");
        LLMProviderRegistry.setDefault(llm);
    }

    private static Model prize(String iri, String label) {
        Model m = ModelFactory.createDefaultModel();
        Resource r = m.createResource(iri);
        m.add(r, RDF.type, m.createResource("http://example.org/Prize")).add(r, RDFS.label, label);
        return m;
    }

    private static final String ECONOMICS = "http://example.org/economics";
    private static final String FULL = "Sveriges Riksbank Prize in Economic Sciences";

    private static List<String> rows(String body, Dataset ds) {
        Context cxt = ARQ.getContext().copy();
        cxt.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
        List<String> out = new ArrayList<>();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + body), ds, cxt)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                out.add(s.contains("g") ? s.get("g") + " " + s.get("p") : String.valueOf(s.get("p")));
            }
        }
        out.sort(null);
        return out;
    }

    @Test
    void aNamedGraphMapsLikeTheDefaultGraph() {
        Dataset dflt = DatasetFactory.wrap(prize(ECONOMICS, FULL));
        Dataset named = DatasetFactory.create();
        named.addNamedModel("http://example.org/g", prize(ECONOMICS, FULL));
        assertEquals(List.of(ECONOMICS), rows("SELECT ?p WHERE { ?p a ex:Prize . " + GENOP + " }", dflt));
        assertEquals(List.of(ECONOMICS),
                rows("SELECT ?p WHERE { GRAPH ex:g { ?p a ex:Prize . " + GENOP + " } }", named));
    }

    @Test
    void theLabelsComeFromTheNamedGraph() {
        String query = "SELECT ?p WHERE { GRAPH ex:g { ?p a ex:Prize . " + GENOP + " } }";
        Dataset fullInNamed = DatasetFactory.create(ModelFactory.createDefaultModel()
                .add(prize(ECONOMICS, "Economics")));
        fullInNamed.addNamedModel("http://example.org/g", prize(ECONOMICS, FULL));
        assertEquals(List.of(ECONOMICS), rows(query, fullInNamed));

        Dataset shortInNamed = DatasetFactory.create(ModelFactory.createDefaultModel()
                .add(prize(ECONOMICS, FULL)));
        shortInNamed.addNamedModel("http://example.org/g", prize(ECONOMICS, "Economics"));
        assertEquals(List.of(), rows(query, shortInNamed));
    }

    @Test
    void aGraphVariableRangesOverTheNamedGraphs() {
        Dataset ds = DatasetFactory.create();
        ds.addNamedModel("http://example.org/g1", prize(ECONOMICS, FULL));
        ds.addNamedModel("http://example.org/g2", prize("http://example.org/physics", "Nobel Prize in Physics"));
        assertEquals(List.of("http://example.org/g1 " + ECONOMICS,
                        "http://example.org/g2 http://example.org/physics"),
                rows("SELECT ?g ?p WHERE { GRAPH ?g { ?p a ex:Prize . " + GENOP + " } }", ds));
        // A base-mode GENOP sends one prompt per query, also over several graphs.
        assertEquals(1, llm.getRequestHistory().size());
        // A graph name bound before the GRAPH restricts it to that graph.
        assertEquals(List.of("http://example.org/g2 http://example.org/physics"),
                rows("SELECT ?g ?p WHERE { VALUES ?g { ex:g2 } GRAPH ?g { ?p a ex:Prize . " + GENOP + " } }", ds));
    }

    @Test
    void aGraphThatTheDatasetLacksHasNoSolution() {
        Dataset ds = DatasetFactory.create();
        ds.addNamedModel("http://example.org/g", prize(ECONOMICS, FULL));
        assertEquals(List.of(), rows("SELECT ?p WHERE { GRAPH ex:none { ?p a ex:Prize . " + GENOP + " } }", ds));
    }
}
