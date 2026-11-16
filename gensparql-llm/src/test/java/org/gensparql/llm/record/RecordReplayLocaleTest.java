package org.gensparql.llm.record;

import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.llm.cache.CacheKey;
import org.gensparql.llm.provider.MockLLMProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key of a recorded answer does not depend on the default locale of the JVM: the committed
 * records use "0.00", and a JVM in a locale with a decimal comma (de, fr, ...) must replay them.
 */
class RecordReplayLocaleTest {

    private static GenerateRequest request() {
        return GenerateRequest.builder()
                .prompt("List the fields in which the Nobel Prize is awarded.")
                .modelSpec(ModelSpec.fromURI("model:mock:m"))
                .outputVariables(List.of("x"))
                .build();
    }

    @Test
    void keyDoesNotDependOnTheDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            String us = new CacheKey("openai", "m", "p", 0.0, 1000, List.of("x")).toStringKey();
            Locale.setDefault(Locale.GERMANY);
            String de = new CacheKey("openai", "m", "p", 0.0, 1000, List.of("x")).toStringKey();
            assertEquals(us, de);
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void aStoreRecordedUnderEnUsReplaysUnderDeDe(@TempDir Path tmp) throws Exception {
        Path store = tmp.resolve("store.json");
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            RecordReplayLLMProvider recorder = new RecordReplayLLMProvider(
                    new MockLLMProvider().withDefaultResponse("[\"Physics\"]"),
                    RecordReplayLLMProvider.Mode.RECORD, store);
            assertTrue(recorder.generateSync(request()).isSuccess());
            recorder.close();
            assertTrue(Files.readString(store).contains(":0.00:"), "the committed records use 0.00 too");

            Locale.setDefault(Locale.GERMANY);
            RecordReplayLLMProvider replayer = new RecordReplayLLMProvider(
                    new MockLLMProvider(), RecordReplayLLMProvider.Mode.REPLAY, store);
            GenerateResponse replayed = replayer.generateSync(request());
            assertTrue(replayed.isSuccess(), replayed.getErrorMessage());
        } finally {
            Locale.setDefault(saved);
        }
    }
}
