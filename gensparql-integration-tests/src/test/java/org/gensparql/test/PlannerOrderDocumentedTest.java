package org.gensparql.test;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The order of the elements of a group as the README describes it, one test per rule. For
 * GENOP expressions G1, ..., Gn in written order with the output variables v̄1, ..., v̄n:
 * an OPTIONAL, MINUS or BIND is deferred if it mentions an output variable or a variable of an
 * earlier element that mentions one or is deferred; the other elements without an output
 * variable come first, and the filters over the variables of their triple patterns follow them
 * before G1; then each Gi with the elements whose last output variable is in v̄i; then the
 * deferred elements in written order; then the remaining filters.
 */
class PlannerOrderDocumentedTest {

    private static final String PREFIXES = """
            PREFIX ex: <http://example.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            """;
    private static final String TEAM_OF = "GENOP(\"Team of {?name}?\", (?team), <model:mock:test>)";
    private MockLLMProvider llm;

    @BeforeEach
    void provider() {
        GenSPARQL.init();
        llm = new MockLLMProvider()
                .withResponse("Lionel Messi", "[\"Argentina\"]")
                .withResponse("Kylian Mbappe", "[\"France\"]");
        LLMProviderRegistry.setDefault(llm);
    }

    private static Model graph() {
        Model m = ModelFactory.createDefaultModel();
        Resource athlete = m.createResource("http://example.org/Athlete");
        Resource team = m.createResource("http://example.org/Team");
        Property code = m.createProperty("http://example.org/code");
        m.add(m.createResource("http://example.org/p1"), RDF.type, athlete)
                .add(m.createResource("http://example.org/p1"), RDFS.label, "Lionel Messi")
                .add(m.createResource("http://example.org/p2"), RDF.type, athlete)
                .add(m.createResource("http://example.org/p2"), RDFS.label, "Kylian Mbappe")
                .add(m.createResource("http://example.org/arg"), RDF.type, team)
                .add(m.createResource("http://example.org/arg"), RDFS.label, "Argentina")
                .add(m.createResource("http://example.org/arg"), code, "ARG")
                .add(m.createResource("http://example.org/fra"), RDF.type, team)
                .add(m.createResource("http://example.org/fra"), RDFS.label, "France")
                .add(m.createResource("http://example.org/fra"), code, "FRA");
        return m;
    }

    /** The values of one variable in the rows, sorted, "-" where it is unbound. */
    private static List<String> column(String body, String var) {
        Context cxt = ARQ.getContext().copy();
        cxt.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
        List<String> out = new ArrayList<>();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(
                GenSPARQLQueryFactory.create(PREFIXES + body), DatasetFactory.wrap(graph()), cxt)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                out.add(s.contains(var) ? s.get(var).toString() : "-");
            }
        }
        out.sort(null);
        return out;
    }

    @Test
    void aBindThatMentionsAnOutputVariableIsDeferredWhereverItIsWritten() {
        // Written before the GENOP, the BIND still reads the entity that the similarity join maps.
        assertEquals(List.of("http://example.org/arg", "http://example.org/fra"), column(
                "SELECT * WHERE { ?p a ex:Athlete ; rdfs:label ?name . "
                        + "BIND(STR(?team) AS ?s) " + TEAM_OF + " ?team a ex:Team . }", "s"));
    }

    @Test
    void aBindThatReadsAVariableOfAnElementWithAnOutputVariableIsDeferred() {
        assertEquals(List.of("arg", "fra"), column(
                "SELECT * WHERE { ?p a ex:Athlete ; rdfs:label ?name . " + TEAM_OF
                        + " ?team a ex:Team ; ex:code ?code . BIND(LCASE(?code) AS ?lc) }", "lc"));
    }

    @Test
    void anOptionalWithoutSuchVariablesComesBeforeTheGenopAndBindsItsPlaceholder() {
        assertEquals(List.of("http://example.org/arg", "http://example.org/fra"), column(
                "SELECT * WHERE { " + TEAM_OF + " ?team a ex:Team . ?p a ex:Athlete "
                        + "OPTIONAL { ?p rdfs:label ?name } }", "team"));
        assertEquals(2, llm.getRequestHistory().size());
    }

    @Test
    void aFilterOverTheTriplePatternsBeforeTheGenopsIsAppliedBeforeThePrompts() {
        assertEquals(List.of("http://example.org/arg"), column(
                "SELECT * WHERE { ?p a ex:Athlete ; rdfs:label ?name . " + TEAM_OF
                        + " ?team a ex:Team . FILTER(?name = \"Lionel Messi\") }", "team"));
        assertEquals(1, llm.getRequestHistory().size(), "one prompt, for Lionel Messi only");
    }

    @Test
    void theOtherFiltersAreAppliedLast() {
        assertEquals(List.of("http://example.org/fra"), column(
                "SELECT * WHERE { FILTER(?code = \"FRA\") ?p a ex:Athlete ; rdfs:label ?name . " + TEAM_OF
                        + " ?team a ex:Team ; ex:code ?code . }", "team"));
        assertEquals(2, llm.getRequestHistory().size(), "both players are prompted");
    }
}
