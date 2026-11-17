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
    // A self-hosted OpenAI-compatible server (Ollama, vLLM) usually needs no key, so a base URL
    // other than OpenAI's makes the provider available without one.
    private final boolean ownEndpoint;
    private final boolean ownEmbeddingEndpoint;

    public OpenAIProvider() {
        this(System.getenv("OPENAI_API_KEY"));
    }

    public OpenAIProvider(String apiKey) {
        // OPENAI_BASE_URL lets you point at any OpenAI-compatible endpoint
        // (OpenAI, Ollama at http://localhost:11434/v1, HF TEI, vLLM, ...).
        this(apiKey, System.getenv("OPENAI_BASE_URL"));
    }

    public OpenAIProvider(String apiKey, String baseUrl) {
        // OPENAI_EMBEDDING_BASE_URL / OPENAI_EMBEDDING_API_KEY override the endpoint used for
        // embeddings; if unset, embeddings reuse the chat base URL and key.
        this(apiKey, baseUrl, System.getenv("OPENAI_EMBEDDING_API_KEY"),
                System.getenv("OPENAI_EMBEDDING_BASE_URL"));
    }

    /**
     * A provider with explicit endpoints that reads no environment variable, as the HTTP service
     * builds one for the endpoint and key of a single request. An embedding key or base URL that
     * is null or empty falls back to the chat one.
     */
    public OpenAIProvider(String apiKey, String baseUrl, String embeddingApiKey, String embeddingBaseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = normalizeBaseUrl(baseUrl != null ? baseUrl : DEFAULT_BASE_URL);
        this.embeddingBaseUrl = (embeddingBaseUrl != null && !embeddingBaseUrl.isEmpty())
                ? normalizeBaseUrl(embeddingBaseUrl) : this.baseUrl;
        this.embeddingApiKey = (embeddingApiKey != null && !embeddingApiKey.isEmpty()) ? embeddingApiKey : apiKey;
        this.ownEndpoint = !this.baseUrl.equals(DEFAULT_BASE_URL);
        this.ownEmbeddingEndpoint = !this.embeddingBaseUrl.equals(DEFAULT_BASE_URL);
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

    /**
     * Available with a key, or without one for an endpoint other than OpenAI's: a local Ollama or
     * vLLM server ignores the key, and the request then carries no Authorization header.
     */
    @Override
    public boolean isAvailable() {
        return hasText(apiKey) || ownEndpoint;
    }

    private boolean isEmbeddingAvailable() {
        return hasText(embeddingApiKey) || ownEmbeddingEndpoint;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static Request.Builder authorized(Request.Builder builder, String key) {
        return hasText(key) ? builder.addHeader("Authorization", "Bearer " + key) : builder;
    }

    @Override
    public String getDefaultModel() {
        return DEFAULT_MODEL;
    }

    /** The embedding endpoint: OpenAI-compatible servers often serve models of the same name. */
    @Override
    public String getEmbeddingEndpointId() {
        return getName() + "@" + embeddingBaseUrl;
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

        // Add the output format instructions, unless the prompt already carries its own
        String enhancedPrompt = PromptBuilder.buildStructuredPrompt(request);
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

        Request httpRequest = authorized(new Request.Builder(), apiKey)
                .url(baseUrl + "/chat/completions")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                LOG.error("OpenAI API error: {} - {}", response.code(), responseBody);
                throw new LLMException("OpenAI API error " + response.code() + ": "
                                + apiErrorMessage(responseBody),
                        "openai", model, response.code());
            }

            JsonNode root = readJsonResponse(response, responseBody, model, "Chat Completions");
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

    private String apiErrorMessage(String responseBody) {
        try {
            String message = objectMapper.readTree(responseBody)
                    .path("error").path("message").asText("");
            if (!message.isBlank()) return truncate(message, 600);
        } catch (Exception ignored) {
            // Fall back to the raw response below.
        }
        String raw = responseBody == null || responseBody.isBlank()
                ? "No error details returned" : responseBody;
        return truncate(raw, 600);
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
        if (!isEmbeddingAvailable()) {
            throw new LLMException("OpenAI API key not configured", "openai", null, -1);
        }

        String model = request.getModelSpec() != null ?
                request.getModelSpec().getModel() : getDefaultEmbeddingModel();

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", request.getTexts());

        String json = objectMapper.writeValueAsString(body);

        Request httpRequest = authorized(new Request.Builder(), embeddingApiKey)
                .url(embeddingBaseUrl + "/embeddings")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new LLMException("OpenAI Embedding API error: " + response.code(),
                        "openai", model, response.code());
            }

            JsonNode root = readJsonResponse(response, responseBody, model, "Embeddings");
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

    /**
     * Accept either an API root (for example {@code https://host/v1}) or a full
     * OpenAI endpoint pasted from provider documentation. Requests append the
     * appropriate endpoint themselves, so remove that suffix once here.
     */
    static String normalizeBaseUrl(String value) {
        String normalized = Objects.requireNonNull(value, "baseUrl").trim();
        normalized = normalized.replaceFirst("/+$", "");
        normalized = normalized.replaceFirst("(?i)/(chat/completions|responses|embeddings)$", "");
        HttpUrl parsed = HttpUrl.parse(normalized);
        if (parsed == null || !("http".equals(parsed.scheme()) || "https".equals(parsed.scheme()))) {
            throw new IllegalArgumentException("Invalid OpenAI-compatible base URL: " + value);
        }
        return normalized;
    }

    private JsonNode readJsonResponse(Response response, String body, String model, String apiName) {
        String text = body == null ? "" : body.trim();
        String contentType = response.header("Content-Type", "unknown");
        if (!(text.startsWith("{") || text.startsWith("["))) {
            String preview = truncate(text.replaceAll("\\s+", " "), 240);
            throw new LLMException(apiName + " endpoint returned a non-JSON response"
                            + " (HTTP " + response.code() + ", Content-Type: " + contentType + "). "
                            + "Check the OpenAI-compatible base URL; it should normally end at /v1."
                            + (preview.isBlank() ? "" : " Response begins: " + preview),
                    "openai", model, response.code());
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root == null) throw new IOException("empty JSON document");
            return root;
        } catch (IOException e) {
            throw new LLMException(apiName + " endpoint returned invalid JSON"
                            + " (HTTP " + response.code() + ", Content-Type: " + contentType + "): "
                            + e.getMessage(),
                    "openai", model, response.code(), e);
        }
    }

    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
