package org.gensparql.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Response from embedding generation in GenSPARQL.
 */
public class EmbedResponse {
    private final List<float[]> embeddings;
    private final boolean success;
    private final String errorMessage;
    private final int dimensions;

    private EmbedResponse(Builder builder) {
        this.embeddings = builder.embeddings != null ?
                Collections.unmodifiableList(builder.embeddings) : Collections.emptyList();
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.dimensions = builder.dimensions;
    }

    public List<float[]> getEmbeddings() {
        return embeddings;
    }

    public float[] getFirstEmbedding() {
        return embeddings.isEmpty() ? null : embeddings.get(0);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getDimensions() {
        return dimensions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static EmbedResponse success(List<float[]> embeddings) {
        int dim = embeddings.isEmpty() ? 0 : embeddings.get(0).length;
        return builder()
                .embeddings(embeddings)
                .dimensions(dim)
                .success(true)
                .build();
    }

    public static EmbedResponse success(float[] embedding) {
        return success(Collections.singletonList(embedding));
    }

    public static EmbedResponse error(String errorMessage) {
        return builder()
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmbedResponse that = (EmbedResponse) o;
        return success == that.success &&
               dimensions == that.dimensions;
    }

    @Override
    public int hashCode() {
        return Objects.hash(success, dimensions);
    }

    @Override
    public String toString() {
        return "EmbedResponse{" +
               "success=" + success +
               ", embeddings=" + embeddings.size() +
               ", dimensions=" + dimensions +
               (errorMessage != null ? ", error='" + errorMessage + '\'' : "") +
               '}';
    }

    public static class Builder {
        private List<float[]> embeddings;
        private boolean success = true;
        private String errorMessage;
        private int dimensions;

        public Builder embeddings(List<float[]> embeddings) {
            this.embeddings = embeddings;
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

        public Builder dimensions(int dimensions) {
            this.dimensions = dimensions;
            return this;
        }

        public EmbedResponse build() {
            return new EmbedResponse(this);
        }
    }
}
