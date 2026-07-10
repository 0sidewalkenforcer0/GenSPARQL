package org.gensparql.core.model;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * Result of entity grounding - mapping LLM free-text output to a KG entity.
 *
 * Contains the original LLM output, the grounded entity (if found),
 * and the similarity score used for the match.
 */
public class GroundingResult {

    private final String originalValue;
    private final String groundedLabel;
    private final String groundedUri;
    private final double similarity;
    private final boolean grounded;

    private GroundingResult(Builder builder) {
        this.originalValue = builder.originalValue;
        this.groundedLabel = builder.groundedLabel;
        this.groundedUri = builder.groundedUri;
        this.similarity = builder.similarity;
        this.grounded = builder.grounded;
    }

    /**
     * Get the original LLM output value.
     */
    public String getOriginalValue() {
        return originalValue;
    }

    /**
     * Get the grounded entity label (human-readable form).
     * Returns null if not grounded.
     */
    public String getGroundedLabel() {
        return groundedLabel;
    }

    /**
     * Get the grounded entity URI.
     * Returns null if the entity is a literal or not grounded.
     */
    public String getGroundedUri() {
        return groundedUri;
    }

    /**
     * Get the similarity score between original value and grounded entity.
     * Returns 0.0 if not grounded.
     */
    public double getSimilarity() {
        return similarity;
    }

    /**
     * Check if the grounding was successful (similarity >= threshold).
     */
    public boolean isGrounded() {
        return grounded;
    }

    /**
     * Get the value to use - grounded label if grounded, otherwise original.
     */
    public String getValue() {
        return grounded && groundedLabel != null ? groundedLabel : originalValue;
    }

    /**
     * Convert to a Jena Node for use in SPARQL bindings.
     *
     * If grounded to a URI, returns a URI node.
     * Otherwise returns a literal string node.
     */
    public Node toNode() {
        if (grounded && groundedUri != null) {
            return NodeFactory.createURI(groundedUri);
        } else if (grounded && groundedLabel != null) {
            return NodeFactory.createLiteralString(groundedLabel);
        } else {
            return NodeFactory.createLiteralString(originalValue);
        }
    }

    /**
     * Create an ungrounded result (no match found).
     */
    public static GroundingResult ungrounded(String originalValue) {
        return new Builder()
                .originalValue(originalValue)
                .grounded(false)
                .similarity(0.0)
                .build();
    }

    /**
     * Create a grounded result with label only (no URI).
     */
    public static GroundingResult grounded(String originalValue, String groundedLabel, double similarity) {
        return new Builder()
                .originalValue(originalValue)
                .groundedLabel(groundedLabel)
                .similarity(similarity)
                .grounded(true)
                .build();
    }

    /**
     * Create a grounded result with both label and URI.
     */
    public static GroundingResult groundedWithUri(String originalValue, String groundedLabel,
                                                   String groundedUri, double similarity) {
        return new Builder()
                .originalValue(originalValue)
                .groundedLabel(groundedLabel)
                .groundedUri(groundedUri)
                .similarity(similarity)
                .grounded(true)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        if (grounded) {
            return String.format("GroundingResult{grounded='%s'->'%s' (sim=%.3f, uri=%s)}",
                    originalValue, groundedLabel, similarity, groundedUri);
        } else {
            return String.format("GroundingResult{ungrounded='%s'}", originalValue);
        }
    }

    public static class Builder {
        private String originalValue;
        private String groundedLabel;
        private String groundedUri;
        private double similarity;
        private boolean grounded;

        public Builder originalValue(String originalValue) {
            this.originalValue = originalValue;
            return this;
        }

        public Builder groundedLabel(String groundedLabel) {
            this.groundedLabel = groundedLabel;
            return this;
        }

        public Builder groundedUri(String groundedUri) {
            this.groundedUri = groundedUri;
            return this;
        }

        public Builder similarity(double similarity) {
            this.similarity = similarity;
            return this;
        }

        public Builder grounded(boolean grounded) {
            this.grounded = grounded;
            return this;
        }

        public GroundingResult build() {
            return new GroundingResult(this);
        }
    }
}
