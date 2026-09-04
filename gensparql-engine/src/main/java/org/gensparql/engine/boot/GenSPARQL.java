package org.gensparql.engine.boot;

import org.apache.jena.query.ARQ;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.util.Context;
import org.gensparql.engine.GenSPARQLConfig;
import org.gensparql.engine.GenSPARQLQueryEngine;
import org.gensparql.engine.GenSPARQLQueryEngineFactory;
import org.gensparql.engine.exec.AlgebraGeneratorGenSPARQL;
import org.gensparql.engine.exec.GenSPARQLOpExecutorFactory;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.gensparql.parser.element.ElementGenerate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap class for GenSPARQL.
 *
 * Call GenSPARQL.init() before using GenSPARQL queries.
 *
 * Usage:
 * <pre>
 * GenSPARQL.init();
 *
 * // Optionally configure LLM provider
 * GenSPARQL.setDefaultProvider("openai");
 *
 * // Parse and execute queries
 * Query q = GenSPARQLQueryFactory.create(queryString);
 * QueryExecution qe = GenSPARQL.createQueryExecution(q, dataset);
 * </pre>
 */
public class GenSPARQL {
    private static final Logger LOG = LoggerFactory.getLogger(GenSPARQL.class);

    private static volatile boolean initialized = false;

    // Context keys for GenSPARQL configuration
    public static final String NS = "http://gensparql.org/";
    public static final org.apache.jena.sparql.util.Symbol LLM_PROVIDER =
            org.apache.jena.sparql.util.Symbol.create(NS + "llmProvider");
    public static final org.apache.jena.sparql.util.Symbol DEFAULT_MODEL =
            org.apache.jena.sparql.util.Symbol.create(NS + "defaultModel");
    public static final org.apache.jena.sparql.util.Symbol SIM_THRESHOLD =
            org.apache.jena.sparql.util.Symbol.create(NS + "simThreshold");
    public static final org.apache.jena.sparql.util.Symbol FAIL_ON_LLM_ERROR =
            org.apache.jena.sparql.util.Symbol.create(NS + "failOnLlmError");

    /**
     * Initialize GenSPARQL.
     *
     * This method is idempotent - calling it multiple times has no additional effect.
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }

        LOG.info("Initializing GenSPARQL...");
        LOG.debug("[DEBUG GenSPARQL.init] Starting initialization...");

        // Initialize ARQ if not already done
        ARQ.init();

        // Load configuration from system properties
        LOG.debug("[DEBUG GenSPARQL.init] Loading system properties...");
        LOG.debug("[DEBUG GenSPARQL.init] gensparql.grounding.enabled=" + System.getProperty("gensparql.grounding.enabled"));
        GenSPARQLConfig.loadFromSystemProperties();
        LOG.debug("[DEBUG GenSPARQL.init] After loading: isGroundingEnabled=" + GenSPARQLConfig.isGroundingEnabled());
        LOG.debug("Configuration: {}", GenSPARQLConfig.getSummary());

        // Register our QueryEngineFactory (must be before OpExecutorFactory)
        GenSPARQLQueryEngineFactory.register();

        // Register our OpExecutorFactory
        registerOpExecutorFactory();

        // Register the gen:* extension functions if the (optional) gensparql-functions
        // module is on the classpath. Done reflectively so the engine keeps no compile-time
        // dependency on gensparql-functions (which itself depends on the engine — a direct
        // call would be a dependency cycle). ARQ is already initialized here, so the global
        // FunctionRegistry exists and the registration is picked up by query execution.
        registerExtensionFunctions();

        // Set default configuration
        setDefaultConfiguration();

        initialized = true;
        LOG.info("GenSPARQL initialized successfully");
    }

    /**
     * Check if GenSPARQL has been initialized.
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Reset GenSPARQL (mainly for testing).
     */
    public static synchronized void reset() {
        initialized = false;
        LLMProviderRegistry.clearInstances();
        LOG.debug("GenSPARQL reset");
    }

