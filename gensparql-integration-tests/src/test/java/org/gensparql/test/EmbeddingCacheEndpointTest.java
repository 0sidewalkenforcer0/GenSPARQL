package org.gensparql.test;

import com.sun.net.httpserver.HttpServer;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.engine.similarity.EmbeddingSimText;
import org.gensparql.llm.provider.OpenAIProvider;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cached embeddings belong to the endpoint that computed them. Two OpenAI-compatible servers can
 * serve different models under the same name, for example two Ollama servers with different
 * builds of "bge-large", and the embeddings of one must not be reused for the other. Before,
 * the cache key held only the provider's name, "openai", the model name and the text.
 */
class EmbeddingCacheEndpointTest {

    /** An embedding endpoint that answers every text with the same vector. */
    private static HttpServer endpoint(String vector, AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] out = ("{\"data\":[{\"embedding\":" + vector + ",\"index\":0}],\"model\":\"bge-large\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        return server;
    }

    private static EmbeddingSimText similarity(HttpServer server) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new EmbeddingSimText(new JaccardSimText(), new OpenAIProvider("", base, "", base),
                ModelSpec.fromURI("model:openai:bge-large"));
    }

    @Test
    void anotherEndpointWithAModelOfTheSameNameComputesItsOwnEmbeddings() throws Exception {
        AtomicInteger requestsA = new AtomicInteger(), requestsB = new AtomicInteger();
        HttpServer a = endpoint("[1.0, 0.0]", requestsA);
        HttpServer b = endpoint("[0.6, 0.8]", requestsB);
        String x = "Korea Republic " + UUID.randomUUID(), y = "South Korea " + UUID.randomUUID();
        try {
            assertEquals(1.0, similarity(a).similarity(x, y), 1e-9);
            assertEquals(2, requestsA.get());
            // Endpoint B answers both texts with the same vector as well: cosine 1. What matters is
            // that B is asked, instead of the cache answering with the vectors of A.
            assertEquals(1.0, similarity(b).similarity(x, y), 1e-9);
            assertEquals(2, requestsB.get(), "B embeds the texts itself");

            // A new provider for the same endpoint, as the service builds one per request, reuses
            // the cached embeddings.
            similarity(a).similarity(x, y);
            assertEquals(2, requestsA.get(), "the embeddings of A come from the cache");
        } finally {
            a.stop(0);
            b.stop(0);
        }
    }
}
