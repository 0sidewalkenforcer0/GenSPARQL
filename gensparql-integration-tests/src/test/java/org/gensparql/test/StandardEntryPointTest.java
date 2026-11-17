package org.gensparql.test;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * After {@code GenSPARQL.init()}, Jena's own query execution reaches GenSPARQL through
 * GenSPARQLQueryEngineFactory. It recognises a GENOP wherever the query may contain one and
 * compiles the query as {@code GenSPARQL.createQueryExecution} does, solution modifiers
 * included. Before, it missed a GENOP in MINUS, GRAPH or a sub-select, which Jena then rejected,
 * and it applied ORDER BY after the projection and ignored GROUP BY and aggregates.
 */
class StandardEntryPointTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;
    private static final String GENOP = "GENOP(\"List the prizes.\", (?p), <model:mock:test>)";

    @BeforeAll
    static void init() {
        GenSPARQL.init();
        // Jaccard: 6/7 = 0.86 for the first name and 1 for the second, both at least θ0 = 0.85.
        LLMProviderRegistry.setDefault(new MockLLMProvider().withDefaultResponse(
                "[\"Sveriges Riksbank Prize in the Economic Sciences\", \"Nobel Prize in Physics\"]"));
    }

    private static Model prizes() {
        Model m = ModelFactory.createDefaultModel();
        Resource type = m.createResource("http://example.org/Prize");
        Resource economics = m.createResource("http://example.org/economics");
        Resource physics = m.createResource("http://example.org/physics");
        m.add(economics, RDF.type, type).add(economics, RDFS.label, "Sveriges Riksbank Prize in Economic Sciences")
                .addLiteral(economics, m.createProperty("http://example.org/year"), 1969);
        m.add(physics, RDF.type, type).add(physics, RDFS.label, "Nobel Prize in Physics")
                .addLiteral(physics, m.createProperty("http://example.org/year"), 1901);
        return m;
    }

    private static Context jaccard() {
        Context cxt = ARQ.getContext().copy();
        cxt.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
        return cxt;
    }

    private static List<String> rows(ResultSet rs) {
        List<String> out = new ArrayList<>();
        while (rs.hasNext()) {
            QuerySolution s = rs.next();
            TreeMap<String, String> row = new TreeMap<>();
            for (Iterator<String> it = s.varNames(); it.hasNext(); ) {
                String v = it.next();
                row.put(v, String.valueOf(s.get(v)));
            }
            out.add(row.toString());
        }
        return out;
    }

    /** The rows of Jena's own execution, which selects GenSPARQL through the engine factory. */
    private static List<String> jena(String body, Dataset ds) {
        Query q = GenSPARQLQueryFactory.create(PREFIXES + body);
        try (QueryExecution qe = QueryExecution.create().query(q).dataset(ds).context(jaccard()).build()) {
            return rows(qe.execSelect());
        }
    }

    private static List<String> gensparql(String body, Dataset ds) {
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + body), ds, jaccard())) {
            return rows(qe.execSelect());
        }
    }

    private static List<String> sameOnBothEntryPoints(String body, Dataset ds, boolean ordered) {
        List<String> viaJena = jena(body, ds);
        List<String> viaGenSPARQL = gensparql(body, ds);
        if (!ordered) {
            viaJena.sort(null);
            viaGenSPARQL.sort(null);
        }
        assertEquals(viaGenSPARQL, viaJena, body);
        return viaJena;
    }

    @Test
    void aGenopInAGroupMinusSubSelectOrGraph() {
        Dataset ds = DatasetFactory.wrap(prizes());
        assertEquals(2, sameOnBothEntryPoints("SELECT ?p WHERE { ?p a ex:Prize . " + GENOP + " }", ds, false).size());
        sameOnBothEntryPoints("SELECT ?p WHERE { ?p a ex:Prize . MINUS { " + GENOP + " } }", ds, false);
        assertEquals(2, sameOnBothEntryPoints(
                "SELECT ?p WHERE { { SELECT ?p WHERE { ?p a ex:Prize . " + GENOP + " } } }", ds, false).size());
        Dataset named = DatasetFactory.create();
        named.addNamedModel("http://example.org/g", prizes());
        sameOnBothEntryPoints("SELECT ?p WHERE { GRAPH ex:g { ?p a ex:Prize . " + GENOP + " } }", named, false);
    }

    @Test
    void solutionModifiersInTheOrderOfSparql() {
        Dataset ds = DatasetFactory.wrap(prizes());
        assertEquals(List.of("{n=\"2\"^^xsd:integer}"), sameOnBothEntryPoints(
                "SELECT (COUNT(?p) AS ?n) WHERE { ?p a ex:Prize . " + GENOP + " }", ds, false));
        // Sorted by a variable that the query does not select: Physics (1901) before Economics (1969).
        assertEquals(List.of("{p=http://example.org/physics}", "{p=http://example.org/economics}"),
                sameOnBothEntryPoints("SELECT ?p WHERE { ?p a ex:Prize ; ex:year ?y . " + GENOP + " } ORDER BY ?y",
                        ds, true));
        assertEquals(1, sameOnBothEntryPoints(
                "SELECT DISTINCT ?t WHERE { ?p a ?t . " + GENOP + " }", ds, false).size());
    }
}
