package org.gensparql.parser.element;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementVisitor;
import org.apache.jena.sparql.util.NodeIsomorphismMap;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.core.util.PromptTemplate;

import java.util.*;

/**
 * Syntax element representing a GENERATE clause or GENOP function in GenSPARQL.
 *
 * Supported syntax forms:
 *
 * 1. GENERATE clause (declarative style):
 *    GENERATE { ?var1 ?var2 ... }
 *    WITH PROMPT "template with {?placeholders}"
 *    USING MODEL <model:provider:model>
 *    [OPTIONS { key: value, ... }]
 *    [THRESHOLD value]
 *
 * 2. GENOP function (functional style):
 *    GENOP("prompt", ?output, <model>)
 *    GENOP("prompt", (?var1, ?var2), <model>, threshold)
 *
 * Modes:
 * - Base Mode: No input variables, direct LLM call (In(g) = ∅)
 * - Context Mode: Prompt contains {?x} placeholders bound by surrounding patterns
 * - Multi-variable Output: LLM output parsed into multiple variable bindings
 *
 * This element corresponds to GenOp(StrX, Y, M) in the paper.
 */
public class ElementGenerate extends Element {

    private final List<Var> outputVariables;     // Y: output variables
    private final String promptTemplate;          // StrX: prompt template
    private final Set<Var> inputVariables;        // X: input variables (from template)
    private final Node modelNode;                 // M: model specification (as URI node)
    private final Map<String, Object> options;    // Optional parameters
    private final Double threshold;               // θ: optional similarity threshold

    public ElementGenerate(List<Var> outputVariables, String promptTemplate, Node modelNode) {
        this(outputVariables, promptTemplate, modelNode, Collections.emptyMap());
    }

    public ElementGenerate(List<Var> outputVariables, String promptTemplate,
                          Node modelNode, Map<String, Object> options) {
        this.outputVariables = Collections.unmodifiableList(new ArrayList<>(outputVariables));
        this.promptTemplate = promptTemplate;
        this.modelNode = modelNode;

        // Process options - extract threshold if present
        Map<String, Object> mutableOptions = options != null ? new HashMap<>(options) : new HashMap<>();
        Object thresholdObj = mutableOptions.remove("threshold");
        if (thresholdObj instanceof Number) {
            this.threshold = ((Number) thresholdObj).doubleValue();
        } else {
            this.threshold = null;
        }
        this.options = Collections.unmodifiableMap(mutableOptions);

        // Extract input variables from prompt template
        this.inputVariables = PromptTemplate.extractVariables(promptTemplate);
    }

    /**
     * Get output variables Y bound by this GenOp.
     */
    public List<Var> getOutputVariables() {
        return outputVariables;
    }

    /**
     * Get the prompt template string StrX.
     */
    public String getPromptTemplate() {
        return promptTemplate;
    }

    /**
     * Get input variables X (placeholders in the template).
     */
    public Set<Var> getInputVariables() {
        return inputVariables;
    }

    /**
     * Get the model specification node.
     */
    public Node getModelNode() {
        return modelNode;
    }

    /**
     * Get options map.
     */
    public Map<String, Object> getOptions() {
        return options;
    }

    /**
     * Get the similarity threshold θ (optional).
     * Used for approximate matching of LLM-generated values.
     *
     * @return threshold value between 0.0 and 1.0, or null if not specified
     */
    public Double getThreshold() {
        return threshold;
    }

    /**
     * Check if a threshold is specified for this GenOp.
     */
    public boolean hasThreshold() {
        return threshold != null;
    }

    /**
     * Parse the model node into a ModelSpec.
     */
    public ModelSpec getModelSpec() {
        if (modelNode == null || !modelNode.isURI()) {
            return null;
        }
        return ModelSpec.fromURI(modelNode.getURI());
    }

    /**
     * Check if this is base mode (no input variables).
     * In base mode, In(g) = ∅ and the prompt doesn't depend on bindings.
     */
    public boolean isBaseMode() {
        return inputVariables.isEmpty();
    }

    /**
     * Get all variables mentioned in this element.
     */
    public Set<Var> getAllVariables() {
        Set<Var> all = new LinkedHashSet<>(outputVariables);
        all.addAll(inputVariables);
        return all;
    }

