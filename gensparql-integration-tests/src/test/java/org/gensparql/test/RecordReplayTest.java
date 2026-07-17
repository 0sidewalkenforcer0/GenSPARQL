package org.gensparql.test;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.MockLLMProvider;
import org.gensparql.llm.record.RecordReplayLLMProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the record-replay layer — the foundation of the deterministic eval protocol
 * (docs/RESEARCH_IDEA1_PLAN.md §4): a recorded run can be replayed byte-for-byte with the
 * real LLM never touched, and a missing recording fails loud instead of silently calling out.
 */
public class RecordReplayTest {

    private static final String FIELD_QUERY = """
            PREFIX ex: <http://example.org/>
            SELECT ?scientist ?field ?desc WHERE {
              ?scientist ex:researchField ?field .
              GENOP("Describe the research field {?field}.", ?desc, <model:mock:test>)
            }
            """;

    @BeforeAll
    static void setUpClass() {
        GenSPARQL.init();
    }

    private static Model model(String... fields) {
        Model m = ModelFactory.createDefaultModel();
        String ns = "http://example.org/";
        int i = 0;
        for (String field : fields) {
            m.add(ResourceFactory.createResource(ns + "p" + (i++)),
                  ResourceFactory.createProperty(ns + "researchField"),
                  ResourceFactory.createPlainLiteral(field));
        }
        return m;
    }

    private static List<String> run(Model model) {
        Query query = GenSPARQLQueryFactory.create(FIELD_QUERY);
        List<String> rows = new ArrayList<>();
        try (QueryExecution qexec = QueryExecutionFactory.create(query, model)) {
            ResultSet rs = qexec.execSelect();
            while (rs.hasNext()) {
                QuerySolution s = rs.next();
                rows.add(s.get("field") + "|" + s.get("desc"));
            }
        }
        return rows;
    }

    @Test
    void testRecordThenReplayReproducesResultsWithoutCallingLLM(@TempDir Path tmp) {
        Path store = tmp.resolve("gen-responses.json");
        Model data = model("Physics", "Chemistry", "Physics");

        // RECORD
        MockLLMProvider recDelegate = new MockLLMProvider()
                .withResponse("Physics", "study of matter and energy")
                .withResponse("Chemistry", "study of substances");
        RecordReplayLLMProvider recorder =
                new RecordReplayLLMProvider(recDelegate, RecordReplayLLMProvider.Mode.RECORD, store);
        LLMProviderRegistry.setDefault(recorder);
        List<String> recorded = run(data);
        recorder.close(); // flushes to disk

        assertTrue(Files.exists(store), "record run must persist a store file");
        assertTrue(recorder.getRecorded() > 0, "record run must have recorded responses");

        // REPLAY — delegate has NO responses configured and must never be called.
        MockLLMProvider replayDelegate = new MockLLMProvider();
        RecordReplayLLMProvider replayer =
                new RecordReplayLLMProvider(replayDelegate, RecordReplayLLMProvider.Mode.REPLAY, store);
        LLMProviderRegistry.setDefault(replayer);
        List<String> replayed = run(data);

        assertEquals(0, replayDelegate.getRequestHistory().size(),
                "REPLAY must not call the underlying LLM");
        assertTrue(replayer.getReplayHits() > 0, "REPLAY must serve from the store");
        assertEquals(0, replayer.getReplayMisses(), "no misses expected for a full recording");

        Collections.sort(recorded);
        Collections.sort(replayed);
        assertEquals(recorded, replayed, "replay must reproduce the recorded results exactly");
    }

    @Test
    void testReplayMissFailsLoud(@TempDir Path tmp) {
        Path store = tmp.resolve("partial.json");

        // Record only Physics.
        MockLLMProvider recDelegate = new MockLLMProvider()
                .withResponse("Physics", "study of matter");
        RecordReplayLLMProvider recorder =
                new RecordReplayLLMProvider(recDelegate, RecordReplayLLMProvider.Mode.RECORD, store);
        LLMProviderRegistry.setDefault(recorder);
        run(model("Physics"));
        recorder.close();

        // Replay over data that also needs Chemistry (never recorded) -> that prompt misses.
        MockLLMProvider replayDelegate = new MockLLMProvider();
        RecordReplayLLMProvider replayer =
                new RecordReplayLLMProvider(replayDelegate, RecordReplayLLMProvider.Mode.REPLAY, store);
        LLMProviderRegistry.setDefault(replayer);
        List<String> replayed = run(model("Physics", "Chemistry"));

        assertTrue(replayer.getReplayMisses() > 0, "unrecorded prompt must register a REPLAY_MISS");
        assertEquals(0, replayDelegate.getRequestHistory().size(),
                "a miss must NOT silently fall back to the real LLM");
        // The missed (Chemistry) binding yields no row; the recorded (Physics) one does.
        assertEquals(1, replayed.size(), "only the recorded prompt produces a result row");
        assertTrue(replayed.get(0).startsWith("Physics|"));
    }
}
