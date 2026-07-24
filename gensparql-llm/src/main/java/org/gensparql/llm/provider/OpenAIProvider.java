package org.gensparql.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.gensparql.core.exception.LLMException;
import org.gensparql.core.model.*;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.parser.JsonResponseParser;
import org.gensparql.llm.parser.ResponseParser;
import org.gensparql.llm.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI LLM provider implementation.
 *
 * Supports GPT models via OpenAI API.
 */
public class OpenAIProvider implements LLMProvider {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAIProvider.class);

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    // Chat and embeddings are frequently served by two different OpenAI-compatible
    // servers (e.g. a vLLM chat server and a separate vLLM/TEI embedding server), so the
    // embedding endpoint and key can be pointed independently. They fall back to the chat
    // base URL / API key when not set, preserving the single-server case.
    private final String embeddingBaseUrl;
    private final String embeddingApiKey;
    private final ResponseParser responseParser;

    public OpenAIProvider() {
        this(System.getenv("OPENAI_API_KEY"));
    }

    public OpenAIProvider(String apiKey) {
        // OPENAI_BASE_URL lets you point at any OpenAI-compatible endpoint
        // (OpenAI, Ollama at http://localhost:11434/v1, HF TEI, vLLM, ...).
        this(apiKey, System.getenv("OPENAI_BASE_URL"));
    }

    public OpenAIProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        // OPENAI_EMBEDDING_BASE_URL / OPENAI_EMBEDDING_API_KEY override the endpoint used for
        // embeddings; if unset, embeddings reuse the chat base URL and key.
        String embUrl = System.getenv("OPENAI_EMBEDDING_BASE_URL");
        this.embeddingBaseUrl = (embUrl != null && !embUrl.isEmpty()) ? embUrl : this.baseUrl;
        String embKey = System.getenv("OPENAI_EMBEDDING_API_KEY");
        this.embeddingApiKey = (embKey != null && !embKey.isEmpty()) ? embKey : apiKey;
        this.objectMapper = new ObjectMapper();
        this.responseParser = new JsonResponseParser();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public String getDefaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    public String getDefaultEmbeddingModel() {
        // OPENAI_EMBEDDING_MODEL selects the embedding model, e.g. a foundation
        // text embedder like "nomic-embed-text" or "bge-m3" on a local server.
        String env = System.getenv("OPENAI_EMBEDDING_MODEL");
        return (env != null && !env.isEmpty()) ? env : DEFAULT_EMBEDDING_MODEL;
    }

    @Override
    public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doGenerate(request);
            } catch (Exception e) {
                LOG.error("Generation failed", e);
                return GenerateResponse.error(e.getMessage());
            }
        });
    }

    private GenerateResponse doGenerate(GenerateRequest request) throws IOException {
        if (!isAvailable()) {
            throw new LLMException("OpenAI API key not configured", "openai", null, -1);
        }

        long startTime = System.currentTimeMillis();
        String model = request.getModelSpec() != null ?
                request.getModelSpec().getModel() : DEFAULT_MODEL;

        // Build enhanced prompt with format instructions for multi-variable outputs
        String enhancedPrompt = PromptBuilder.buildStructuredPrompt(
                request.getPrompt(),
                request.getOutputVariables()
        );
        LOG.debug("Enhanced prompt: {}", truncate(enhancedPrompt, 500));

        // Build request body
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "user", "content", enhancedPrompt)
        ));
        body.put("temperature", request.getTemperature());
        body.put("max_tokens", request.getMaxTokens());

        String json = objectMapper.writeValueAsString(body);
        LOG.debug("OpenAI request: {}", truncate(json, 200));

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                LOG.error("OpenAI API error: {} - {}", response.code(), responseBody);
                throw new LLMException("OpenAI API error: " + response.code(),
                        "openai", model, response.code());
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                // Error payloads and content-filter responses omit choices; guard against
                // the NPE that root.path("choices").get(0) would otherwise throw.
                String errMsg = root.path("error").path("message").asText("no choices returned");
                LOG.error("OpenAI API returned no choices: {}", responseBody);
                throw new LLMException("OpenAI API returned no choices: " + errMsg,
                        "openai", model, response.code());
            }
            String content = choices.get(0).path("message").path("content").asText();

            // Parse usage info
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            long latency = System.currentTimeMillis() - startTime;

            GenerateResponse.ResponseMetadata metadata = new GenerateResponse.ResponseMetadata(
                    model, promptTokens, completionTokens, latency,
                    Instant.now(), root.path("id").asText()
            );

            LOG.debug("OpenAI response: {} tokens in {}ms", promptTokens + completionTokens, latency);

            // Parse output into bindings using ResponseParser
            List<Map<String, String>> bindings = responseParser.parse(content, request.getOutputVariables());
            LOG.debug("ResponseParser returned {} binding(s)", bindings.size());

            return GenerateResponse.builder()
                    .rawText(content)
                    .bindings(bindings)
                    .metadata(metadata)
                    .success(true)
                    .build();
        }
    }

    @Override
    public CompletableFuture<EmbedResponse> embed(EmbedRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return doEmbed(request);
            } catch (Exception e) {
                LOG.error("Embedding failed", e);
                return EmbedResponse.error(e.getMessage());
            }
        });
    }

    private EmbedResponse doEmbed(EmbedRequest request) throws IOException {
        if (!isAvailable()) {
            throw new LLMException("OpenAI API key not configured", "openai", null, -1);
        }

        String model = request.getModelSpec() != null ?
                request.getModelSpec().getModel() : getDefaultEmbeddingModel();

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", request.getTexts());

        String json = objectMapper.writeValueAsString(body);

        Request httpRequest = new Request.Builder()
                .url(embeddingBaseUrl + "/embeddings")
                .addHeader("Authorization", "Bearer " + embeddingApiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new LLMException("OpenAI Embedding API error: " + response.code(),
                        "openai", model, response.code());
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataArray = root.path("data");

            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : dataArray) {
                JsonNode embeddingNode = item.path("embedding");
                float[] embedding = new float[embeddingNode.size()];
                for (int i = 0; i < embeddingNode.size(); i++) {
                    embedding[i] = (float) embeddingNode.get(i).asDouble();
                }
                embeddings.add(embedding);
            }

            return EmbedResponse.success(embeddings);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
