package org.gensparql.engine.iterator;

import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.iterator.QueryIterRepeatApply;
import org.apache.jena.sparql.engine.iterator.QueryIterSingleton;

import java.util.function.Function;

/**
 * OPTIONAL whose optional part contains a GENOP.
 *
 * <p>For every solution of the required part, the engine supplies the solutions of the optional
 * part that extend it and satisfy the expressions of the left join. How it computes them
 * depends on the GENOP: a context-mode GENOP, as in
 * {@code OPTIONAL { GENOP("Position of {?name}?", (?pos), <model>) }}, names variables of the
 * required part, so the optional part is evaluated once for every solution, starting from its
 * bindings. A solution without any extension is returned unchanged, without the variables of
 * the optional part.
 */
public class QueryIterGenOptional extends QueryIterRepeatApply {

    private final Function<Binding, QueryIterator> extensions;

    /**
     * @param required    solutions of the required part
     * @param execCxt     execution context
     * @param extensions  the solutions of the optional part that extend a solution of the
     *                    required part and satisfy the expressions of the left join
     */
    public QueryIterGenOptional(QueryIterator required, ExecutionContext execCxt,
                                Function<Binding, QueryIterator> extensions) {
        super(required, execCxt);
        this.extensions = extensions;
    }

    @Override
    protected QueryIterator nextStage(Binding binding) {
        QueryIterator results = extensions.apply(binding);
        if (results.hasNext()) {
            return results;
        }
        results.close();
        return QueryIterSingleton.create(binding, getExecContext());
    }
}
