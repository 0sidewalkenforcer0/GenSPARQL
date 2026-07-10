package org.gensparql.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Request for embedding generation in GenSPARQL.
 *
 * Used by gen:embedding() function and similarity computations.
 */
public class EmbedRequest {
    private final List<String> texts;
    private final ModelSpec modelSpec;

    public EmbedRequest(String text, ModelSpec modelSpec) {
        this(Collections.singletonList(text), modelSpec);
    }

    public EmbedRequest(List<String> texts, ModelSpec modelSpec) {
        this.texts = Objects.requireNonNull(texts, "texts cannot be null");
        this.modelSpec = modelSpec;
    }

    public List<String> getTexts() {
        return texts;
    }

    public String getFirstText() {
        return texts.isEmpty() ? null : texts.get(0);
    }

    public ModelSpec getModelSpec() {
        return modelSpec;
    }

    public boolean isBatch() {
        return texts.size() > 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmbedRequest that = (EmbedRequest) o;
        return Objects.equals(texts, that.texts) &&
               Objects.equals(modelSpec, that.modelSpec);
    }

    @Override
    public int hashCode() {
        return Objects.hash(texts, modelSpec);
    }

    @Override
    public String toString() {
        return "EmbedRequest{" +
               "texts=" + texts.size() + " items" +
               ", modelSpec=" + modelSpec +
               '}';
    }
}