    /**
     * Register the GenSPARQL OpExecutorFactory with ARQ.
     */
    private static void registerOpExecutorFactory() {
        Context context = ARQ.getContext();
        GenSPARQLOpExecutorFactory factory = new GenSPARQLOpExecutorFactory(
                QC.getFactory(context)
        );
        QC.setFactory(context, factory);
        LOG.debug("Registered GenSPARQL OpExecutorFactory");
    }

    /**
     * Register the {@code gen:*} SPARQL extension functions, if the gensparql-functions
     * module is present on the classpath. No-op (debug log) when it is absent.
     */
    private static void registerExtensionFunctions() {
        try {
            Class<?> fns = Class.forName("org.gensparql.function.GenSPARQLFunctions");
            fns.getMethod("register").invoke(null);
            LOG.info("Registered gen:* extension functions");
        } catch (ClassNotFoundException e) {
            LOG.debug("gensparql-functions not on classpath; gen:* functions unavailable");
        } catch (ReflectiveOperationException e) {
            LOG.warn("Failed to register gen:* extension functions", e);
        }
    }

    /**
     * Set default configuration values.
     */
    private static void setDefaultConfiguration() {
        Context context = ARQ.getContext();

        // Default similarity threshold for soft joins
        if (!context.isDefined(SIM_THRESHOLD)) {
            context.set(SIM_THRESHOLD, 0.85);
        }

        LOG.debug("Default configuration set");
    }

    /**
     * Set the default LLM provider by name.
     *
     * @param providerName provider name (e.g., "openai", "anthropic")
     */
    public static void setDefaultProvider(String providerName) {
        ensureInitialized();
        LLMProviderRegistry.setDefault(providerName);
        LOG.info("Set default LLM provider to: {}", providerName);
    }

    /**
     * Set the default LLM provider.
     *
     * @param provider the provider instance
     */
    public static void setDefaultProvider(LLMProvider provider) {
        ensureInitialized();
        LLMProviderRegistry.setDefault(provider);
        LOG.info("Set default LLM provider to: {}", provider.getName());
    }

    /**
     * Get the default LLM provider.
     */
    public static LLMProvider getDefaultProvider() {
        ensureInitialized();
        return LLMProviderRegistry.getDefault();
    }

    /**
     * Set the default model URI.
     *
     * @param modelUri model URI (e.g., "model:openai:gpt-4")
     */
    public static void setDefaultModel(String modelUri) {
        ensureInitialized();
        ARQ.getContext().set(DEFAULT_MODEL, modelUri);
        LOG.info("Set default model to: {}", modelUri);
    }

