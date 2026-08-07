package org.gensparql.test;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Predicate position: variables and property paths.
 *
 * <p>The grammar used to accept only an IRI or {@code a} there, with a comment saying it was
 * simplified. That rejected {@code ?s ?p ?o}, which is ordinary SPARQL, and every property path
 * operator. The algebra generator also collected {@code TriplePath.asTriple()}, which returns
 * null for a genuine path, so a path that did parse would have been dropped from the pattern
 * and the query would have run as if it had never been written.
 *
 * <p>Each case is checked against Jena on the same data, since Jena is the reference for what
 * these constructs mean.
 */
@DisplayName("predicate position")
public class PropertyPathTest {

    private static final String TTL = """
            @prefix ex: <http://example.org/> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            ex:a  a ex:T ; rdfs:label "A" ; ex:next ex:b .
            ex:b  a ex:T ; rdfs:label "B" ; ex:next ex:c .
            ex:c  a ex:U ; rdfs:label "C" .
            ex:T  rdfs:subClassOf ex:Super .
            ex:U  rdfs:subClassOf ex:T .
            """;

    private static final String PREFIXES =
            "PREFIX ex: <http://example.org/>\n"
          + "PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n"
          + "PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n";

    private static Model kg;

    @BeforeAll
    static void load() {
        GenSPARQL.init();
        kg = ModelFactory.createDefaultModel();
        RDFDataMgr.read(kg, new StringReader(TTL), null, Lang.TURTLE);
    }

    /**
     * Solutions as canonical strings.
     *
     * <p>Variables are emitted in name order, and the internal variables a path sequence
     * introduces are skipped: they are named from a global counter, so the same query run twice
     * gets different names for the same join position.
     */
    private static List<String> rows(Query query) {
        List<String> out = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(query, kg)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.next();
                List<String> names = new ArrayList<>();
                sol.varNames().forEachRemaining(n -> {
                    // Jena names these from a global counter, so the same join position gets a
                    // different name on each run and comparing them would test nothing.
                    if (!n.matches("\\??P\\d+")) {
                        names.add(n);
                    }
                });
                Collections.sort(names);
                StringBuilder sb = new StringBuilder();
                for (String n : names) {
                    sb.append(n).append('=').append(sol.get(n)).append(' ');
                }
                out.add(sb.toString());
            }
        }
        Collections.sort(out);
        return out;
    }

    private static void agreesWithJena(String body) {
        String queryString = PREFIXES + body;
        List<String> mine = rows(GenSPARQLQueryFactory.create(queryString));
        List<String> reference = rows(QueryFactory.create(queryString));
        assertEquals(reference, mine, "must agree with Jena on: " + body);
        assertFalse(reference.isEmpty(), "the case should match something: " + body);
    }

    @Test
    @DisplayName("a variable in predicate position")
    void variablePredicate() {
        agreesWithJena("SELECT * WHERE { ?s ?p ?o }");
        agreesWithJena("SELECT * WHERE { ex:a ?p ?o }");
        agreesWithJena("SELECT * WHERE { ?s a ex:T OPTIONAL { ?s ?p ?o } }");
        agreesWithJena("SELECT * WHERE { ?s ?p ?o ; rdfs:label ?l }");
    }

    @Test
    @DisplayName("sequence, alternative and inverse")
    void sequenceAlternativeInverse() {
        agreesWithJena("SELECT * WHERE { ?s ex:next/ex:next ?o }");
        agreesWithJena("SELECT * WHERE { ?s rdf:type|rdfs:label ?o }");
        agreesWithJena("SELECT * WHERE { ?o ^ex:next ?s }");
        agreesWithJena("SELECT * WHERE { ?s ^ex:next/ex:next ?o }");
    }

    @Test
    @DisplayName("the cardinality modifiers")
    void modifiers() {
        agreesWithJena("SELECT * WHERE { ?s ex:next* ?o }");
        agreesWithJena("SELECT * WHERE { ?s ex:next+ ?o }");
        agreesWithJena("SELECT * WHERE { ?s ex:next? ?o }");
        agreesWithJena("SELECT * WHERE { ?s rdf:type/rdfs:subClassOf* ?o }");
    }

    @Test
    @DisplayName("grouping and negated property sets")
    void groupingAndNegation() {
        agreesWithJena("SELECT * WHERE { ?s (ex:next/ex:next) ?o }");
        agreesWithJena("SELECT * WHERE { ?s (ex:next)+ ?o }");
        agreesWithJena("SELECT * WHERE { ?s !(rdf:type) ?o }");
        agreesWithJena("SELECT * WHERE { ?s !(rdf:type|rdfs:label) ?o }");
    }

    @Test
    @DisplayName("a path next to plain triples")
    void pathBesidePlainTriples() {
        agreesWithJena("SELECT * WHERE { ?s ex:next+ ?o . ?s rdfs:label ?l }");
        agreesWithJena("SELECT * WHERE { ?s rdfs:label ?l . ?s ex:next+ ?o }");
    }

    @Test
    @DisplayName("a one-step path stays an ordinary triple pattern")
    void singleStepIsNotAPath() {
        // ex:next parses through the path productions but is a plain predicate, so it must not
        // become a TriplePath: keeping it a triple is what lets it join into a BGP.
        Query query = GenSPARQLQueryFactory.create(PREFIXES + "SELECT * WHERE { ?s ex:next ?o }");
        String algebra = org.gensparql.engine.exec.AlgebraGeneratorGenSPARQL
                .compile(query.getQueryPattern()).toString();

        assertTrue(algebra.contains("bgp"), "expected a BGP, got: " + algebra);
        assertFalse(algebra.contains("path"), "a single link must not compile to a path: " + algebra);
    }

    @Test
    @DisplayName("a path feeding a GENOP")
    void pathFeedingGenOp() {
        Query query = GenSPARQLQueryFactory.create(PREFIXES
                + "SELECT * WHERE { ?s rdf:type/rdfs:subClassOf* ?super . ?s rdfs:label ?l . "
                + "GENOP(\"say {?l}\", (?g), <model:mock:t>) }");

        String algebra = org.gensparql.engine.exec.AlgebraGeneratorGenSPARQL
                .compile(query.getQueryPattern()).toString();
        assertTrue(algebra.contains("path"), "the path must survive into the algebra: " + algebra);
        assertTrue(algebra.contains("generate"), "the GENOP must survive too: " + algebra);
    }
}
