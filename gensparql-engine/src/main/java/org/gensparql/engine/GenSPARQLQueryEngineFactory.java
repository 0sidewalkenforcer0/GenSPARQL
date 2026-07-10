package org.gensparql.engine;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryEngineFactory;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.syntax.Element;
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
        System.out.println("[DEBUG GenSPARQLQueryEngineFactory] accept() called");
        Element pattern = query.getQueryPattern();
        boolean result = containsElementGenerate(pattern);
        System.out.println("[DEBUG GenSPARQLQueryEngineFactory] containsElementGenerate: " + result);
        return result;
    }

    @Override
    public Plan create(Query query, DatasetGraph dataset, Binding inputBinding, Context context) {
        System.out.println("[DEBUG GenSPARQLQueryEngineFactory] create() called!");
        LOG.debug("GenSPARQL Factory create() called");
        // Compile using our custom algebra generator
        Element pattern = query.getQueryPattern();
        Op op;

        if (containsElementGenerate(pattern)) {
            System.out.println("[DEBUG GenSPARQLQueryEngineFactory] Compiling with custom algebra generator");
            LOG.debug("Compiling GenSPARQL query with custom algebra generator");
            op = AlgebraGeneratorGenSPARQL.compile(pattern);

            // Apply standard query modifications (PROJECT, ORDER BY, etc.)
            op = applyQueryModifiers(query, op);
            System.out.println("[DEBUG GenSPARQLQueryEngineFactory] Final Op: " + op.getClass().getSimpleName());
        } else {
            // Fall back to standard compilation
            op = Algebra.compile(query);
        }

        // Create our custom query engine
        System.out.println("[DEBUG GenSPARQLQueryEngineFactory] Creating GenSPARQLQueryEngine");
        LOG.debug("Creating GenSPARQLQueryEngine with op: {}", op.getClass().getSimpleName());
        GenSPARQLQueryEngine engine = new GenSPARQLQueryEngine(op, dataset, inputBinding, context);
        Plan plan = engine.getPlan();
        System.out.println("[DEBUG GenSPARQLQueryEngineFactory] Returning Plan: " + plan);
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

    /**
     * Check if the element tree contains any ElementGenerate.
     */
    private boolean containsElementGenerate(Element element) {
        if (element == null) {
            return false;
        }
        if (element instanceof ElementGenerate) {
            return true;
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementGroup) {
            for (Element e : ((org.apache.jena.sparql.syntax.ElementGroup) element).getElements()) {
                if (containsElementGenerate(e)) {
                    return true;
                }
            }
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementOptional) {
            return containsElementGenerate(
                    ((org.apache.jena.sparql.syntax.ElementOptional) element).getOptionalElement());
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementUnion) {
            for (Element e : ((org.apache.jena.sparql.syntax.ElementUnion) element).getElements()) {
                if (containsElementGenerate(e)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Apply query modifiers (SELECT, ORDER BY, LIMIT, etc.) to the algebra.
     */
    private Op applyQueryModifiers(Query query, Op op) {
        // Apply projection
        if (query.isSelectType() && query.getProjectVars() != null && !query.getProjectVars().isEmpty()) {
            op = new org.apache.jena.sparql.algebra.op.OpProject(op, query.getProjectVars());
        }

        // Apply DISTINCT
        if (query.isDistinct()) {
            op = org.apache.jena.sparql.algebra.op.OpDistinct.create(op);
        }

        // Apply REDUCED
        if (query.isReduced()) {
            op = org.apache.jena.sparql.algebra.op.OpReduced.create(op);
        }

        // Apply ORDER BY
        if (query.hasOrderBy()) {
            op = new org.apache.jena.sparql.algebra.op.OpOrder(op, query.getOrderBy());
        }

        // Apply LIMIT and OFFSET
        if (query.hasLimit() || query.hasOffset()) {
            long start = query.hasOffset() ? query.getOffset() : 0;
            long length = query.hasLimit() ? query.getLimit() : Query.NOLIMIT;
            op = new org.apache.jena.sparql.algebra.op.OpSlice(op, start, length);
        }

        return op;
    }
}
