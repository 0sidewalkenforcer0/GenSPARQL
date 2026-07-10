package org.gensparql.llm.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.gensparql.core.exception.LLMException;
import org.gensparql.core.model.*;
import org.gensparql.llm.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Anthropic Claude LLM provider implementation.
 */
public class AnthropicProvider implements LLMProvider {
    private static final Logger LOG = LoggerFactory.getLogger(AnthropicProvider.class);

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    private static final String DEFAULT_MODEL = "claude-3-5-sonnet-20241022";
    private static final String API_VERSION = "2023-06-01";

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;

    public AnthropicProvider() {
        this(System.getenv("ANTHROPIC_API_KEY"));
    }

    public AnthropicProvider(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public AnthropicProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.objectMapper = new ObjectMapper();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "anthropic";
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
        // Anthropic doesn't have a native embedding API
        return false;
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
            throw new LLMException("Anthropic API key not configured", "anthropic", null, -1);
        }

        long startTime = System.currentTimeMillis();
        String model = request.getModelSpec() != null ?
                request.getModelSpec().getModel() : DEFAULT_MODEL;

        // Build request body (Anthropic Messages API format)
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        body.put("messages", List.of(
                Map.of("role", "user", "content", request.getPrompt())
        ));

        // Add temperature if not default
        if (request.getTemperature() != 1.0) {
            body.put("temperature", request.getTemperature());
        }

        String json = objectMapper.writeValueAsString(body);
        LOG.debug("Anthropic request: {}", truncate(json, 200));

        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", API_VERSION)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                LOG.error("Anthropic API error: {} - {}", response.code(), responseBody);
                throw new LLMException("Anthropic API error: " + response.code(),
                        "anthropic", model, response.code());
            }

            JsonNode root = objectMapper.readTree(responseBody);

            // Extract content from response
            StringBuilder contentBuilder = new StringBuilder();
            JsonNode contentArray = root.path("content");
            for (JsonNode contentBlock : contentArray) {
                if ("text".equals(contentBlock.path("type").asText())) {
                    contentBuilder.append(contentBlock.path("text").asText());
                }
            }
            String content = contentBuilder.toString();

            // Parse usage info
            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);
            long latency = System.currentTimeMillis() - startTime;

            GenerateResponse.ResponseMetadata metadata = new GenerateResponse.ResponseMetadata(
                    model, inputTokens, outputTokens, latency,
                    Instant.now(), root.path("id").asText()
            );

            LOG.debug("Anthropic response: {} tokens in {}ms", inputTokens + outputTokens, latency);

            // Parse output into bindings
            List<Map<String, String>> bindings = parseOutput(content, request.getOutputVariables());

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
        // Anthropic doesn't have embedding API, return error
        return CompletableFuture.completedFuture(
                EmbedResponse.error("Anthropic does not support embedding API")
        );
    }

    private List<Map<String, String>> parseOutput(String content, List<String> outputVars) {
        List<Map<String, String>> bindings = new ArrayList<>();

        if (outputVars == null || outputVars.isEmpty()) {
            bindings.add(Map.of("result", content.trim()));
            return bindings;
        }

        if (outputVars.size() == 1) {
            bindings.add(Map.of(outputVars.get(0), content.trim()));
            return bindings;
        }

        try {
            JsonNode json = objectMapper.readTree(content);
            Map<String, String> binding = new HashMap<>();
            for (String var : outputVars) {
                if (json.has(var)) {
                    binding.put(var, json.get(var).asText());
                }
            }
            if (!binding.isEmpty()) {
                bindings.add(binding);
            }
        } catch (Exception e) {
            LOG.debug("Could not parse JSON output, using raw text");
            bindings.add(Map.of(outputVars.get(0), content.trim()));
        }

        return bindings;
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
