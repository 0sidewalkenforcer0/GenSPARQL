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

        /**
         * Feed a join's left side into its right side when the right side generates.
         *
         * <p>ARQ evaluates the two sides of a join independently, the right one against the root
         * binding, and joins the results. A context-mode GENOP on the right then finds its input
         * variable unbound, skips every row and issues no calls, so the query returns nothing
         * with no error. Running it over the left side's results instead is the pipelined
         * evaluation the operator needs, and it is the same join.
         *
         * <p>This lives on the executor rather than on the engine because ARQ recurses through
         * here for every nested operator. Handling it in the engine's own dispatch would only
         * cover the operators that dispatch names, and GRAPH, GROUP or anything else wrapping
         * the join would go back to the independent evaluation.
         */
        @Override
        protected QueryIterator execute(org.apache.jena.sparql.algebra.op.OpJoin opJoin,
                                        QueryIterator input) {
            if (containsGenerate(opJoin.getRight())) {
                LOG.debug("Pipelining join: right side contains a GENOP");
                QueryIterator left = exec(opJoin.getLeft(), input);
                return exec(opJoin.getRight(), left);
            }
            return super.execute(opJoin, input);
        }

        /** Whether a GENOP appears anywhere below this operator. Walks by arity, so an
         *  operator nobody listed still counts. */
        private static boolean containsGenerate(org.apache.jena.sparql.algebra.Op op) {
            if (op instanceof OpGenerate) {
                return true;
            }
            if (op instanceof org.apache.jena.sparql.algebra.op.Op1) {
                return containsGenerate(((org.apache.jena.sparql.algebra.op.Op1) op).getSubOp());
            }
            if (op instanceof org.apache.jena.sparql.algebra.op.Op2) {
                org.apache.jena.sparql.algebra.op.Op2 op2 = (org.apache.jena.sparql.algebra.op.Op2) op;
                return containsGenerate(op2.getLeft()) || containsGenerate(op2.getRight());
            }
            if (op instanceof org.apache.jena.sparql.algebra.op.OpN) {
                for (org.apache.jena.sparql.algebra.Op e
                        : ((org.apache.jena.sparql.algebra.op.OpN) op).getElements()) {
                    if (containsGenerate(e)) {
                        return true;
                    }
                }
            }
            return false;
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
