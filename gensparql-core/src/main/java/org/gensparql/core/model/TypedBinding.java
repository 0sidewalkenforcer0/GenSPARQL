package org.gensparql.core.model;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.binding.BindingFactory;

import java.util.*;

/**
 * Extended binding that tracks source type for each variable.
 *
 * Implements paper's μ : V → T × ζ mapping where each variable binding
 * is annotated with its source type (RDF or GEN).
 *
 * This allows semantic joins to apply different matching rules based
 * on whether values came from RDF patterns or LLM generation.
 */
public class TypedBinding {

    private final Binding baseBinding;
    private final Map<Var, SourceType> sourceTypes;

    /**
     * Create a typed binding from a standard binding.
     * All variables default to RDF source type.
     *
     * @param binding the base binding
     */
    public TypedBinding(Binding binding) {
        this.baseBinding = binding;
        this.sourceTypes = new HashMap<>();
        // Default: all RDF
        if (binding != null) {
            binding.vars().forEachRemaining(v -> sourceTypes.put(v, SourceType.RDF));
        }
    }

    /**
     * Create a typed binding with explicit source types.
     *
     * @param binding the base binding
     * @param types   map of variable to source type
     */
    public TypedBinding(Binding binding, Map<Var, SourceType> types) {
        this.baseBinding = binding;
        this.sourceTypes = new HashMap<>();
        if (binding != null) {
            // Initialize all with RDF, then override with provided types
            binding.vars().forEachRemaining(v -> sourceTypes.put(v, SourceType.RDF));
        }
        if (types != null) {
            sourceTypes.putAll(types);
        }
    }

    /**
     * Get the value for a variable.
     *
     * @param var the variable
     * @return the node value, or null if not bound
     */
    public Node get(Var var) {
        return baseBinding != null ? baseBinding.get(var) : null;
    }

    /**
     * Get the source type for a variable.
     *
     * @param var the variable
     * @return source type (defaults to RDF if not set)
     */
    public SourceType getSourceType(Var var) {
        return sourceTypes.getOrDefault(var, SourceType.RDF);
    }

    /**
     * Get the typed value for a variable.
     *
     * @param var the variable
     * @return typed value, or null if not bound
     */
    public TypedValue getTypedValue(Var var) {
        Node node = get(var);
        if (node == null) {
            return null;
        }
        SourceType type = getSourceType(var);
        return new TypedValue(node, type);
    }

    /**
     * Get the underlying Jena binding.
     *
     * @return base binding
     */
    public Binding getBaseBinding() {
        return baseBinding;
    }

    /**
     * Set the source type for a variable.
     *
     * @param var  the variable
     * @param type the source type
     */
    public void setSourceType(Var var, SourceType type) {
        sourceTypes.put(var, type);
    }

    /**
     * Get iterator over all variables.
     *
     * @return variable iterator
     */
    public Iterator<Var> vars() {
        return baseBinding != null ? baseBinding.vars() : Collections.emptyIterator();
    }

    /**
     * Get all variables as a set.
     *
     * @return set of variables
     */
    public Set<Var> varSet() {
        Set<Var> vars = new LinkedHashSet<>();
        if (baseBinding != null) {
            baseBinding.vars().forEachRemaining(vars::add);
        }
        return vars;
    }

    /**
     * Check if a variable is bound.
     *
     * @param var the variable
     * @return true if bound
     */
    public boolean contains(Var var) {
        return baseBinding != null && baseBinding.contains(var);
    }

    /**
     * Get the source types map.
     *
     * @return unmodifiable map of source types
     */
    public Map<Var, SourceType> getSourceTypes() {
        return Collections.unmodifiableMap(sourceTypes);
    }

    /**
     * Create an empty typed binding.
     *
     * @return empty typed binding
     */
    public static TypedBinding empty() {
        return new TypedBinding(BindingFactory.empty());
    }

