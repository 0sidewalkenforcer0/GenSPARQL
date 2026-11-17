package org.gensparql.engine;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryEngineFactory;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.util.Context;
import org.gensparql.engine.exec.AlgebraGeneratorGenSPARQL;
import org.gensparql.parser.element.ElementGenerate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QueryEngineFactory for GenSPARQL queries.
 *
 * This factory detects queries containing ElementGenerate and
 * uses the custom algebra generator to compile them.
 */
public class GenSPARQLQueryEngineFactory implements QueryEngineFactory {
    private static final Logger LOG = LoggerFactory.getLogger(GenSPARQLQueryEngineFactory.class);

    private static volatile boolean registered = false;

    /**
     * Register this factory with ARQ.
     * Should be called during GenSPARQL initialization.
     */
    public static synchronized void register() {
        if (!registered) {
            QueryEngineRegistry.addFactory(new GenSPARQLQueryEngineFactory());
            registered = true;
            LOG.debug("GenSPARQL QueryEngineFactory registered");
        }
    }

    @Override
    public boolean accept(Query query, DatasetGraph dataset, Context context) {
        // Accept queries that contain ElementGenerate
        LOG.debug("[DEBUG GenSPARQLQueryEngineFactory] accept() called");
        boolean result = AlgebraGeneratorGenSPARQL.containsGenerate(query.getQueryPattern());
        LOG.debug("[DEBUG GenSPARQLQueryEngineFactory] containsGenerate: " + result);
        return result;
    }

    @Override
    public Plan create(Query query, DatasetGraph dataset, Binding inputBinding, Context context) {
        LOG.debug("[DEBUG GenSPARQLQueryEngineFactory] create() called!");
        LOG.debug("GenSPARQL Factory create() called");
        // Compile as GenSPARQL.createQueryExecution does: the GenSPARQL algebra of the pattern and
        // the solution modifiers in the order of SPARQL 1.1, so both entry points evaluate a
        // query alike.
        Op op;
        if (AlgebraGeneratorGenSPARQL.containsGenerate(query.getQueryPattern())) {
            LOG.debug("Compiling GenSPARQL query with custom algebra generator");
            op = AlgebraGeneratorGenSPARQL.compileQuery(query);
        } else {
            // Fall back to standard compilation
            op = Algebra.compile(query);
        }

        // Create our custom query engine
        LOG.debug("[DEBUG GenSPARQLQueryEngineFactory] Creating GenSPARQLQueryEngine");
        LOG.debug("Creating GenSPARQLQueryEngine with op: {}", op.getClass().getSimpleName());
        GenSPARQLQueryEngine engine = new GenSPARQLQueryEngine(op, dataset, inputBinding, context);
        Plan plan = engine.getPlan();
        LOG.debug("[DEBUG GenSPARQLQueryEngineFactory] Returning Plan: " + plan);
        return plan;
    }

    @Override
    public boolean accept(Op op, DatasetGraph dataset, Context context) {
        // Accept pre-compiled ops
        return false;
    }

    @Override
    public Plan create(Op op, DatasetGraph dataset, Binding inputBinding, Context context) {
        GenSPARQLQueryEngine engine = new GenSPARQLQueryEngine(op, dataset, inputBinding, context);
        return engine.getPlan();
    }

}
