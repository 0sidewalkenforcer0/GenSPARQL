package org.gensparql.engine.exec;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpExt;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.main.OpExecutor;
import org.apache.jena.sparql.engine.main.OpExecutorFactory;
import org.gensparql.engine.op.OpGenerate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpExecutorFactory that handles GenSPARQL operators.
 *
 * Extends the standard ARQ execution to support OpGenerate.
 */
public class GenSPARQLOpExecutorFactory implements OpExecutorFactory {

    private static final Logger LOG = LoggerFactory.getLogger(GenSPARQLOpExecutorFactory.class);

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
            LOG.debug("[DEBUG GenSPARQLOpExecutor] execute(OpExt) called: " + opExt.getClass().getSimpleName());
            // Handle OpGenerate specifically
            if (opExt instanceof OpGenerate) {
                return executeGenerate((OpGenerate) opExt, input);
            }

            // Delegate to standard executor for other OpExt
            return super.execute(opExt, input);
        }

        private QueryIterator executeGenerate(OpGenerate op, QueryIterator input) {
            LOG.debug("[DEBUG GenSPARQLOpExecutor] executeGenerate called");
            LOG.debug("[DEBUG GenSPARQLOpExecutor] OpGenerate isBaseMode: " + op.isBaseMode());
            LOG.debug("[DEBUG GenSPARQLOpExecutor] Input iterator: " + input.getClass().getSimpleName());
            LOG.debug("[DEBUG GenSPARQLOpExecutor] Input hasNext: " + input.hasNext());
            // OpGenerate.eval handles the execution
            return op.eval(input, execCxt);
        }
    }
}
