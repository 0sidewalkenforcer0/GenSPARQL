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
 * OpenRouter LLM provider implementation.
 *
 * OpenRouter provides access to multiple LLM providers through a unified API.
 * Uses OpenAI-compatible API format.
 */
public class OpenRouterProvider implements LLMProvider {
    private static final Logger LOG = LoggerFactory.getLogger(OpenRouterProvider.class);

    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    private static final String DEFAULT_MODEL = "deepseek/deepseek-chat";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final ResponseParser responseParser;

    public OpenRouterProvider() {
        this(System.getenv("OPENROUTER_API_KEY"));
    }

    public OpenRouterProvider(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public OpenRouterProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
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
        return "openrouter";
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
    public boolean supportsEmbedding() {
        return true; // OpenRouter supports embeddings via OpenAI-compatible API
    }

    @Override
    public CompletableFuture<GenerateResponse> generate(GenerateRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("[DEBUG OpenRouterProvider] Starting generation...");
                GenerateResponse resp = doGenerate(request);
                System.out.println("[DEBUG OpenRouterProvider] Generation completed, success=" + resp.isSuccess());
                return resp;
            } catch (Exception e) {
                System.out.println("[DEBUG OpenRouterProvider] Generation failed: " + e.getMessage());
                e.printStackTrace();
                LOG.error("Generation failed", e);
                return GenerateResponse.error(e.getMessage());
            }
        });
    }

    private GenerateResponse doGenerate(GenerateRequest request) throws IOException {
        if (!isAvailable()) {
            throw new LLMException("OpenRouter API key not configured", "openrouter", null, -1);
        }

        String model = request.getModelSpec() != null ?
                request.getModelSpec().getModel() : DEFAULT_MODEL;

        // Retry loop for rate limiting and transient errors
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doGenerateAttempt(request, model, attempt);
            } catch (IOException e) {
                lastException = e;
                String msg = e.getMessage();
                // Check if it's a rate limit or transient error worth retrying
                boolean isRateLimit = msg != null && (msg.contains("429") || msg.contains("rate") || msg.contains("limit"));
                boolean isServerError = msg != null && (msg.contains("500") || msg.contains("502") || msg.contains("503"));

                if ((isRateLimit || isServerError) && attempt < MAX_RETRIES) {
                    long delay = RETRY_DELAY_MS * attempt;
                    LOG.warn("OpenRouter request failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt, MAX_RETRIES, delay, msg);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry delay", ie);
                    }
                } else {
                    throw e;
                }
            }
        }
        throw lastException != null ? lastException : new IOException("Failed after " + MAX_RETRIES + " attempts");
    }

    private GenerateResponse doGenerateAttempt(GenerateRequest request, String model, int attempt) throws IOException {
        long startTime = System.currentTimeMillis();

        // Build enhanced prompt with format instructions for multi-variable outputs
        String enhancedPrompt = PromptBuilder.buildStructuredPrompt(
                request.getPrompt(),
                request.getOutputVariables()
        );
        LOG.debug("Enhanced prompt: {}", truncate(enhancedPrompt, 500));

        // Build request body (OpenAI-compatible format)
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "user", "content", enhancedPrompt)
        ));
        body.put("temperature", request.getTemperature());
        body.put("max_tokens", request.getMaxTokens());

        String json = objectMapper.writeValueAsString(body);
        LOG.debug("OpenRouter request (attempt {}): {}", attempt, truncate(json, 200));

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://gensparql.org")
                .addHeader("X-Title", "GenSPARQL")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                LOG.error("OpenRouter API error: {} - {}", response.code(), responseBody);
                throw new IOException("OpenRouter API error: " + response.code() + " - " + truncate(responseBody, 200));
            }

            LOG.debug("OpenRouter response body: {}", truncate(responseBody, 500));
            JsonNode root = objectMapper.readTree(responseBody);

            // Check if choices array exists and has elements
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.size() == 0) {
                LOG.error("OpenRouter response has no choices: {}", responseBody);
                throw new IOException("OpenRouter response has no choices: " + truncate(responseBody, 200));
            }

            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.path("message");
            String content = message.path("content").asText();

            LOG.debug("Extracted content from response: '{}' (length: {})",
                     truncate(content, 100), content != null ? content.length() : 0);

            if (content == null || content.trim().isEmpty()) {
                LOG.warn("OpenRouter returned empty content. Full response: {}", truncate(responseBody, 1000));
                // Try alternative paths
                if (message.has("text")) {
                    content = message.path("text").asText();
                    LOG.debug("Using alternative 'text' field: '{}'", truncate(content, 100));
                }
                // Check reasoning field for "think" models
                if ((content == null || content.trim().isEmpty()) && firstChoice.has("reasoning")) {
                    content = firstChoice.path("reasoning").asText();
                    LOG.debug("Using 'reasoning' field: '{}'", truncate(content, 100));
                }
                // If still empty, return error response instead of throwing
                if (content == null || content.trim().isEmpty()) {
                    LOG.error("OpenRouter returned empty content after checking all fields");
                    return GenerateResponse.error("LLM returned empty response");
                }
            }

            // Parse usage info
            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);
            long latency = System.currentTimeMillis() - startTime;

            GenerateResponse.ResponseMetadata metadata = new GenerateResponse.ResponseMetadata(
                    model, promptTokens, completionTokens, latency,
                    Instant.now(), root.path("id").asText()
            );

            LOG.debug("OpenRouter response: {} tokens in {}ms", promptTokens + completionTokens, latency);

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

    private static final String DEFAULT_EMBEDDING_MODEL = "openai/text-embedding-3-small";

    private EmbedResponse doEmbed(EmbedRequest request) throws IOException {
        if (!isAvailable()) {
            throw new LLMException("OpenRouter API key not configured", "openrouter", null, -1);
        }

        // Use embedding model from request modelSpec or default
        String model = DEFAULT_EMBEDDING_MODEL;
        if (request.getModelSpec() != null && request.getModelSpec().getModel() != null) {
            model = request.getModelSpec().getModel();
        }

        // Build request body
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);

        // Support single text or batch
        List<String> texts = request.getTexts();
        if (texts != null && !texts.isEmpty()) {
            body.put("input", texts);
        } else {
            return EmbedResponse.error("No text provided for embedding");
        }

        String json = objectMapper.writeValueAsString(body);
        LOG.debug("OpenRouter embedding request: model={}, texts={}", model, texts.size());

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/embeddings")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://gensparql.org")
                .addHeader("X-Title", "GenSPARQL")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                LOG.error("OpenRouter embedding API error: {} - {}", response.code(), responseBody);
                return EmbedResponse.error("OpenRouter embedding API error: " + response.code() +
                        " - " + truncate(responseBody, 200));
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode data = root.path("data");

            if (!data.isArray() || data.size() == 0) {
                LOG.error("OpenRouter embedding response has no data: {}", responseBody);
                return EmbedResponse.error("No embeddings in response");
            }

            // Parse embeddings
            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embeddingNode = item.path("embedding");
                if (embeddingNode.isArray()) {
                    float[] embedding = new float[embeddingNode.size()];
                    for (int i = 0; i < embeddingNode.size(); i++) {
                        embedding[i] = (float) embeddingNode.get(i).asDouble();
                    }
                    embeddings.add(embedding);
                }
            }

            LOG.debug("OpenRouter embedding response: {} embeddings", embeddings.size());
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