    /**
     * Merge two TypedBindings with type-aware conflict resolution.
     *
     * Implements the union mapping μ₁ ⊎ μ₂ for typed solution mappings
     * with the following conflict resolution rules for shared variables:
     *
     * <ul>
     *   <li><b>RDF-RDF</b>: Exact match required (values must be equal, use either)</li>
     *   <li><b>RDF-GEN</b>: Prefer RDF value (ensures grounding in KG)</li>
     *   <li><b>GEN-GEN</b>: Use first value (both passed SimScore threshold θ(v))</li>
     * </ul>
     *
     * @param b1 first binding (μ₁)
     * @param b2 second binding (μ₂)
     * @return merged binding (μ₁ ⊎ μ₂)
     */
    public static TypedBinding merge(TypedBinding b1, TypedBinding b2) {
        if (b1 == null) return b2;
        if (b2 == null) return b1;

        BindingBuilder builder = BindingFactory.builder();
        Map<Var, SourceType> mergedTypes = new HashMap<>();

        // Collect all variables from both bindings
        Set<Var> allVars = new LinkedHashSet<>();
        if (b1.baseBinding != null) {
            b1.baseBinding.vars().forEachRemaining(allVars::add);
        }
        if (b2.baseBinding != null) {
            b2.baseBinding.vars().forEachRemaining(allVars::add);
        }

        // Process each variable with type-aware conflict resolution
        for (Var var : allVars) {
            boolean inB1 = b1.contains(var);
            boolean inB2 = b2.contains(var);

            if (inB1 && inB2) {
                // Shared variable - apply conflict resolution
                Node node1 = b1.get(var);
                Node node2 = b2.get(var);
                SourceType type1 = b1.getSourceType(var);
                SourceType type2 = b2.getSourceType(var);

                ResolvedValue resolved = resolveConflict(var, node1, type1, node2, type2);
                builder.add(var, resolved.node);
                mergedTypes.put(var, resolved.sourceType);

            } else if (inB1) {
                // Only in b1
                builder.add(var, b1.get(var));
                mergedTypes.put(var, b1.getSourceType(var));

            } else {
                // Only in b2
                builder.add(var, b2.get(var));
                mergedTypes.put(var, b2.getSourceType(var));
            }
        }

        return new TypedBinding(builder.build(), mergedTypes);
    }

    /**
     * Resolve value conflict for a shared variable based on source types.
     *
     * Resolution rules:
     * - RDF × RDF: Values must be equal (COM predicate ensures this), return either
     * - RDF × GEN: Return RDF value (grounding preference)
     * - GEN × RDF: Return RDF value (grounding preference)
     * - GEN × GEN: Return first value (both passed similarity threshold)
     *
     * @param var   the shared variable
     * @param node1 value from first binding
     * @param type1 source type of first value
     * @param node2 value from second binding
     * @param type2 source type of second value
     * @return resolved value with its source type
     */
    private static ResolvedValue resolveConflict(Var var, Node node1, SourceType type1,
                                                  Node node2, SourceType type2) {
        // Case 1: RDF × RDF - exact match (use first)
        if (type1 == SourceType.RDF && type2 == SourceType.RDF) {
            // COM predicate guarantees node1.equals(node2)
            return new ResolvedValue(node1, SourceType.RDF);
        }

        // Case 2: RDF × GEN - prefer RDF (grounding)
        if (type1 == SourceType.RDF && type2 == SourceType.GEN) {
            return new ResolvedValue(node1, SourceType.RDF);
        }

        // Case 3: GEN × RDF - prefer RDF (grounding)
        if (type1 == SourceType.GEN && type2 == SourceType.RDF) {
            return new ResolvedValue(node2, SourceType.RDF);
        }

        // Case 4: GEN × GEN - use first (both passed SimScore threshold)
        // Since both values are semantically compatible (SimScore >= θ(v)),
        // we arbitrarily choose the first one
        return new ResolvedValue(node1, SourceType.GEN);
    }

    /**
     * Helper class to hold resolved value and its source type.
     */
    private static class ResolvedValue {
        final Node node;
        final SourceType sourceType;

        ResolvedValue(Node node, SourceType sourceType) {
            this.node = node;
            this.sourceType = sourceType;
        }
    }

    /**
     * Create a builder for TypedBinding.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a builder initialized from an existing binding.
     *
     * @param parent parent binding to copy from
     * @return new builder with parent values
     */
    public static Builder builder(TypedBinding parent) {
        return new Builder(parent);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("TypedBinding{");
        boolean first = true;
        for (Var v : varSet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("?").append(v.getName())
              .append("=").append(get(v))
              .append("[").append(getSourceType(v)).append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builder for TypedBinding.
     */
    public static class Builder {
        private final BindingBuilder bindingBuilder;
        private final Map<Var, SourceType> sourceTypes;

        public Builder() {
            this.bindingBuilder = BindingFactory.builder();
            this.sourceTypes = new HashMap<>();
        }

        public Builder(TypedBinding parent) {
            if (parent != null && parent.baseBinding != null) {
                this.bindingBuilder = BindingFactory.builder(parent.baseBinding);
                this.sourceTypes = new HashMap<>(parent.sourceTypes);
            } else {
                this.bindingBuilder = BindingFactory.builder();
                this.sourceTypes = new HashMap<>();
            }
        }

        /**
         * Add a variable binding with RDF source type.
         */
        public Builder add(Var var, Node node) {
            return add(var, node, SourceType.RDF);
        }

        /**
         * Add a variable binding with specified source type.
         */
        public Builder add(Var var, Node node, SourceType type) {
            if (!bindingBuilder.contains(var)) {
                bindingBuilder.add(var, node);
            }
            sourceTypes.put(var, type);
            return this;
        }

        /**
         * Add a GEN-typed variable binding.
         */
        public Builder addGen(Var var, Node node) {
            return add(var, node, SourceType.GEN);
        }

        /**
         * Build the TypedBinding.
         */
        public TypedBinding build() {
            return new TypedBinding(bindingBuilder.build(), sourceTypes);
        }
    }
}