    @Override
    public void visit(ElementVisitor v) {
        // Custom visitor pattern - standard visitor won't know about this element
        if (v instanceof GenSPARQLElementVisitor) {
            ((GenSPARQLElementVisitor) v).visit(this);
        }
    }

    /**
     * Check if this has multiple output variables.
     * Multi-variable output requires PARSE function to split LLM response.
     */
    public boolean isMultiVariableOutput() {
        return outputVariables.size() > 1;
    }

    @Override
    public int hashCode() {
        return Objects.hash(outputVariables, promptTemplate, modelNode, options, threshold);
    }

    @Override
    public boolean equalTo(Element other, NodeIsomorphismMap isoMap) {
        if (!(other instanceof ElementGenerate)) {
            return false;
        }
        ElementGenerate o = (ElementGenerate) other;
        return Objects.equals(outputVariables, o.outputVariables) &&
               Objects.equals(promptTemplate, o.promptTemplate) &&
               Objects.equals(modelNode, o.modelNode) &&
               Objects.equals(options, o.options) &&
               Objects.equals(threshold, o.threshold);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("GENERATE { ");
        for (int i = 0; i < outputVariables.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append("?").append(outputVariables.get(i).getName());
        }
        sb.append(" }\n");
        sb.append("WITH PROMPT \"").append(escapeString(promptTemplate)).append("\"\n");
        sb.append("USING MODEL <").append(modelNode.getURI()).append(">");
        if (!options.isEmpty()) {
            sb.append("\nOPTIONS { ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : options.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append(": ").append(entry.getValue());
                first = false;
            }
            sb.append(" }");
        }
        if (threshold != null) {
            sb.append("\nTHRESHOLD ").append(threshold);
        }
        return sb.toString();
    }

    /**
     * Convert to GENOP function syntax.
     */
    public String toGenOpSyntax() {
        StringBuilder sb = new StringBuilder();
        sb.append("GENOP(\"").append(escapeString(promptTemplate)).append("\", ");
        if (outputVariables.size() == 1) {
            sb.append("?").append(outputVariables.get(0).getName());
        } else {
            sb.append("(");
            for (int i = 0; i < outputVariables.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("?").append(outputVariables.get(i).getName());
            }
            sb.append(")");
        }
        sb.append(", <").append(modelNode.getURI()).append(">");
        if (threshold != null) {
            sb.append(", ").append(threshold);
        }
        sb.append(")");
        return sb.toString();
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Extended visitor interface for GenSPARQL elements.
     */
    public interface GenSPARQLElementVisitor extends ElementVisitor {
        void visit(ElementGenerate el);
    }

    /**
     * Builder for ElementGenerate.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<Var> outputVariables = new ArrayList<>();
        private String promptTemplate;
        private Node modelNode;
        private Map<String, Object> options = new HashMap<>();

        public Builder outputVariables(List<Var> vars) {
            this.outputVariables = new ArrayList<>(vars);
            return this;
        }

        public Builder addOutputVariable(Var var) {
            this.outputVariables.add(var);
            return this;
        }

        public Builder addOutputVariable(String name) {
            this.outputVariables.add(Var.alloc(name));
            return this;
        }

        public Builder promptTemplate(String template) {
            this.promptTemplate = template;
            return this;
        }

        public Builder modelNode(Node node) {
            this.modelNode = node;
            return this;
        }

        public Builder modelURI(String uri) {
            this.modelNode = org.apache.jena.graph.NodeFactory.createURI(uri);
            return this;
        }

        public Builder option(String key, Object value) {
            this.options.put(key, value);
            return this;
        }

        public Builder options(Map<String, Object> opts) {
            this.options.putAll(opts);
            return this;
        }

        /**
         * Set the similarity threshold θ for approximate matching.
         *
         * @param threshold value between 0.0 and 1.0
         */
        public Builder threshold(double threshold) {
            if (threshold < 0.0 || threshold > 1.0) {
                throw new IllegalArgumentException("Threshold must be between 0.0 and 1.0");
            }
            this.options.put("threshold", threshold);
            return this;
        }

        public ElementGenerate build() {
            Objects.requireNonNull(promptTemplate, "promptTemplate is required");
            Objects.requireNonNull(modelNode, "modelNode is required");
            if (outputVariables.isEmpty()) {
                throw new IllegalArgumentException("At least one output variable is required");
            }
            return new ElementGenerate(outputVariables, promptTemplate, modelNode, options);
        }
    }
}