    /**
     * Set the default similarity threshold for soft joins.
     *
     * @param threshold threshold value (0.0 to 1.0)
     */
    public static void setSimilarityThreshold(double threshold) {
        ensureInitialized();
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("Threshold must be between 0 and 1");
        }
        ARQ.getContext().set(SIM_THRESHOLD, threshold);
        LOG.debug("Set similarity threshold to: {}", threshold);
    }

    /**
     * Get the similarity threshold.
     */
    public static double getSimilarityThreshold() {
        ensureInitialized();
        return ARQ.getContext().get(SIM_THRESHOLD, 0.85);
    }

    private static void ensureInitialized() {
        if (!initialized) {
            init();
        }
    }

    /**
     * Create a QueryExecution for a GenSPARQL query using a Model.
     * This method ensures the GenSPARQL engine is used for query execution.
     *
     * @param query the query to execute
     * @param model the model to query
     * @return a QueryExecution that uses GenSPARQL engine
     */
    public static QueryExecution createQueryExecution(Query query, Model model) {
        ensureInitialized();
        Dataset dataset = org.apache.jena.query.DatasetFactory.wrap(model);
        return createQueryExecution(query, dataset);
    }

    /**
     * Create a QueryExecution for a GenSPARQL query using a Dataset.
     * This method ensures the GenSPARQL engine is used for query execution.
     *
     * @param query the query to execute
     * @param dataset the dataset to query
     * @return a QueryExecution that uses GenSPARQL engine
     */
    public static QueryExecution createQueryExecution(Query query, Dataset dataset) {
        return createQueryExecution(query, dataset, ARQ.getContext());
    }

    /** Create a GenSPARQL execution with an isolated, request-local context. */
    public static QueryExecution createQueryExecution(Query query, Dataset dataset, Context context) {
        ensureInitialized();
        DatasetGraph dsg = dataset.asDatasetGraph();
        Context ctx = (context != null ? context : ARQ.getContext()).copy();
        Binding input = BindingFactory.root();

        // Check if query contains ElementGenerate
        org.apache.jena.sparql.syntax.Element pattern = query.getQueryPattern();
        Op op;
        if (containsElementGenerate(pattern)) {
            // Use our custom algebra generator
            op = AlgebraGeneratorGenSPARQL.compileQuery(query);
        } else {
            // Fall back to standard compilation
            op = org.apache.jena.sparql.algebra.Algebra.compile(query);
        }

        // Create our custom query engine and get the plan
        GenSPARQLQueryEngine engine = new GenSPARQLQueryEngine(op, dsg, input, ctx);
        Plan plan = engine.getPlan();

        // Return a QueryExecution wrapper
        return new GenSPARQLQueryExecution(query, dataset, plan);
    }

    /**
     * Does this pattern contain a GENOP anywhere?
     *
     * <p>The answer decides whether the query is compiled by GenSPARQL or handed to Jena, and
     * Jena's compiler rejects ElementGenerate outright. Missing a nesting construct here does
     * not degrade the plan, it fails the query, which is what happened for a GENOP inside
     * GRAPH, a sub-select, SERVICE or MINUS.
     */
    private static boolean containsElementGenerate(org.apache.jena.sparql.syntax.Element element) {
        if (element == null) {
            return false;
        }
        if (element instanceof ElementGenerate) {
            return true;
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementGroup) {
            for (org.apache.jena.sparql.syntax.Element e
                    : ((org.apache.jena.sparql.syntax.ElementGroup) element).getElements()) {
                if (containsElementGenerate(e)) {
                    return true;
                }
            }
            return false;
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementUnion) {
            for (org.apache.jena.sparql.syntax.Element e
                    : ((org.apache.jena.sparql.syntax.ElementUnion) element).getElements()) {
                if (containsElementGenerate(e)) {
                    return true;
                }
            }
            return false;
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementOptional) {
            return containsElementGenerate(
                    ((org.apache.jena.sparql.syntax.ElementOptional) element).getOptionalElement());
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementMinus) {
            return containsElementGenerate(
                    ((org.apache.jena.sparql.syntax.ElementMinus) element).getMinusElement());
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementNamedGraph) {
            return containsElementGenerate(
                    ((org.apache.jena.sparql.syntax.ElementNamedGraph) element).getElement());
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementService) {
            return containsElementGenerate(
                    ((org.apache.jena.sparql.syntax.ElementService) element).getElement());
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementSubQuery) {
            return containsElementGenerate(
                    ((org.apache.jena.sparql.syntax.ElementSubQuery) element).getQuery().getQueryPattern());
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementExists) {
            return containsElementGenerate(
                    ((org.apache.jena.sparql.syntax.ElementExists) element).getElement());
        }
        if (element instanceof org.apache.jena.sparql.syntax.ElementNotExists) {
            return containsElementGenerate(
                    ((org.apache.jena.sparql.syntax.ElementNotExists) element).getElement());
        }
        return false;
    }

    /**
     * Simple QueryExecution wrapper for GenSPARQL queries.
     */
    private static class GenSPARQLQueryExecution implements QueryExecution {
        private final Query query;
        private final Dataset dataset;
        private final Plan plan;
        private boolean closed = false;

        // Iterators this execution has handed out, so abort() and close() can reach them.
        private final java.util.List<org.apache.jena.sparql.engine.QueryIterator> liveIterators =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private volatile boolean aborted = false;

        GenSPARQLQueryExecution(Query query, Dataset dataset, Plan plan) {
            this.query = query;
            this.dataset = dataset;
            this.plan = plan;
        }

        /**
         * Start evaluating, tracking the iterator so it can be cancelled.
         *
         * <p>abort() used to be a no-op and nothing held the iterator, so a cancelled or timed
         * out query ran to completion regardless. A GENOP query is the case where that shows:
         * it keeps calling a model that the caller has already stopped waiting for.
         */
        private org.apache.jena.sparql.engine.QueryIterator openIterator() {
            if (closed) {
                throw new IllegalStateException("QueryExecution is closed");
            }
            org.apache.jena.sparql.engine.QueryIterator qIter = plan.iterator();
            liveIterators.add(qIter);
            if (aborted) {
                qIter.cancel();
            }
            return qIter;
        }

        @Override
        public org.apache.jena.query.ResultSet execSelect() {
            if (closed) throw new IllegalStateException("QueryExecution is closed");
            org.apache.jena.sparql.engine.QueryIterator qIter = openIterator();
            // Convert String list to Var list
            java.util.List<org.apache.jena.sparql.core.Var> vars = new java.util.ArrayList<>();
            for (String varName : query.getResultVars()) {
                vars.add(org.apache.jena.sparql.core.Var.alloc(varName));
            }
            return org.apache.jena.sparql.engine.ResultSetStream.create(vars, qIter);
        }

        @Override
        public Model execConstruct() {
            return execConstruct(ModelFactory.createDefaultModel());
        }

        @Override
        public Model execConstruct(Model model) {
            model.setNsPrefixes(query.getPrefixMapping());
            java.util.Iterator<org.apache.jena.graph.Triple> triples = execConstructTriples();
            while (triples.hasNext()) {
                model.getGraph().add(triples.next());
            }
            return model;
        }

        @Override
        public org.apache.jena.query.Dataset execConstructDataset() {
            return execConstructDataset(org.apache.jena.query.DatasetFactory.createTxnMem());
        }

        @Override
        public org.apache.jena.query.Dataset execConstructDataset(org.apache.jena.query.Dataset target) {
            DatasetGraph dsg = target.asDatasetGraph();
            java.util.Iterator<org.apache.jena.sparql.core.Quad> quads = execConstructQuads();
            while (quads.hasNext()) {
                dsg.add(quads.next());
            }
            return target;
        }

        /**
         * Instantiate the CONSTRUCT template once per solution.
         *
         * <p>Jena's TemplateLib does the substitution, including allocating fresh blank nodes
         * per solution, so a template blank node does not collapse into one node across rows.
         */
        @Override
        public java.util.Iterator<org.apache.jena.graph.Triple> execConstructTriples() {
            if (closed) {
                throw new IllegalStateException("QueryExecution is closed");
            }
            org.apache.jena.sparql.syntax.Template template = query.getConstructTemplate();
            if (template == null) {
                return java.util.Collections.emptyIterator();
            }
            return org.apache.jena.sparql.modify.TemplateLib.calcTriples(
                    template.getTriples(), openIterator());
        }

        @Override
        public java.util.Iterator<org.apache.jena.sparql.core.Quad> execConstructQuads() {
            java.util.Iterator<org.apache.jena.graph.Triple> triples = execConstructTriples();
            java.util.List<org.apache.jena.sparql.core.Quad> quads = new java.util.ArrayList<>();
            while (triples.hasNext()) {
                quads.add(org.apache.jena.sparql.core.Quad.create(
                        org.apache.jena.sparql.core.Quad.defaultGraphNodeGenerated, triples.next()));
            }
            return quads.iterator();
        }

        @Override
        public Model execDescribe() {
            return execDescribe(ModelFactory.createDefaultModel());
        }

        /**
         * Describe the resources the query names and the ones its pattern binds.
         *
         * <p>What "describe" means is left to ARQ's registered describe handlers, the same ones
         * a plain Jena query would use, so the output does not depend on whether the query
         * happened to contain a GENOP.
         */
        @Override
        public Model execDescribe(Model model) {
            if (closed) {
                throw new IllegalStateException("QueryExecution is closed");
            }
            model.setNsPrefixes(query.getPrefixMapping());

            java.util.Set<org.apache.jena.graph.Node> resources = new java.util.LinkedHashSet<>();
            resources.addAll(query.getResultURIs());

            java.util.List<org.apache.jena.sparql.core.Var> describeVars = query.getProjectVars();
            org.apache.jena.sparql.engine.QueryIterator qIter = openIterator();
            try {
                while (qIter.hasNext()) {
                    Binding binding = qIter.next();
                    if (describeVars == null || describeVars.isEmpty()) {
                        binding.vars().forEachRemaining(v -> collectDescribed(binding, v, resources));
                    } else {
                        for (org.apache.jena.sparql.core.Var v : describeVars) {
                            collectDescribed(binding, v, resources);
                        }
                    }
                }
            } finally {
                qIter.close();
            }

            java.util.List<org.apache.jena.sparql.core.describe.DescribeHandler> handlers =
                    org.apache.jena.sparql.core.describe.DescribeHandlerRegistry.get().newHandlerList();
            Context describeCxt = ARQ.getContext().copy();
            describeCxt.set(org.apache.jena.sparql.ARQConstants.sysCurrentDataset, dataset);

            for (org.apache.jena.sparql.core.describe.DescribeHandler h : handlers) {
                h.start(model, describeCxt);
            }
            for (org.apache.jena.graph.Node node : resources) {
                if (node.isURI() || node.isBlank()) {
                    for (org.apache.jena.sparql.core.describe.DescribeHandler h : handlers) {
                        h.describe(model.getRDFNode(node).asResource());
                    }
                }
            }
            for (org.apache.jena.sparql.core.describe.DescribeHandler h : handlers) {
                h.finish();
            }
            return model;
        }

        /** A described resource must be a node the graph can be about, so literals are skipped. */
        private static void collectDescribed(Binding binding, org.apache.jena.sparql.core.Var v,
                                             java.util.Set<org.apache.jena.graph.Node> out) {
            org.apache.jena.graph.Node n = binding.get(v);
            if (n != null && (n.isURI() || n.isBlank())) {
                out.add(n);
            }
        }

        @Override
        public java.util.Iterator<org.apache.jena.graph.Triple> execDescribeTriples() {
            return execDescribe().getGraph().find(null, null, null);
        }

        /** True as soon as one solution exists; the rest of the plan is not evaluated. */
        @Override
        public boolean execAsk() {
            if (closed) {
                throw new IllegalStateException("QueryExecution is closed");
            }
            org.apache.jena.sparql.engine.QueryIterator qIter = openIterator();
            try {
                return qIter.hasNext();
            } finally {
                qIter.close();
            }
        }

        @Override
        public java.util.Iterator<org.apache.jena.atlas.json.JsonObject> execJsonItems() {
            throw new UnsupportedOperationException("JSON not yet supported");
        }

        @Override
        public org.apache.jena.atlas.json.JsonArray execJson() {
            throw new UnsupportedOperationException("JSON not yet supported");
        }

        /**
         * Stop this execution. Safe to call from another thread, which is how a timeout and a
         * user-initiated cancel both arrive.
         */
        @Override
        public void abort() {
            aborted = true;
            synchronized (liveIterators) {
                for (org.apache.jena.sparql.engine.QueryIterator it : liveIterators) {
                    try {
                        it.cancel();
                    } catch (RuntimeException e) {
                        LOG.debug("Ignoring error while cancelling an iterator: {}", e.getMessage());
                    }
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            synchronized (liveIterators) {
                for (org.apache.jena.sparql.engine.QueryIterator it : liveIterators) {
                    try {
                        it.close();
                    } catch (RuntimeException e) {
                        LOG.debug("Ignoring error while closing an iterator: {}", e.getMessage());
                    }
                }
                liveIterators.clear();
            }
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public String getQueryString() {
            return query.toString();
        }

        @Override
        public Query getQuery() {
            return query;
        }

        @Override
        public Dataset getDataset() {
            return dataset;
        }

        @Override
        public Context getContext() {
            return ARQ.getContext();
        }

        /**
         * No timeout. Jena 5 sets one through the execution builder, which this execution is
         * not built by, so there is none to report. Cancellation goes through {@link #abort()}.
         */
        @Override
        public long getTimeout1() {
            return -1;
        }

        @Override
        public long getTimeout2() {
            return -1;
        }
    }
}
