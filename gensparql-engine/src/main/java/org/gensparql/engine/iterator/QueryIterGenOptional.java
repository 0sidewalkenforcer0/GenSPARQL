package org.gensparql.engine.iterator;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.iterator.QueryIterFilterExpr;
import org.apache.jena.sparql.engine.iterator.QueryIterRepeatApply;
import org.apache.jena.sparql.engine.iterator.QueryIterSingleton;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprList;

import java.util.function.BiFunction;

/**
 * OPTIONAL whose optional part contains a context-mode GENOP.
 *
 * <p>The prompt of such a GENOP names variables of the required part, as in
 * {@code OPTIONAL { GENOP("Position of {?name}?", (?pos), <model>) }}. The optional part is
 * therefore evaluated once for every solution of the required part, with that solution as its
 * input. Each result of the optional part is kept when it satisfies the expressions of the
 * left join, and a solution without any such result is returned unchanged, without the
 * generated variables.
 */
public class QueryIterGenOptional extends QueryIterRepeatApply {

    private final Op optional;
    private final ExprList exprs;
    private final BiFunction<Op, QueryIterator, QueryIterator> executor;

    /**
     * @param required  solutions of the required part
     * @param optional  the optional part
     * @param exprs     the expressions of the left join, or null
     * @param execCxt   execution context
     * @param executor  evaluates an operator over an input iterator
     */
    public QueryIterGenOptional(QueryIterator required, Op optional, ExprList exprs,
                                ExecutionContext execCxt,
                                BiFunction<Op, QueryIterator, QueryIterator> executor) {
        super(required, execCxt);
        this.optional = optional;
        this.exprs = exprs;
        this.executor = executor;
    }

    @Override
    protected QueryIterator nextStage(Binding binding) {
        QueryIterator results = executor.apply(optional, QueryIterSingleton.create(binding, getExecContext()));
        if (exprs != null) {
            for (Expr expr : exprs) {
                results = new QueryIterFilterExpr(results, expr, getExecContext());
            }
        }
        if (results.hasNext()) {
            return results;
        }
        results.close();
        return QueryIterSingleton.create(binding, getExecContext());
    }
}
