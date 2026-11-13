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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Anthropic provider asks for the same output format and reads the answer with the same
 * parser as the other providers, against a local stub of the Messages API.
 */
class AnthropicProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void asksForAJsonArrayAndBindsEveryValue() throws Exception {
        List<String> sent = new ArrayList<>();
        HttpServer server = messagesStub("[\"Physics\", \"Chemistry\", \"Literature\"]", sent);
        AnthropicProvider provider = new AnthropicProvider("test", baseUrl(server));
        try {
            GenerateResponse response = provider.generateSync(request(List.of("field")));

            assertTrue(response.isSuccess(), response.getErrorMessage());
            assertEquals(List.of("Physics", "Chemistry", "Literature"),
                    response.getBindings().stream().map(b -> b.get("field")).toList());
            assertTrue(sent.get(0).contains("JSON array"), "the format instruction is sent");
        } finally {
            provider.close();
            server.stop(0);
        }
    }

    @Test
    void anEmptyArrayYieldsNoBinding() throws Exception {
        HttpServer server = messagesStub("[]", new ArrayList<>());
        AnthropicProvider provider = new AnthropicProvider("test", baseUrl(server));
        try {
            GenerateResponse response = provider.generateSync(request(List.of("field")));

            assertTrue(response.isSuccess(), response.getErrorMessage());
            assertEquals(List.of(), response.getBindings());
        } finally {
            provider.close();
            server.stop(0);
        }
    }

    @Test
    void anArrayOfObjectsBindsSeveralVariables() throws Exception {
        HttpServer server = messagesStub(
                "```json\n[{\"name\": \"Marie Curie\", \"year\": \"1903\"}]\n```", new ArrayList<>());
        AnthropicProvider provider = new AnthropicProvider("test", baseUrl(server));
        try {
            GenerateResponse response = provider.generateSync(request(List.of("name", "year")));

            assertTrue(response.isSuccess(), response.getErrorMessage());
            assertEquals(List.of(Map.of("name", "Marie Curie", "year", "1903")), response.getBindings());
        } finally {
            provider.close();
            server.stop(0);
        }
    }

    private static GenerateRequest request(List<String> outputVariables) {
        return GenerateRequest.builder()
                .prompt("List the official Nobel Prize fields.")
                .modelSpec(ModelSpec.fromURI("model:anthropic:test"))
                .outputVariables(outputVariables)
                .build();
    }

    private static HttpServer messagesStub(String text, List<String> sentPrompts) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            Map<?, ?> body = JSON.readValue(exchange.getRequestBody(), Map.class);
            Map<?, ?> message = (Map<?, ?>) ((List<?>) body.get("messages")).get(0);
            sentPrompts.add((String) message.get("content"));
            byte[] reply = JSON.writeValueAsBytes(Map.of(
                    "id", "msg_test",
                    "content", List.of(Map.of("type", "text", "text", text)),
                    "usage", Map.of("input_tokens", 1, "output_tokens", 1)));
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
