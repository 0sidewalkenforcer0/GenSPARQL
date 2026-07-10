package org.gensparql.core.model;

import org.apache.jena.graph.Node;

import java.util.Objects;

/**
 * A value with source type annotation.
 *
 * Wraps a Jena Node with its origin type (RDF or GEN).
 * This implements the paper's notion of typed solution mappings:
 * μ : V → T × ζ where ζ ∈ {rdf, gen}
 */
public class TypedValue {

    private final Node node;
    private final SourceType sourceType;

    /**
     * Create a typed value.
     *
     * @param node       the RDF node
     * @param sourceType the source type (RDF or GEN)
     */
    public TypedValue(Node node, SourceType sourceType) {
        this.node = node;
        this.sourceType = sourceType != null ? sourceType : SourceType.RDF;
    }

    /**
     * Create a typed value with RDF source type.
     *
     * @param node the RDF node
     * @return typed value with RDF source
     */
    public static TypedValue rdf(Node node) {
        return new TypedValue(node, SourceType.RDF);
    }

    /**
     * Create a typed value with GEN source type.
     *
     * @param node the RDF node (LLM-generated)
     * @return typed value with GEN source
     */
    public static TypedValue gen(Node node) {
        return new TypedValue(node, SourceType.GEN);
    }

    /**
     * Get the underlying RDF node.
     *
     * @return the node
     */
    public Node getNode() {
        return node;
    }

    /**
     * Get the source type.
     *
     * @return source type (RDF or GEN)
     */
    public SourceType getSourceType() {
        return sourceType;
    }

    /**
     * Check if this value is from RDF.
     *
     * @return true if source is RDF
     */
    public boolean isRdf() {
        return sourceType == SourceType.RDF;
    }

    /**
     * Check if this value is LLM-generated.
     *
     * @return true if source is GEN
     */
    public boolean isGen() {
        return sourceType == SourceType.GEN;
    }

    /**
     * Extract lexical form for similarity comparison.
     * Implements Lex(t) function from the paper.
     *
     * @return lexical string representation
     */
    public String getLexicalForm() {
        if (node == null) {
            return "";
        }
        if (node.isLiteral()) {
            return node.getLiteralLexicalForm();
        } else if (node.isURI()) {
            return node.getURI();
        } else if (node.isBlank()) {
            return node.getBlankNodeLabel();
        }
        return node.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TypedValue that = (TypedValue) o;
        return Objects.equals(node, that.node) && sourceType == that.sourceType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(node, sourceType);
    }

    @Override
    public String toString() {
        return "TypedValue{" +
                "node=" + node +
                ", sourceType=" + sourceType +
                '}';
    }
}
