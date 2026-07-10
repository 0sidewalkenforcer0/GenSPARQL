package org.gensparql.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Request for LLM generation in GenSPARQL.
 *
 * Represents an instantiated prompt ready to be sent to an LLM.
 * The prompt template has been resolved with variable bindings.
 */
public class GenerateRequest {
    private final String prompt;
    private final ModelSpec modelSpec;
    private final List<String> outputVariables;
    private final OutputFormat outputFormat;

    public GenerateRequest(String prompt, ModelSpec modelSpec, List<String> outputVariables) {
        this(prompt, modelSpec, outputVariables, OutputFormat.TEXT);
    }

    public GenerateRequest(String prompt, ModelSpec modelSpec, List<String> outputVariables, OutputFormat outputFormat) {
        this.prompt = Objects.requireNonNull(prompt, "prompt cannot be null");
        this.modelSpec = Objects.requireNonNull(modelSpec, "modelSpec cannot be null");
        this.outputVariables = outputVariables != null ?
                Collections.unmodifiableList(outputVariables) : Collections.emptyList();
        this.outputFormat = outputFormat != null ? outputFormat : OutputFormat.TEXT;
    }

    public String getPrompt() {
        return prompt;
    }

    public ModelSpec getModelSpec() {
        return modelSpec;
    }

    public List<String> getOutputVariables() {
        return outputVariables;
    }

    public OutputFormat getOutputFormat() {
        return outputFormat;
    }

    public double getTemperature() {
        return modelSpec.getTemperature();
    }

    public int getMaxTokens() {
        return modelSpec.getMaxTokens();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GenerateRequest that = (GenerateRequest) o;
        return Objects.equals(prompt, that.prompt) &&
               Objects.equals(modelSpec, that.modelSpec) &&
               Objects.equals(outputVariables, that.outputVariables) &&
               outputFormat == that.outputFormat;
    }

    @Override
    public int hashCode() {
        return Objects.hash(prompt, modelSpec, outputVariables, outputFormat);
    }

    @Override
    public String toString() {
        return "GenerateRequest{" +
               "prompt='" + truncate(prompt, 50) + '\'' +
               ", modelSpec=" + modelSpec +
               ", outputVariables=" + outputVariables +
               ", outputFormat=" + outputFormat +
               '}';
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Output format for LLM responses.
     */
    public enum OutputFormat {
        /**
         * Plain text output (single value).
         */
        TEXT,

        /**
         * JSON object output (multiple named values).
         */
        JSON,

        /**
         * JSON array output (list of values).
         */
        JSON_ARRAY,

        /**
         * CSV table output (multiple rows).
         */
        CSV
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String prompt;
        private ModelSpec modelSpec;
        private List<String> outputVariables;
        private OutputFormat outputFormat = OutputFormat.TEXT;

        public Builder prompt(String prompt) {
            this.prompt = prompt;
            return this;
        }

        public Builder modelSpec(ModelSpec modelSpec) {
            this.modelSpec = modelSpec;
            return this;
        }

        public Builder outputVariables(List<String> outputVariables) {
            this.outputVariables = outputVariables;
            return this;
        }

        public Builder outputFormat(OutputFormat outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        public GenerateRequest build() {
            return new GenerateRequest(prompt, modelSpec, outputVariables, outputFormat);
        }
    }
}
