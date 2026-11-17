package org.gensparql.test;

import com.sun.net.httpserver.HttpServer;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.rdf.model.ModelFactory;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.llm.provider.OpenAIProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * gen:similarity keeps the embeddings of each endpoint and model apart. Before, its cache held
 * them by text only, so after a change of the default provider a text kept the vector of the
 * previous endpoint.
 */
class SimilarityFunctionCacheTest {

    /** Answers "Argentina …" with one vector and every other text with another. */
    private static HttpServer endpoint(String argentina, String other, AtomicInteger requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String vector = body.contains("Argentina") ? argentina : other;
            byte[] out = ("{\"data\":[{\"embedding\":" + vector + ",\"index\":0}],\"model\":\"m\"}")
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

    private static double similarity(String a, String b) {
        String q = "PREFIX gen: <http://gensparql.org/function#> SELECT ?s WHERE { BIND(gen:similarity(\""
                + a + "\", \"" + b + "\") AS ?s) }";
        try (QueryExecution qe = QueryExecutionFactory.create(GenSPARQLQueryFactory.create(q),
                ModelFactory.createDefaultModel())) {
            return qe.execSelect().next().getLiteral("s").getDouble();
        }
    }

    @Test
    void theEmbeddingsOfAnotherEndpointAreNotReused() throws Exception {
        GenSPARQL.init();
        AtomicInteger requestsA = new AtomicInteger(), requestsB = new AtomicInteger();
        HttpServer a = endpoint("[1.0, 0.0]", "[1.0, 0.0]", requestsA);
        HttpServer b = endpoint("[1.0, 0.0]", "[0.0, 1.0]", requestsB);
        String x = "Argentina " + UUID.randomUUID(), y = "Brazil " + UUID.randomUUID();
        try {
            LLMProviderRegistry.setDefault(new OpenAIProvider("", "http://127.0.0.1:" + a.getAddress().getPort() + "/v1",
                    "", null));
            assertEquals(1.0, similarity(x, y), 1e-9);
            LLMProviderRegistry.setDefault(new OpenAIProvider("", "http://127.0.0.1:" + b.getAddress().getPort() + "/v1",
                    "", null));
            assertEquals(0.0, similarity(x, y), 1e-9);
            assertEquals(2, requestsB.get(), "B embeds both texts itself");
        } finally {
            a.stop(0);
            b.stop(0);
        }
    }
}
