package org.gensparql.engine.iterator;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.iterator.QueryIteratorBase;
import org.apache.jena.sparql.expr.*;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.gensparql.core.model.SourceType;
import org.gensparql.core.model.TypedValue;
import org.gensparql.engine.similarity.SimScoreEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Custom filter iterator that implements SimScore-based equality for GEN types.
 *
 * According to the GenSPARQL paper's Filter Evaluation semantics:
 * - For v = c: If v is GEN type, evaluate as SimScore(μ(v), (c, rdf), v)
 * - For v = v': If either is GEN type, use SimScore instead of exact equality
 * - For unbound variables: Return error state ε (filter to false)
 *
 * This replaces standard QueryIterFilterExpr when GEN types are involved.
 */
public class QueryIterSimScoreFilter extends QueryIteratorBase {
    private static final Logger LOG = LoggerFactory.getLogger(QueryIterSimScoreFilter.class);

    private final QueryIterator input;
    private final Expr expr;
    private final ExecutionContext execCxt;
    private final SimScoreEvaluator simScoreEvaluator;
    private final Map<Var, SourceType> sourceTypes;

    private Binding nextBinding = null;
    private boolean finished = false;

    /**
     * Create a SimScore-aware filter iterator.
     *
     * @param input       input iterator
     * @param expr        filter expression
     * @param evaluator   SimScore evaluator
     * @param sourceTypes source type mapping for variables
     * @param execCxt     execution context
     */
    public QueryIterSimScoreFilter(QueryIterator input, Expr expr,
                                    SimScoreEvaluator evaluator,
                                    Map<Var, SourceType> sourceTypes,
                                    ExecutionContext execCxt) {
        this.input = input;
        this.expr = expr;
        this.execCxt = execCxt;
        this.simScoreEvaluator = evaluator;
        this.sourceTypes = sourceTypes != null ? sourceTypes : Map.of();
    }

    @Override
    protected boolean hasNextBinding() {
        if (finished) {
            return false;
        }

        if (nextBinding != null) {
            return true;
        }

        // Find next binding that passes the filter
        while (input.hasNext()) {
            Binding binding = input.next();
            if (evaluateFilter(binding)) {
                nextBinding = binding;
                return true;
            }
        }

        finished = true;
        return false;
    }

    @Override
    protected Binding moveToNextBinding() {
        if (nextBinding == null && !hasNextBinding()) {
            throw new NoSuchElementException();
        }
        Binding result = nextBinding;
        nextBinding = null;
        return result;
    }

    /**
     * Evaluate the filter expression with SimScore semantics for GEN types.
     *
     * @param binding current binding
     * @return true if filter passes, false otherwise
     */
    private boolean evaluateFilter(Binding binding) {
        try {
            // Check if this is an equality expression that might involve GEN types
            if (expr instanceof E_Equals) {
                return evaluateEquality((E_Equals) expr, binding);
            }

            // Check for NOT equality
            if (expr instanceof E_NotEquals) {
                return !evaluateEquality(
                        new E_Equals(((E_NotEquals) expr).getArg1(), ((E_NotEquals) expr).getArg2()),
                        binding);
            }

            // For other expressions, use standard evaluation
            return evaluateStandard(binding);

        } catch (ExprEvalException e) {
            // Variable not bound or evaluation error - return error state ε (filter to false)
            LOG.debug("Filter evaluation: expr eval error - returning ε (false): {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOG.debug("Filter evaluation error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Evaluate equality expression with SimScore for GEN types.
     *
     * Implements paper's filter evaluation:
     * - v = c: If τ(v) = gen, then SimScore(μ(v), (c, rdf), v)
     * - v = v': If gen ∈ {τ(v), τ(v')}, then SimScore(μ(v), μ(v'), v)
     */
    private boolean evaluateEquality(E_Equals eqExpr, Binding binding) {
        Expr arg1 = eqExpr.getArg1();
        Expr arg2 = eqExpr.getArg2();

        // Extract nodes and determine types
        EvalResult left = evaluateArg(arg1, binding);
        EvalResult right = evaluateArg(arg2, binding);

        // If either is unbound, return false (error state ε)
        if (left == null || right == null) {
            LOG.debug("Filter: unbound variable -> ε (false)");
            return false;
        }

        // Check if GEN types are involved
        boolean leftIsGen = left.sourceType == SourceType.GEN;
        boolean rightIsGen = right.sourceType == SourceType.GEN;

        if (leftIsGen || rightIsGen) {
            // Use SimScore for comparison
            TypedValue tv1 = new TypedValue(left.node, left.sourceType);
            TypedValue tv2 = new TypedValue(right.node, right.sourceType);

            // Determine which variable to use for threshold lookup
            Var thresholdVar = left.var != null ? left.var : (right.var != null ? right.var : Var.alloc("_filter"));

            boolean result = simScoreEvaluator.evaluate(tv1, tv2, thresholdVar);
            LOG.debug("Filter SimScore: {} = {} -> {}", left.node, right.node, result);
            return result;
        }

        // Both are RDF types - use exact equality
        boolean result = left.node.equals(right.node);
        LOG.debug("Filter exact: {} = {} -> {}", left.node, right.node, result);
        return result;
    }

    /**
     * Evaluate an expression argument and determine its source type.
     */
    private EvalResult evaluateArg(Expr expr, Binding binding) {
        if (expr.isVariable()) {
            Var var = expr.asVar();
            Node node = binding.get(var);
            if (node == null) {
                return null; // Unbound - error state
            }
            SourceType type = sourceTypes.getOrDefault(var, SourceType.RDF);
            return new EvalResult(node, type, var);
        }

        if (expr.isConstant()) {
            Node node = expr.getConstant().getNode();
            return new EvalResult(node, SourceType.RDF, null);
        }

        // For complex expressions, evaluate and treat as RDF
        try {
            NodeValue nv = expr.eval(binding, execCxt);
            return new EvalResult(nv.asNode(), SourceType.RDF, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Standard filter evaluation for non-equality expressions.
     */
    private boolean evaluateStandard(Binding binding) {
        try {
            NodeValue nv = expr.eval(binding, execCxt);
            return nv.getBoolean();
        } catch (ExprEvalException e) {
            // Expression evaluation error - return false
            return false;
        }
    }

    @Override
    protected void closeIterator() {
        if (input != null) {
            input.close();
        }
    }

    @Override
    protected void requestCancel() {
        finished = true;
    }

    @Override
    public void output(IndentedWriter out, SerializationContext sCxt) {
        out.print("QueryIterSimScoreFilter(");
        out.print(expr.toString());
        out.print(")");
    }

    /**
     * Helper class to hold evaluation result with type info.
     */
    private static class EvalResult {
        final Node node;
        final SourceType sourceType;
        final Var var;

        EvalResult(Node node, SourceType sourceType, Var var) {
            this.node = node;
            this.sourceType = sourceType;
            this.var = var;
        }
    }
}
