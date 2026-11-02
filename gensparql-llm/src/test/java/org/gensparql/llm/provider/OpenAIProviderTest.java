package org.gensparql.llm.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
import org.gensparql.core.model.ModelSpec;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keepsApiRoot() {
        assertEquals("https://api.groq.com/openai/v1",
                OpenAIProvider.normalizeBaseUrl("https://api.groq.com/openai/v1/"));
    }

    @Test
    void acceptsFullEndpointWithoutDuplicatingItsPath() {
        assertEquals("https://api.groq.com/openai/v1",
                OpenAIProvider.normalizeBaseUrl(
                        "https://api.groq.com/openai/v1/chat/completions"));
    }

    @Test
    void rejectsNonHttpBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> OpenAIProvider.normalizeBaseUrl("api.groq.com/openai/v1"));
    }

    @Test
    void singleVariableListYieldsOneBindingPerValue() throws Exception {
        List<String> sent = new ArrayList<>();
        HttpServer server = chatStub("[\"Physics\", \"Chemistry\", \"Literature\"]", sent);
        OpenAIProvider provider = new OpenAIProvider("test", baseUrl(server));
        try {
            GenerateResponse response = provider.generateSync(GenerateRequest.builder()
                    .prompt("List the official Nobel Prize fields.")
                    .modelSpec(ModelSpec.fromURI("model:openai:test"))
                    .outputVariables(List.of("field"))
                    .build());

            assertTrue(response.isSuccess(), response.getErrorMessage());
            assertEquals(List.of("Physics", "Chemistry", "Literature"),
                    response.getBindings().stream().map(b -> b.get("field")).toList());
            assertTrue(sent.get(0).contains("JSON array of strings"));
            assertFalse(sent.get(0).contains("one valid JSON object"));
        } finally {
            provider.close();
            server.stop(0);
        }
    }

    @Test
    void batchedPromptIsSentWithoutASecondFormatBlock() throws Exception {
        List<String> sent = new ArrayList<>();
        HttpServer server = chatStub("[{\"prompt_id\": 1, \"pos\": \"Forward\"}]", sent);
        OpenAIProvider provider = new OpenAIProvider("test", baseUrl(server));
        String batched = "PROMPT 1:\nPosition of Messi?\n\nReturn a JSON array with exactly 1 objects.";
        try {
            provider.generateSync(GenerateRequest.builder()
                    .prompt(batched)
                    .modelSpec(ModelSpec.fromURI("model:openai:test"))
                    .outputVariables(List.of("pos"))
                    .outputFormat(GenerateRequest.OutputFormat.JSON_ARRAY)
                    .build());

            assertEquals(batched, sent.get(0));
        } finally {
            provider.close();
            server.stop(0);
        }
    }

    /**
     * Serves one canned chat completion and records the prompt of every request.
     */
    private static HttpServer chatStub(String content, List<String> sentPrompts) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            Map<?, ?> body = JSON.readValue(exchange.getRequestBody(), Map.class);
            Map<?, ?> message = (Map<?, ?>) ((List<?>) body.get("messages")).get(0);
            sentPrompts.add((String) message.get("content"));
            byte[] reply = JSON.writeValueAsBytes(Map.of(
                    "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content))),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
}
