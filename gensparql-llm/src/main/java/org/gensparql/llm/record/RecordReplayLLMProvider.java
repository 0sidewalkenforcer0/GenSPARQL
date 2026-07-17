package org.gensparql.llm.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gensparql.core.model.*;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.cache.CacheKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Record-replay decorator for deterministic, reproducible evaluation.
 *
 * <p>The eval methodology (see docs/RESEARCH_IDEA1_PLAN.md) requires that all plan
 * variants run against the SAME LLM responses, so the only variable is the plan. Real
 * LLMs are non-deterministic, so we:
 * <ul>
 *   <li><b>RECORD</b> — call the delegate once per distinct request, persist the response;
 *   <li><b>REPLAY</b> — serve persisted responses; a cache miss fails loud (returns an
 *       error response marked {@code REPLAY_MISS}) so an unfaithful replay is never mistaken
 *       for a real result;
 *   <li><b>OFF</b> — transparent pass-through.
 * </ul>
 *
 * <p>Requests are keyed by {@link CacheKey#toStringKey()} (provider, model, prompt hash,
 * temperature, maxTokens, output variables). During RECORD, a repeated key reuses the
 * first stored response, so the recording itself is deterministic per key.
 *
 * <p><b>Scope:</b> generation only. Embeddings pass through (grounding determinism is a
 * separate concern, out of scope for the C3/C4 planning experiments).
 */
public class RecordReplayLLMProvider implements LLMProvider {

    private static final Logger LOG = LoggerFactory.getLogger(RecordReplayLLMProvider.class);

    public enum Mode { OFF, RECORD, REPLAY }

    private final LLMProvider delegate;
    private final Mode mode;
    private final Path storeFile;
    private final Map<String, StoredResponse> store = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private int replayHits = 0;
    private int replayMisses = 0;
    private int recorded = 0;

    public RecordReplayLLMProvider(LLMProvider delegate, Mode mode, Path storeFile) {
        this.delegate = delegate;
        this.mode = mode;
        this.storeFile = storeFile;
        if (mode == Mode.REPLAY || (mode == Mode.RECORD && storeFile != null && Files.exists(storeFile))) {
            load();
        }
    }

    @Override
    public String getName() {
        // Transparent: keep the delegate's name so ModelSpec resolution still routes here.
        return delegate.getName();
    }

    @Override
    public boolean isAvailable() {
        return mode == Mode.REPLAY || delegate.isAvailable();
    }

    @Override
    public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
        if (mode == Mode.OFF) {
            return delegate.generate(request);
        }

        String key = keyOf(request);
        StoredResponse stored = store.get(key);
        if (stored != null) {
            if (mode == Mode.REPLAY) replayHits++;
            return CompletableFuture.completedFuture(stored.toResponse());
        }

        if (mode == Mode.REPLAY) {
            replayMisses++;
            LOG.warn("REPLAY_MISS for key {}", key);
            return CompletableFuture.completedFuture(
                    GenerateResponse.error("REPLAY_MISS: no recorded response for key " + key));
        }

        // RECORD: call the delegate once and remember it.
        return delegate.generate(request).thenApply(response -> {
            if (response != null) {
                store.put(key, StoredResponse.from(response));
                recorded++;
            }
            return response;
        });
    }

    @Override
    public CompletableFuture<EmbedResponse> embed(EmbedRequest request) {
        // Out of scope: embeddings pass through.
        return delegate.embed(request);
    }

    @Override
    public boolean supportsEmbedding() {
        return delegate.supportsEmbedding();
    }

    @Override
    public String getDefaultModel() {
        return delegate.getDefaultModel();
    }

    @Override
    public String getDefaultEmbeddingModel() {
        return delegate.getDefaultEmbeddingModel();
    }

    @Override
    public void close() {
        if (mode == Mode.RECORD) {
            save();
        }
        delegate.close();
    }

    private String keyOf(GenerateRequest request) {
        ModelSpec spec = request.getModelSpec();
        String model = spec != null ? spec.getModel() : delegate.getDefaultModel();
        return new CacheKey(delegate.getName(), model, request.getPrompt(),
                request.getTemperature(), request.getMaxTokens(),
                request.getOutputVariables()).toStringKey();
    }

    /** Persist the recorded responses to the store file (JSON). */
    public void save() {
        if (storeFile == null) {
            return;
        }
        try {
            if (storeFile.getParent() != null) {
                Files.createDirectories(storeFile.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(storeFile.toFile(), store);
            LOG.info("Recorded {} response(s) to {}", store.size(), storeFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save record-replay store to " + storeFile, e);
        }
    }

    private void load() {
        if (storeFile == null || !Files.exists(storeFile)) {
            return;
        }
        try {
            Map<String, StoredResponse> loaded = mapper.readValue(
                    storeFile.toFile(),
                    mapper.getTypeFactory().constructMapType(
                            java.util.HashMap.class, String.class, StoredResponse.class));
            store.putAll(loaded);
            LOG.info("Loaded {} recorded response(s) from {}", store.size(), storeFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load record-replay store from " + storeFile, e);
        }
    }

    // ---- counters (for eval reporting) ----
    public int getReplayHits() { return replayHits; }
    public int getReplayMisses() { return replayMisses; }
    public int getRecorded() { return recorded; }
    public int getStoreSize() { return store.size(); }
    public Mode getMode() { return mode; }

    /** JSON-serializable snapshot of a {@link GenerateResponse}. */
    public static class StoredResponse {
        public String rawText;
        public List<Map<String, String>> bindings;
        public boolean success = true;
        public String errorMessage;
        public String model;
        public int promptTokens;
        public int completionTokens;

        public StoredResponse() {
        }

        static StoredResponse from(GenerateResponse r) {
            StoredResponse s = new StoredResponse();
            s.rawText = r.getRawText();
            s.bindings = r.getBindings();
            s.success = r.isSuccess();
            s.errorMessage = r.getErrorMessage();
            GenerateResponse.ResponseMetadata md = r.getMetadata();
            if (md != null) {
                s.model = md.getModel();
                s.promptTokens = md.getPromptTokens();
                s.completionTokens = md.getCompletionTokens();
            }
            return s;
        }

        GenerateResponse toResponse() {
            return GenerateResponse.builder()
                    .rawText(rawText)
                    .bindings(bindings)
                    .success(success)
                    .errorMessage(errorMessage)
                    .metadata(new GenerateResponse.ResponseMetadata(
                            model, promptTokens, completionTokens, 0L, null, null))
                    .build();
        }
    }
}
