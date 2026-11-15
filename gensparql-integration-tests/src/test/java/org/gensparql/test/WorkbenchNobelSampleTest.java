package org.gensparql.test;

import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workbench sample {@code nobelQuantumLaureates} of the project page, read from
 * {@code docs/app.js} and run over {@code docs/nobel-prize.ttl} as the HTTP service runs it.
 * Its base-mode GENOP lists names once, and the similarity join keeps the physics laureates of
 * the graph whose label matches one of them.
 */
class WorkbenchNobelSampleTest {

    @BeforeAll
    static void init() {
        GenSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        GenSPARQLConfig.reset();
    }

    private static File docs(String name) {
        File f = new File("../docs/" + name);
        return f.exists() ? f : new File("docs/" + name);
    }

    private static String sample() throws IOException {
        String app = Files.readString(docs("app.js").toPath());
        Matcher m = Pattern.compile("nobelQuantumLaureates:`([^`]*)`").matcher(app);
        assertTrue(m.find(), "the sample is defined in docs/app.js");
        return m.group(1).replace("<model>", "<model:mock:test>");
    }

    @Test
    void theSampleKeepsOnlyTheLaureatesWhoseNamesTheModelLists() throws IOException {
        MockLLMProvider mock = new MockLLMProvider()
                .withDefaultResponse("[\"Max Planck\", \"Niels Bohr\", \"Werner Heisenberg\"]");
        LLMProviderRegistry.setDefault(mock);
        Model data = RDFDataMgr.loadModel(docs("nobel-prize.ttl").getPath());

        List<String> rows = new ArrayList<>();
        try (QueryExecution qe = GenSPARQL.createQueryExecution(GenSPARQLQueryFactory.create(sample()), data)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                rows.add(s.getLiteral("laureate").getLexicalForm() + " " + s.getLiteral("year").getLexicalForm()
                        + " " + s.getResource("person").getURI());
            }
        }

        assertEquals(1, mock.getRequestHistory().size(), "the base-mode prompt is sent once");
        assertEquals(List.of(
                "Max Planck 1918 http://www.wikidata.org/entity/Q9021",
                "Niels Bohr 1922 http://www.wikidata.org/entity/Q7085",
                "Werner Heisenberg 1932 http://www.wikidata.org/entity/Q40904"), rows);
    }
}
