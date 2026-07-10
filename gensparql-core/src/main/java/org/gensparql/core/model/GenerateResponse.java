package org.gensparql.core.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Response from LLM generation in GenSPARQL.
 *
 * Contains the generated text/values and metadata about the generation.
 */
public class GenerateResponse {
    private final String rawText;
    private final List<Map<String, String>> bindings;
    private final ResponseMetadata metadata;
    private final boolean success;
    private final String errorMessage;

    private GenerateResponse(Builder builder) {
        this.rawText = builder.rawText;
        this.bindings = builder.bindings != null ?
                Collections.unmodifiableList(builder.bindings) : Collections.emptyList();
        this.metadata = builder.metadata;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
    }

    /**
     * Get the raw text response from the LLM.
     */
    public String getRawText() {
        return rawText;
    }

    /**
     * Get parsed bindings from the response.
     * Each map represents one solution mapping: variable name -> value.
     */
    public List<Map<String, String>> getBindings() {
        return bindings;
    }

    /**
     * Get response metadata (timing, token usage, etc.).
     */
    public ResponseMetadata getMetadata() {
        return metadata;
    }

    /**
     * Check if the generation was successful.
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Get error message if generation failed.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Check if the response has bindings.
     */
    public boolean hasBindings() {
        return bindings != null && !bindings.isEmpty();
    }

    /**
     * Get the first binding value for a variable (convenience method).
     */
    public String getFirstValue(String variable) {
        if (bindings == null || bindings.isEmpty()) {
            return null;
        }
        return bindings.get(0).get(variable);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static GenerateResponse success(String rawText) {
        return builder()
                .rawText(rawText)
                .success(true)
                .build();
    }

    public static GenerateResponse success(String rawText, List<Map<String, String>> bindings) {
        return builder()
                .rawText(rawText)
                .bindings(bindings)
                .success(true)
                .build();
    }

    public static GenerateResponse error(String errorMessage) {
        return builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GenerateResponse that = (GenerateResponse) o;
        return success == that.success &&
               Objects.equals(rawText, that.rawText) &&
               Objects.equals(bindings, that.bindings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawText, bindings, success);
    }

    @Override
    public String toString() {
        return "GenerateResponse{" +
               "success=" + success +
               ", rawText='" + truncate(rawText, 50) + '\'' +
               ", bindingsCount=" + (bindings != null ? bindings.size() : 0) +
               (errorMessage != null ? ", error='" + errorMessage + '\'' : "") +
               '}';
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Metadata about the generation response.
     */
    public static class ResponseMetadata {
        private final String model;
        private final int promptTokens;
        private final int completionTokens;
        private final long latencyMs;
        private final Instant timestamp;
        private final String requestId;

        public ResponseMetadata(String model, int promptTokens, int completionTokens,
                               long latencyMs, Instant timestamp, String requestId) {
            this.model = model;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.latencyMs = latencyMs;
            this.timestamp = timestamp;
            this.requestId = requestId;
        }

        public String getModel() {
            return model;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public int getTotalTokens() {
            return promptTokens + completionTokens;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public String getRequestId() {
            return requestId;
        }

        @Override
        public String toString() {
            return "ResponseMetadata{" +
                   "model='" + model + '\'' +
                   ", promptTokens=" + promptTokens +
                   ", completionTokens=" + completionTokens +
                   ", latencyMs=" + latencyMs +
                   '}';
        }
    }

    public static class Builder {
        private String rawText;
        private List<Map<String, String>> bindings;
        private ResponseMetadata metadata;
        private boolean success = true;
        private String errorMessage;

        public Builder rawText(String rawText) {
            this.rawText = rawText;
            return this;
        }

        public Builder bindings(List<Map<String, String>> bindings) {
            this.bindings = bindings;
            return this;
        }

        public Builder metadata(ResponseMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public GenerateResponse build() {
            return new GenerateResponse(this);
        }
    }
}
