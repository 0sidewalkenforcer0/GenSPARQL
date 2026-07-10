package org.gensparql.engine.exec;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpExt;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.main.OpExecutor;
import org.apache.jena.sparql.engine.main.OpExecutorFactory;
import org.gensparql.engine.op.OpGenerate;

/**
 * OpExecutorFactory that handles GenSPARQL operators.
 *
 * Extends the standard ARQ execution to support OpGenerate.
 */
public class GenSPARQLOpExecutorFactory implements OpExecutorFactory {

    private final OpExecutorFactory delegate;

    public GenSPARQLOpExecutorFactory() {
        this(null);
    }

    public GenSPARQLOpExecutorFactory(OpExecutorFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public OpExecutor create(ExecutionContext execCxt) {
        OpExecutor base = delegate != null ? delegate.create(execCxt) : null;
        return new GenSPARQLOpExecutor(execCxt, base);
    }

    /**
     * OpExecutor that handles GenSPARQL-specific operators.
     */
    public static class GenSPARQLOpExecutor extends OpExecutor {

        private final OpExecutor delegate;

        public GenSPARQLOpExecutor(ExecutionContext execCxt, OpExecutor delegate) {
            super(execCxt);
            this.delegate = delegate;
        }

        @Override
        protected QueryIterator execute(OpExt opExt, QueryIterator input) {
            System.out.println("[DEBUG GenSPARQLOpExecutor] execute(OpExt) called: " + opExt.getClass().getSimpleName());
            // Handle OpGenerate specifically
            if (opExt instanceof OpGenerate) {
                return executeGenerate((OpGenerate) opExt, input);
            }

            // Delegate to standard executor for other OpExt
            return super.execute(opExt, input);
        }

        private QueryIterator executeGenerate(OpGenerate op, QueryIterator input) {
            System.out.println("[DEBUG GenSPARQLOpExecutor] executeGenerate called");
            System.out.println("[DEBUG GenSPARQLOpExecutor] OpGenerate isBaseMode: " + op.isBaseMode());
            System.out.println("[DEBUG GenSPARQLOpExecutor] Input iterator: " + input.getClass().getSimpleName());
            System.out.println("[DEBUG GenSPARQLOpExecutor] Input hasNext: " + input.hasNext());
            // OpGenerate.eval handles the execution
            return op.eval(input, execCxt);
        }
    }
}
