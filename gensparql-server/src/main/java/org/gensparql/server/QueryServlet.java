package org.gensparql.server;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.sparql.util.Context;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.engine.boot.GenSPARQL;
import org.gensparql.engine.GenSPARQLConstants;
import org.gensparql.engine.similarity.EmbeddingSimText;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.provider.OpenAIProvider;
import org.gensparql.parser.GenSPARQLQueryFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class QueryServlet extends JsonServlet {
    private final DatasetRegistry registry;

    QueryServlet(DatasetRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            JsonNode body = JSON.readTree(request.getInputStream());
            String datasetId = requiredText(body, "datasetId");
            String queryText = requiredText(body, "query");
            Dataset dataset = registry.get(datasetId);
            if (dataset == null) {
                json(response, HttpServletResponse.SC_NOT_FOUND,
                        java.util.Map.of("error", "Unknown datasetId: " + datasetId));
                return;
            }

            Query query = GenSPARQLQueryFactory.create(queryText);
            if (!query.isSelectType() && !query.isAskType()) {
                throw new IllegalArgumentException("The UI endpoint currently supports SELECT and ASK queries");
            }

            LLMProvider requestProvider = null;
            LLMProvider embeddingProvider = null;
            String requestApiKey = null;
            Context context = org.apache.jena.query.ARQ.getContext().copy();
            // Interactive API clients need the real provider error, not a misleading
            // successful response containing zero rows.
            context.set(GenSPARQL.FAIL_ON_LLM_ERROR, true);
            JsonNode llm = body.path("llm");
            if (llm.isObject()) {
                String apiKey = optionalText(llm, "apiKey");
                requestApiKey = apiKey;
                String baseUrl = optionalText(llm, "baseUrl");
                String model = optionalText(llm, "model");
                if (baseUrl != null && !baseUrl.matches("(?i)^https?://.+")) {
                    throw new IllegalArgumentException("llm.baseUrl must be an http:// or https:// URL");
                }
                if (model != null && (model.length() > 200 || model.matches(".*[<>\\s].*"))) {
                    throw new IllegalArgumentException("llm.model contains invalid characters");
                }
                if (baseUrl != null) baseUrl = baseUrl.replaceFirst("/+$", "");
                if (apiKey != null || baseUrl != null || model != null) {
                    requestProvider = new OpenAIProvider(apiKey == null ? "" : apiKey, baseUrl);
                    context.set(GenSPARQL.LLM_PROVIDER, requestProvider);
                    if (model != null) {
                        context.set(GenSPARQL.DEFAULT_MODEL,
                                ModelSpec.fromURI("model:openai:" + model));
                    }
                }
            }

            JsonNode similarity = body.path("similarity");
            if (similarity.isObject()) {
                String method = optionalText(similarity, "method");
                if (method == null || "jaccard".equalsIgnoreCase(method) || "text".equalsIgnoreCase(method)) {
                    context.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
                } else if ("embedding".equalsIgnoreCase(method)) {
                    String baseUrl = optionalText(similarity, "baseUrl");
                    String model = optionalText(similarity, "model");
                    String apiKey = optionalText(similarity, "apiKey");
                    if (baseUrl == null || !baseUrl.matches("(?i)^https?://.+")) {
                        throw new IllegalArgumentException("similarity.baseUrl must be an http:// or https:// URL");
                    }
                    if (model == null || model.length() > 200 || model.matches(".*[<>\\s].*")) {
                        throw new IllegalArgumentException("similarity.model is required and must not contain spaces or angle brackets");
                    }
                    embeddingProvider = new OpenAIProvider(
                            apiKey == null ? (requestApiKey == null ? "" : requestApiKey) : apiKey,
                            baseUrl);
                    context.set(GenSPARQLConstants.SIM_TEXT,
                            new EmbeddingSimText(new JaccardSimText(), embeddingProvider,
                                    ModelSpec.fromURI("model:openai:" + model)));
                } else {
                    throw new IllegalArgumentException("similarity.method must be jaccard or embedding");
                }
            }

            // Materialize the response before committing HTTP headers. GENOP is evaluated
            // lazily while ResultSetFormatter iterates, so writing directly to the servlet
            // stream can leave clients with truncated JSON if an LLM call fails mid-query.
            ByteArrayOutputStream resultBuffer = new ByteArrayOutputStream();
            dataset.begin(ReadWrite.READ);
            try (QueryExecution execution = GenSPARQL.createQueryExecution(query, dataset, context)) {
                if (query.isSelectType()) {
                    ResultSetFormatter.outputAsJSON(resultBuffer, execution.execSelect());
                } else {
                    JSON.writeValue(resultBuffer, java.util.Map.of("boolean", execution.execAsk()));
                }
            } finally {
                dataset.end();
                if (requestProvider != null) requestProvider.close();
                if (embeddingProvider != null) embeddingProvider.close();
            }
            response.setStatus(HttpServletResponse.SC_OK);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/sparql-results+json");
            response.setContentLength(resultBuffer.size());
            resultBuffer.writeTo(response.getOutputStream());
        } catch (IllegalArgumentException e) {
            if (!response.isCommitted()) error(response, HttpServletResponse.SC_BAD_REQUEST, e);
        } catch (Exception e) {
            if (!response.isCommitted()) error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e);
        }
    }

    private static String requiredText(JsonNode body, String field) {
        String value = body == null ? null : body.path(field).asText(null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String optionalText(JsonNode body, String field) {
        String value = body.path(field).asText(null);
        if (value == null || value.isBlank()) return null;
        if (value.length() > 2048) throw new IllegalArgumentException("llm." + field + " is too long");
        return value.trim();
    }
}
