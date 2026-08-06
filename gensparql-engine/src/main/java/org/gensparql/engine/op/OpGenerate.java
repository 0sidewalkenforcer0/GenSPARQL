package org.gensparql.engine.op;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitor;
import org.apache.jena.sparql.algebra.op.OpExt;
import org.apache.jena.sparql.algebra.op.OpExtend;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.core.VarExprList;
import org.apache.jena.sparql.expr.E_StrConcat;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.ExprVar;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.sparql.util.NodeIsomorphismMap;
import org.apache.jena.atlas.io.IndentedWriter;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.core.util.PromptTemplate;
import org.gensparql.engine.iterator.QueryIterGenerate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Algebra operator for GenOp(StrX, Y, M) in GenSPARQL.
 *
 * Represents a generative operation that:
 * - Takes input bindings from surrounding patterns
 * - Substitutes them into a prompt template
 * - Calls an LLM to generate outputs
 * - Binds the outputs to specified variables
 */
public class OpGenerate extends OpExt {

    private static final Logger LOG = LoggerFactory.getLogger(OpGenerate.class);

    private final List<Var> outputVariables;     // Y: output variables
    private final String promptTemplate;          // StrX: prompt template string
    private final Set<Var> inputVariables;        // X: input variables (from template)
    private final Node modelNode;                 // M: model specification
    private final Map<String, Object> options;    // Additional options

    public OpGenerate(List<Var> outputVariables, String promptTemplate,
                      Node modelNode, Map<String, Object> options) {
        super("generate");
        this.outputVariables = Collections.unmodifiableList(new ArrayList<>(outputVariables));
        this.promptTemplate = promptTemplate;
        this.modelNode = modelNode;
        this.options = options != null ?
                Collections.unmodifiableMap(new HashMap<>(options)) : Collections.emptyMap();

        // Extract input variables from template
        this.inputVariables = PromptTemplate.extractVariables(promptTemplate);
    }

    /**
     * Get output variables Y.
     */
    public List<Var> getOutputVariables() {
        return outputVariables;
    }

    /**
     * Get input variables X (placeholders in template).
     */
    public Set<Var> getInputVariables() {
        return inputVariables;
    }

    /**
     * Get the prompt template string.
     */
    public String getPromptTemplate() {
        return promptTemplate;
    }

    /**
     * Get the model node.
     */
    public Node getModelNode() {
        return modelNode;
    }

    /**
     * Get options.
     */
    public Map<String, Object> getOptions() {
        return options;
    }

    /**
     * Get ModelSpec from the model node.
     */
    public ModelSpec getModelSpec() {
        if (modelNode == null || !modelNode.isURI()) {
            return null;
        }
        return ModelSpec.fromURI(modelNode.getURI());
    }

    /**
     * Check if this is base mode (no input variables).
     */
    public boolean isBaseMode() {
        return inputVariables.isEmpty();
    }

    /**
     * Get the similarity threshold θ for this GenOp.
     * Used for approximate matching of LLM-generated values in semantic joins.
     *
     * @return threshold value between 0.0 and 1.0, or null if not specified
     */
    public Double getThreshold() {
        Object t = options.get("threshold");
        if (t instanceof Number) {
            return ((Number) t).doubleValue();
        }
        return null;
    }

    /**
     * Check if a threshold is specified for this GenOp.
     *
     * @return true if threshold is set
     */
    public boolean hasThreshold() {
        return getThreshold() != null;
    }

    /**
     * The similarity threshold grounding applies to this GenOp's generated values.
     *
     * <p>Most specific source wins: an explicit {@code grounding_threshold} option, then the
     * positional theta on the GENOP, then the global default. The middle step is what makes
     * {@code GENOP(prompt, Y, M, theta)} behave as sugar for a GenOp followed by a similarity
     * join at theta; without it, theta reached only the ThresholdRegistry, which serves SimJoin
     * and the SimScore filter, and neither of those runs for a context-mode GENOP.
     *
     * <p>A non-numeric {@code grounding_threshold} falls through to the next source rather than
     * failing the query.
     */
    public double getEffectiveGroundingThreshold() {
        Double explicit = asThreshold(options.get("grounding_threshold"));
        if (explicit != null) {
            return explicit;
        }
        Double theta = getThreshold();
        if (theta != null) {
            return theta;
        }
        return org.gensparql.engine.GenSPARQLConfig.getGroundingThreshold();
    }

