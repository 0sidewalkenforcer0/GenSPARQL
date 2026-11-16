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

    /** The three values of a filter expression in SPARQL: true, false and an error. */
    private enum Value { TRUE, FALSE, ERROR }

    /**
     * Evaluate the filter for a solution. A filter keeps the solution when its expression is
     * true, and an error counts as false, as in SPARQL.
     */
    private boolean evaluateFilter(Binding binding) {
        try {
            return value(expr, binding) == Value.TRUE;
        } catch (Exception e) {
            LOG.debug("Filter evaluation error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The value of an expression under the rule of Section 2.2 for a generated value. Wherever
     * ?x = t, t = ?x, ?x != t or t != ?x occurs in the expression, also inside ||, && and !,
     * and ?x holds a generated value: the comparison is true when the similarity of the value
     * and a label of the term of t reaches the threshold of ?x, and false otherwise, and !=
     * takes the opposite value. A generated variable whose value the similarity join mapped to
     * an entity is compared as that entity. An unbound variable or an evaluation error is an
     * error. ||, && and ! combine the values of their operands as SPARQL does, with errors, and
     * every other expression is evaluated as in SPARQL.
     */
    private Value value(Expr e, Binding binding) {
        if (e instanceof E_LogicalOr) {
            Value a = value(((E_LogicalOr) e).getArg1(), binding);
            Value b = value(((E_LogicalOr) e).getArg2(), binding);
            if (a == Value.TRUE || b == Value.TRUE) {
                return Value.TRUE;
            }
            return a == Value.FALSE && b == Value.FALSE ? Value.FALSE : Value.ERROR;
        }
        if (e instanceof E_LogicalAnd) {
            Value a = value(((E_LogicalAnd) e).getArg1(), binding);
            Value b = value(((E_LogicalAnd) e).getArg2(), binding);
            if (a == Value.FALSE || b == Value.FALSE) {
                return Value.FALSE;
            }
            return a == Value.TRUE && b == Value.TRUE ? Value.TRUE : Value.ERROR;
        }
        if (e instanceof E_LogicalNot) {
            Value a = value(((E_LogicalNot) e).getArg(), binding);
            return a == Value.TRUE ? Value.FALSE : (a == Value.FALSE ? Value.TRUE : Value.ERROR);
        }
        if (e instanceof E_Equals || e instanceof E_NotEquals) {
            ExprFunction2 comparison = (ExprFunction2) e;
            EvalResult left = evaluateArg(comparison.getArg1(), binding);
            EvalResult right = evaluateArg(comparison.getArg2(), binding);
            if (left == null || right == null) {
                LOG.debug("Filter: unbound variable or evaluation error -> error");
                return Value.ERROR;
            }
            if (left.sourceType == SourceType.GEN || right.sourceType == SourceType.GEN) {
                boolean similar = similar(left, right);
                return (e instanceof E_Equals) == similar ? Value.TRUE : Value.FALSE;
            }
        }
        // Two terms of the graph, and every other expression, as SPARQL evaluates them.
        return standard(e, binding);
    }

    /**
     * Whether two bound arguments, at least one of them generated, are similar: the
     * similarity of the generated value and the other term reaches the threshold of the
     * generated variable.
     */
    private boolean similar(EvalResult left, EvalResult right) {
        TypedValue tv1 = new TypedValue(left.node, left.sourceType);
        TypedValue tv2 = new TypedValue(right.node, right.sourceType);
        Var thresholdVar = left.sourceType == SourceType.GEN && left.var != null ? left.var
                : right.sourceType == SourceType.GEN && right.var != null ? right.var
                : left.var != null ? left.var : (right.var != null ? right.var : Var.alloc("_filter"));
        boolean result = simScoreEvaluator.evaluate(tv1, tv2, thresholdVar);
        LOG.debug("Filter similarity: {} ~ {} -> {}", left.node, right.node, result);
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

    /** The effective boolean value of an expression, as SPARQL evaluates a filter. */
    private Value standard(Expr e, Binding binding) {
        try {
            NodeValue nv = e.eval(binding, execCxt);
            return org.apache.jena.sparql.expr.nodevalue.XSDFuncOp.booleanEffectiveValue(nv)
                    ? Value.TRUE : Value.FALSE;
        } catch (ExprEvalException ex) {
            return Value.ERROR;
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
