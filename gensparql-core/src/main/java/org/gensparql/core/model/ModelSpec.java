package org.gensparql.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Specification for a generative model M in GenOp(StrX, Y, M).
 *
 * ModelSpec encapsulates:
 * - Provider name (e.g., "openai", "anthropic", "openrouter")
 * - Model identifier (e.g., "gpt-4", "claude-3-opus")
 * - Model parameters (e.g., temperature, maxTokens)
 *
 * URI format: model:{provider}:{model}?param1=value1&param2=value2
 * Examples:
 * - model:openai:gpt-4
 * - model:anthropic:claude-3-opus?temperature=0.7
 * - model:openrouter:deepseek/deepseek-chat
 */
public class ModelSpec {
    private final String provider;
    private final String model;
    private final Map<String, Object> parameters;

    private ModelSpec(Builder builder) {
        this.provider = Objects.requireNonNull(builder.provider, "provider cannot be null");
        this.model = Objects.requireNonNull(builder.model, "model cannot be null");
        this.parameters = Collections.unmodifiableMap(new HashMap<>(builder.parameters));
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    @SuppressWarnings("unchecked")
    public <T> T getParameter(String key, T defaultValue) {
        Object value = parameters.get(key);
        if (value == null) {
            return defaultValue;
        }
        return (T) value;
    }

    public double getTemperature() {
        return getNumber("temperature", 0.7).doubleValue();
    }

    public int getMaxTokens() {
        return getNumber("maxTokens", 1000).intValue();
    }

    public int getTimeout() {
        return getNumber("timeout", 30000).intValue();
    }

    /**
     * Fetch a numeric parameter tolerating either Integer or Double storage.
     * Query params parse "temperature=0" to Integer but "temperature=0.5" to
     * Double (see {@link #parseValue}), so a plain {@code (Double) value} cast
     * would throw ClassCastException for integer-valued inputs.
     */
    private Number getNumber(String key, Number defaultValue) {
        Object value = parameters.get(key);
        if (value instanceof Number) {
            return (Number) value;
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                // not numeric - use default
            }
        }
        return defaultValue;
    }

    /**
     * Parse a model URI into a ModelSpec.
     *
     * Format: model:{provider}:{model}[?params]
     * Examples:
     * - model:openai:gpt-4
     * - model:anthropic:claude-3-opus?temperature=0.7&maxTokens=500
     *
     * @param uri the model URI
     * @return parsed ModelSpec
     */
    public static ModelSpec fromURI(String uri) {
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("Model URI cannot be null or empty");
        }

        // Handle full URI format: <model:openai:gpt-4>
        String normalizedUri = uri;
        if (normalizedUri.startsWith("<") && normalizedUri.endsWith(">")) {
            normalizedUri = normalizedUri.substring(1, normalizedUri.length() - 1);
        }

        // Split query parameters
        String basePart;
        Map<String, Object> params = new HashMap<>();
        int queryIndex = normalizedUri.indexOf('?');
        if (queryIndex >= 0) {
            basePart = normalizedUri.substring(0, queryIndex);
            String queryString = normalizedUri.substring(queryIndex + 1);
            params = parseQueryParams(queryString);
        } else {
            basePart = normalizedUri;
        }

        // Parse model:provider:model format
        if (!basePart.startsWith("model:")) {
            throw new IllegalArgumentException("Invalid model URI format. Expected 'model:provider:model', got: " + uri);
        }

        String remainder = basePart.substring(6); // Remove "model:"
        int colonIndex = remainder.indexOf(':');
        if (colonIndex < 0) {
            throw new IllegalArgumentException("Invalid model URI format. Expected 'model:provider:model', got: " + uri);
        }

        String provider = remainder.substring(0, colonIndex);
        String model = remainder.substring(colonIndex + 1);

        return builder()
                .provider(provider)
                .model(model)
                .parameters(params)
                .build();
    }

    private static Map<String, Object> parseQueryParams(String queryString) {
        Map<String, Object> params = new HashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return params;
        }

        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int eqIndex = pair.indexOf('=');
            if (eqIndex > 0) {
                String key = pair.substring(0, eqIndex);
                String value = pair.substring(eqIndex + 1);
                params.put(key, parseValue(value));
            }
        }
        return params;
    }

    private static Object parseValue(String value) {
        // Try to parse as number
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            } else {
                return Integer.parseInt(value);
            }
        } catch (NumberFormatException e) {
            // Return as string if not a number
            if ("true".equalsIgnoreCase(value)) {
                return true;
            } else if ("false".equalsIgnoreCase(value)) {
                return false;
            }
            return value;
        }
    }

    /**
     * Convert to URI string representation.
     *
     * @return URI string
     */
    public String toURI() {
        StringBuilder sb = new StringBuilder();
        sb.append("model:").append(provider).append(":").append(model);

        if (!parameters.isEmpty()) {
            sb.append("?");
            boolean first = true;
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (!first) {
                    sb.append("&");
                }
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }

        return sb.toString();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModelSpec modelSpec = (ModelSpec) o;
        return Objects.equals(provider, modelSpec.provider) &&
               Objects.equals(model, modelSpec.model) &&
               Objects.equals(parameters, modelSpec.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, model, parameters);
    }

    @Override
    public String toString() {
        return "ModelSpec{" +
               "provider='" + provider + '\'' +
               ", model='" + model + '\'' +
               ", parameters=" + parameters +
               '}';
    }

    public static class Builder {
        private String provider;
        private String model;
        private Map<String, Object> parameters = new HashMap<>();

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder parameter(String key, Object value) {
            this.parameters.put(key, value);
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters.putAll(parameters);
            return this;
        }

        public Builder temperature(double temperature) {
            return parameter("temperature", temperature);
        }

        public Builder maxTokens(int maxTokens) {
            return parameter("maxTokens", maxTokens);
        }

        public Builder timeout(int timeout) {
            return parameter("timeout", timeout);
        }

        public ModelSpec build() {
            return new ModelSpec(this);
        }
    }
}