    private static Double asThreshold(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Get all variables (input + output).
     */
    public Set<Var> getAllVariables() {
        Set<Var> all = new LinkedHashSet<>(outputVariables);
        all.addAll(inputVariables);
        return all;
    }

    /**
     * The equivalent plain-SPARQL shape, for the algorithms that analyse a plan.
     *
     * <p>A GENOP reads its input variables and binds its output variables, which is what an
     * extend does, so that is what this reports: an extend binding each output variable to an
     * expression over the inputs. The expression is never evaluated; it exists so that the
     * inputs are counted as mentioned.
     *
     * <p>Reporting a unit table here, as this used to, told every caller that a GENOP neither
     * reads nor binds anything. ARQ walks into this op to work out which variables a plan uses,
     * so the operator was invisible to that analysis and a plan could be rewritten as if the
     * patterns feeding it were unused.
     */
    @Override
    public Op effectiveOp() {
        VarExprList bindings = new VarExprList();
        Expr inputsMentioned = mentionInputs();
        for (Var out : outputVariables) {
            bindings.add(out, inputsMentioned);
        }
        if (bindings.isEmpty()) {
            return OpTable.unit();
        }
        return OpExtend.create(OpTable.unit(), bindings);
    }

    /** An expression mentioning every input variable, so variable analysis sees them all. */
    private Expr mentionInputs() {
        if (inputVariables.isEmpty()) {
            return NodeValue.makeString("");
        }
        ExprList args = new ExprList();
        for (Var in : inputVariables) {
            args.add(new ExprVar(in));
        }
        return new E_StrConcat(args);
    }

    @Override
    public QueryIterator eval(QueryIterator input, ExecutionContext execCxt) {
        LOG.debug("[DEBUG OpGenerate] eval() called");
        LOG.debug("[DEBUG OpGenerate] input: " + input);
        LOG.debug("[DEBUG OpGenerate] execCxt: " + execCxt);
        return new QueryIterGenerate(input, this, execCxt);
    }

    @Override
    public void outputArgs(IndentedWriter out, SerializationContext sCxt) {
        out.print("(");
        out.print("prompt=\"" + escapeString(promptTemplate) + "\"");
        out.print(" model=" + modelNode);
        out.print(" out=(");
        for (int i = 0; i < outputVariables.size(); i++) {
            if (i > 0) out.print(" ");
            out.print("?" + outputVariables.get(i).getName());
        }
        out.print(")");
        if (!inputVariables.isEmpty()) {
            out.print(" in=(");
            boolean first = true;
            for (Var v : inputVariables) {
                if (!first) out.print(" ");
                out.print("?" + v.getName());
                first = false;
            }
            out.print(")");
        }
        out.print(")");
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), outputVariables, promptTemplate, modelNode, options);
    }

    @Override
    public boolean equalTo(Op other, NodeIsomorphismMap labelMap) {
        if (!(other instanceof OpGenerate)) {
            return false;
        }
        OpGenerate o = (OpGenerate) other;
        return Objects.equals(outputVariables, o.outputVariables) &&
               Objects.equals(promptTemplate, o.promptTemplate) &&
               Objects.equals(modelNode, o.modelNode) &&
               Objects.equals(options, o.options);
    }

    private static String escapeString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    /**
     * Create an OpGenerate from an ElementGenerate.
     * Note: ElementGenerate stores threshold separately from options,
     * so we need to reconstruct the complete options map including threshold.
     */
    public static OpGenerate fromElement(org.gensparql.parser.element.ElementGenerate element) {
        // Reconstruct options including threshold (ElementGenerate removes it from options)
        java.util.Map<String, Object> options = new java.util.HashMap<>(element.getOptions());
        if (element.hasThreshold()) {
            options.put("threshold", element.getThreshold());
        }
        return new OpGenerate(
                element.getOutputVariables(),
                element.getPromptTemplate(),
                element.getModelNode(),
                options
        );
    }

    /**
     * Builder for OpGenerate.
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

        public OpGenerate build() {
            return new OpGenerate(outputVariables, promptTemplate, modelNode, options);
        }
    }
}
