package org.gensparql.engine;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.Plan;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.iterator.QueryIterRoot;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.util.Context;
import org.gensparql.core.model.SourceType;
import org.gensparql.core.similarity.JaccardSimText;
import org.gensparql.core.similarity.SimText;
import org.gensparql.core.similarity.ThresholdRegistry;
import org.gensparql.engine.iterator.QueryIterGenOptional;
import org.gensparql.engine.iterator.QueryIterSimJoin;
import org.gensparql.engine.iterator.QueryIterSimScoreFilter;
import org.gensparql.engine.op.OpGenerate;
import org.gensparql.engine.similarity.EmbeddingSimText;
import org.gensparql.engine.similarity.GraphLabels;
import org.gensparql.engine.similarity.SimScoreEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom query engine for GenSPARQL queries.
 *
 * Extends QueryEngineMain to handle OpGenerate operations.
 * Supports semantic joins via SimScore for LLM-generated values.
 */
public class GenSPARQLQueryEngine extends QueryEngineMain {
    private static final Logger LOG = LoggerFactory.getLogger(GenSPARQLQueryEngine.class);

    // Track source types for variables across the query execution
    private final Map<Var, SourceType> sourceTypes = new ConcurrentHashMap<>();

    // The output variables of base-mode GENOPs that the solution an optional part is evaluated
    // for already binds, see the OPTIONAL case of executeOp.
    private Set<Var> outputsBoundByInput = Set.of();

    public GenSPARQLQueryEngine(Op op, DatasetGraph dataset, Binding input, Context context) {
        super(op, dataset, input, context);
        LOG.debug("[DEBUG GenSPARQLQueryEngine CONSTRUCTOR] Op: " + op.getClass().getSimpleName());
    }

    /**
     * Skip ARQ's optimizer for a plan containing a GENOP.
     *
     * <p>Two things go wrong when ARQ rewrites such a plan, and both were measured by turning
     * the optimizer on and running the suite.
     *
     * <p>The first is correctness. A sub-select renames the variables that it does not project,
     * so that an inner ?l becomes ?/l and cannot be captured from outside. ARQ applies that
     * rename by walking the plan with a node transform, and an extension operator has no way to
     * take part: {@code OpExt.apply(Transform)} is the only hook, it is handed a Transform that
     * does not carry the mapping, and Jena ships no extension operator that implements it. The
     * patterns are therefore renamed while the GENOP still refers to ?l, its prompt variable is
     * never bound, every row is skipped, and the query returns nothing having issued no calls.
     * Projecting ?l as well made the same query work, which is not a distinction the semantics
     * should draw.
     *
     * <p>The second is that ARQ reorders the operators of a conjunctive fragment on its own
     * cardinality estimates, which do not account for what a GENOP costs. That undoes the order
     * the cost planner chose: on the WC2026 data the selective-patterns-first plan went back to
     * firing the GENOP over all 825 athletes instead of one squad of 26.
     *
     * <p>So these plans keep the shape they were compiled with. What is given up is ARQ's
     * KG-side rewriting, and the part of it that matters most here, pipelining a join instead of
     * evaluating both sides independently, is done by the executor anyway.
     */
    @Override
    protected Op modifyOp(Op op) {
        if (planContainsGenOp(op)) {
            LOG.debug("Skipping ARQ optimization: plan contains a GENOP");
            return op;
        }
        return super.modifyOp(op);
    }

    /**
     * Whether a GENOP appears anywhere in the plan.
     *
     * <p>Walks by arity over Jena's Op1/Op2/OpN shapes rather than listing operator classes, so
     * a construct nobody thought of still counts. Missing one here does not cost a better plan,
     * it lets the optimizer loose on a tree it cannot analyse.
     */
    private static boolean planContainsGenOp(Op op) {
        return planContains(op, g -> true);
    }

    /** Whether a GENOP that passes the test appears anywhere in the plan, walking by arity. */
    private static boolean planContains(Op op, java.util.function.Predicate<OpGenerate> test) {
        if (op instanceof OpGenerate) {
            return test.test((OpGenerate) op);
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.Op1) {
            return planContains(((org.apache.jena.sparql.algebra.op.Op1) op).getSubOp(), test);
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.Op2) {
            org.apache.jena.sparql.algebra.op.Op2 op2 = (org.apache.jena.sparql.algebra.op.Op2) op;
            return planContains(op2.getLeft(), test) || planContains(op2.getRight(), test);
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpN) {
            for (Op e : ((org.apache.jena.sparql.algebra.op.OpN) op).getElements()) {
                if (planContains(e, test)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public QueryIterator eval(Op op, DatasetGraph dsg, Binding input, Context context) {
        LOG.debug("[DEBUG GenSPARQLQueryEngine.eval] START - Op type: " + op.getClass().getSimpleName());
        LOG.debug("GenSPARQLQueryEngine.eval called with op: {}", op.getClass().getSimpleName());

        // Initialize threshold registry and sim text in context if not present
        initializeContext(context);
        // Each query starts with the embedding similarity it asked for; a fallback to the
        // Jaccard similarity applies to the query in which the embedding model failed.
        Object simText = context.get(GenSPARQLConstants.SIM_TEXT);
        if (simText instanceof EmbeddingSimText) {
            ((EmbeddingSimText) simText).resetFallback();
        }
        context.set(GenSPARQLConstants.THRESHOLD_REGISTRY, filterThresholds(op));

        ExecutionContext execCxt = new ExecutionContext(context, dsg.getDefaultGraph(), dsg, QC.getFactory(context));
        QueryIterator qIter = QueryIterRoot.create(input, execCxt);
        LOG.debug("[DEBUG GenSPARQLQueryEngine.eval] Created QueryIterRoot, about to call executeOp");

        // Clear source types for new query
        sourceTypes.clear();
        context.set(GenSPARQLConstants.BASE_MODE_ANSWERS,
                java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>()));

        QueryIterator result = executeOp(op, qIter, execCxt);
        LOG.debug("[DEBUG GenSPARQLQueryEngine.eval] executeOp returned: " + result.getClass().getSimpleName());
        return result;
    }

    /**
     * Initialize GenSPARQL-specific context values.
     */
    private void initializeContext(Context context) {
        // Initialize threshold registry
        if (context.get(GenSPARQLConstants.THRESHOLD_REGISTRY) == null) {
            context.set(GenSPARQLConstants.THRESHOLD_REGISTRY, new ThresholdRegistry());
        }

        // Initialize SimText strategy (prefer embedding, fallback to Jaccard)
        if (context.get(GenSPARQLConstants.SIM_TEXT) == null) {
            try {
                context.set(GenSPARQLConstants.SIM_TEXT, new EmbeddingSimText());
            } catch (Exception e) {
                LOG.warn("Could not initialize the embedding similarity ({}); using the Jaccard similarity",
                        e.getMessage());
                context.set(GenSPARQLConstants.SIM_TEXT, new JaccardSimText());
            }
        }

        // Default: enable sim join
        if (context.get(GenSPARQLConstants.ENABLE_SIM_JOIN) == null) {
            context.set(GenSPARQLConstants.ENABLE_SIM_JOIN, Boolean.TRUE);
        }
    }

    /**
     * Get the threshold registry from context.
     */
    private ThresholdRegistry getThresholdRegistry(ExecutionContext execCxt) {
        ThresholdRegistry registry = execCxt.getContext().get(GenSPARQLConstants.THRESHOLD_REGISTRY);
        if (registry == null) {
            registry = new ThresholdRegistry();
            execCxt.getContext().set(GenSPARQLConstants.THRESHOLD_REGISTRY, registry);
        }
        // A GENOP without a threshold of its own takes the default threshold θ0.
        registry.setDefaultThreshold(GenSPARQLConfig.getDefaultThreshold());
        return registry;
    }

    /**
     * Get the SimText strategy from context.
     */
    private SimText getSimText(ExecutionContext execCxt) {
        SimText simText = execCxt.getContext().get(GenSPARQLConstants.SIM_TEXT);
        if (simText == null) {
            simText = new JaccardSimText();
            execCxt.getContext().set(GenSPARQLConstants.SIM_TEXT, simText);
        }
        return simText;
    }

    /**
     * Check if similarity joins are enabled.
     */
    private boolean isSimJoinEnabled(ExecutionContext execCxt) {
        Boolean enabled = execCxt.getContext().get(GenSPARQLConstants.ENABLE_SIM_JOIN);
        return enabled != null && enabled;
    }

    private QueryIterator executeOp(Op op, QueryIterator input, ExecutionContext execCxt) {
        LOG.debug("[DEBUG GenSPARQLQueryEngine] executeOp: " + op.getClass().getSimpleName());
        LOG.debug("executeOp: {}", op.getClass().getSimpleName());

        // Handle OpGenerate
        if (op instanceof OpGenerate) {
            LOG.debug("[DEBUG GenSPARQLQueryEngine] Executing OpGenerate!");
            LOG.debug("Executing OpGenerate");
            OpGenerate opGen = (OpGenerate) op;

            // Register output variables as GEN type
            for (Var outputVar : opGen.getOutputVariables()) {
                sourceTypes.put(outputVar, SourceType.GEN);
            }

            LOG.debug("[DEBUG GenSPARQLQueryEngine] Calling opGen.eval()...");
            return opGen.eval(input, execCxt);
        }

        // Handle OpProject
        if (op instanceof OpProject) {
            OpProject opProject = (OpProject) op;
            if (!(input instanceof QueryIterRoot)) {
                // A sub-select that receives the solutions of the patterns before it. SPARQL
                // evaluates a sub-select on its own, its variables that it does not select are
                // local to it, and its solutions are joined with the others by equality.
                // Projecting the incoming solutions instead dropped every variable of the outer
                // pattern that the sub-select does not select.
                QueryIterator own = new org.apache.jena.sparql.engine.iterator.QueryIterProject(
                        executeOp(opProject.getSubOp(), QueryIterRoot.create(
                                org.apache.jena.sparql.engine.binding.BindingFactory.empty(), execCxt),
                                execCxt),
                        opProject.getVars(), execCxt);
                return org.apache.jena.sparql.engine.join.Join.join(input, own, execCxt);
            }
            QueryIterator subIter = executeOp(opProject.getSubOp(), input, execCxt);
            return new org.apache.jena.sparql.engine.iterator.QueryIterProject(
                    subIter, opProject.getVars(), execCxt);
        }

        // Handle OpJoin - potentially use SimJoin for semantic matching
        if (op instanceof OpJoin) {
            OpJoin opJoin = (OpJoin) op;

            boolean simEnabled = isSimJoinEnabled(execCxt);

            // A later GENOP whose output variable an earlier GENOP of the group already binds
            // extends the solutions of the earlier one (Section 2.1): it compares its values with
            // the bound ones by similarity and keeps their terms, and its Qi then maps them. The
            // right side is therefore evaluated over the solutions of the left side, and so is
            // the GENOP of a GENOP-and-Qi join there.
            if (simEnabled && laterGenopSharesOutput(opJoin)) {
                QueryIterator left = executeOp(opJoin.getLeft(), input, execCxt);
                Op right = opJoin.getRight();
                if (right instanceof OpJoin && mapsGeneratedOutput((OpJoin) right)) {
                    return executeGeneratingSideSimJoin(((OpJoin) right).getLeft(),
                            ((OpJoin) right).getRight(), left, execCxt);
                }
                return executeOp(right, left, execCxt);
            }

            boolean needs = needsSimJoin(opJoin);
            LOG.debug("OpJoin: simJoinEnabled={}, needsSimJoin={}", simEnabled, needs);

            // Check if similarity join is needed (when GEN variables are involved)
            if (simEnabled && needs) {
                LOG.debug("Using similarity join for OpJoin");
                if (isGeneratingSideSimJoin(opJoin) || isQiWithGenop(opJoin)) {
                    return executeGeneratingSideSimJoin(opJoin.getLeft(), opJoin.getRight(), input, execCxt);
                }
                return executeSimJoin(opJoin, input, execCxt);
            }

            // Cost-based GENOP placement (C4). A group compiles to nested joins, never to an
            // OpSequence, so a planner wired only to the sequence path never sees a real query.
            // Flatten the conjunctive fragment and let the planner order it.
            if (GenSPARQLConfig.isCostBasedPlanningEnabled()) {
                List<Op> fragment = flattenConjunctiveFragment(opJoin);
                if (fragment != null) {
                    List<Op> ordered = reorderByCost(fragment, execCxt);
                    if (ordered != null) {
                        LOG.debug("Cost-based join order: {}", ordered.stream()
                                .map(e -> e.getClass().getSimpleName())
                                .collect(java.util.stream.Collectors.joining(", ")));
                        QueryIterator current = input;
                        for (Op e : ordered) {
                            current = executeOp(e, current, execCxt);
                        }
                        return current;
                    }
                }
            }

            LOG.debug("Using standard join (NOT SimJoin)");
            // Standard join: execute left, then right with left's output
            QueryIterator left = executeOp(opJoin.getLeft(), input, execCxt);
            return executeOp(opJoin.getRight(), left, execCxt);
        }

        // Handle OpSequence - like OpJoin but for sequence patterns
        if (op instanceof OpSequence) {
            OpSequence opSeq = (OpSequence) op;
            List<Op> elements = new ArrayList<>(opSeq.getElements());
            LOG.debug("OpSequence with {} elements", elements.size());

            // Does any element contain a GENOP, and does any need variables bound elsewhere?
            boolean hasGenOp = false;
            boolean hasGenOpWithInputVars = false;

            for (Op elem : elements) {
                if (containsGenOp(elem)) {
                    hasGenOp = true;
                }
                if (containsGenOpWithInputVars(elem)) {
                    hasGenOpWithInputVars = true;
                }
            }

            // Check for variable dependencies: does any OpGenerate need vars from a later BGP?
            boolean needsReordering = false;
            if (hasGenOpWithInputVars) {
                Set<Var> seenVars = new HashSet<>();
                for (Op elem : elements) {
                    Set<Var> requiredVars = getRequiredInputVariables(elem);
                    Set<Var> providedVars = getProvidedVariables(elem);

                    // Check if this element needs vars that haven't been provided yet
                    for (Var reqVar : requiredVars) {
                        if (!seenVars.contains(reqVar)) {
                            // This element needs a var not yet provided - check if it's provided later
                            for (int i = elements.indexOf(elem) + 1; i < elements.size(); i++) {
                                if (getProvidedVariables(elements.get(i)).contains(reqVar)) {
                                    needsReordering = true;
                                    LOG.debug("Variable dependency detected: ?{} needed by element {} but provided by element {}",
                                        reqVar.getName(), elements.indexOf(elem), i);
                                    break;
                                }
                            }
                        }
                    }
                    seenVars.addAll(providedVars);
                }
            }

            // Reorder: cost-based (C4) when enabled for context-mode GENOPs, else the
            // correctness-only "BGPs first" heuristic when a dependency requires it.
            if (GenSPARQLConfig.isCostBasedPlanningEnabled() && hasGenOpWithInputVars) {
                List<Op> costOrder = reorderByCost(elements, execCxt);
                if (costOrder != null) {
                    elements = costOrder;
                    LOG.debug("Cost-based reorder: {}", elements.stream()
                        .map(e -> e.getClass().getSimpleName())
                        .collect(java.util.stream.Collectors.joining(", ")));
                } else if (needsReordering) {
                    elements = reorderForDependencies(elements);
                }
            } else if (needsReordering) {
                LOG.debug("Reordering OpSequence elements for variable dependencies");
                elements = reorderForDependencies(elements);
                LOG.debug("New order: {}", elements.stream()
                    .map(e -> e.getClass().getSimpleName())
                    .collect(java.util.stream.Collectors.joining(", ")));
            }

            // In a sequence, a similarity join applies to a base-mode GENOP only: a context-mode
            // GENOP has to receive its bindings from the preceding elements, which the standard
            // sequence does. The planner compiles a GENOP and the patterns that mention its
            // output as a join, not as a sequence, and needsSimJoin covers both modes there.
            //
            // The two-element restriction is structural: the similarity join takes one generating
            // side and one KG side, and with more elements there is no single pair to hand it.
            // A longer sequence therefore runs as a standard sequence, which is the same answer
            // without the join, so this reports itself rather than passing over in silence.
            if (isSimJoinEnabled(execCxt) && hasGenOp && !hasGenOpWithInputVars) {
                if (elements.size() != 2) {
                    LOG.debug("Not using SimJoin: it pairs one generating side with one KG side, "
                            + "and this sequence has {} elements", elements.size());
                } else {
                    LOG.debug("Using SimJoin for a base-mode GENOP sequence");
                    return executeSimJoinForSequence(elements.get(0), elements.get(1), input, execCxt);
                }
            }
            LOG.debug("Using standard sequence execution (hasGenOpWithInputVars={})", hasGenOpWithInputVars);
            // Standard sequence: execute elements in order, passing results through
            QueryIterator current = input;
            for (Op elem : elements) {
                current = executeOp(elem, current, execCxt);
            }
            return current;
        }

        // Handle OpFilter - use SimScoreFilter for GEN types
        if (op instanceof OpFilter) {
            OpFilter opFilter = (OpFilter) op;
            QueryIterator subIter = executeOp(opFilter.getSubOp(), input, execCxt);
            return applyFilter(opFilter.getExprs(), subIter, execCxt);
        }

        // Handle OpTable
        if (op instanceof OpTable) {
            OpTable opTable = (OpTable) op;
            if (opTable.isJoinIdentity()) {
                // Unit table - pass through input
                return input;
            }
            // VALUES is joined with the solutions that come in, as SPARQL joins it with the rest
            // of the group. Returning the rows of the table alone dropped the solutions of the
            // patterns written before a VALUES in the middle of a group.
            return org.apache.jena.sparql.engine.join.Join.join(
                    input, opTable.getTable().iterator(execCxt), execCxt);
        }

        // Handle OpSlice
        if (op instanceof OpSlice) {
            OpSlice opSlice = (OpSlice) op;
            QueryIterator subIter = executeOp(opSlice.getSubOp(), input, execCxt);
            return new org.apache.jena.sparql.engine.iterator.QueryIterSlice(
                    subIter, opSlice.getStart(), opSlice.getLength(), execCxt);
        }

        // Handle OpGroup and OpExtend by executing the sub-operator here and letting ARQ apply
        // only the operator itself, over a unit sub-plan.
        //
        // Handing the whole subtree to QC.execute instead would let ARQ evaluate it, and ARQ
        // evaluates the right side of a join against the root binding rather than against the
        // left side's results. A context-mode GENOP under such a join then sees its input
        // variable unbound, skips every row and issues no calls, so an aggregate over a GENOP
        // came back as zero.
        if (op instanceof org.apache.jena.sparql.algebra.op.OpGroup) {
            org.apache.jena.sparql.algebra.op.OpGroup opGroup =
                    (org.apache.jena.sparql.algebra.op.OpGroup) op;
            QueryIterator subIter = executeOp(opGroup.getSubOp(), input, execCxt);
            return QC.execute(
                    org.apache.jena.sparql.algebra.op.OpGroup.create(
                            OpTable.unit(), opGroup.getGroupVars(), opGroup.getAggregators()),
                    subIter, execCxt);
        }

        if (op instanceof org.apache.jena.sparql.algebra.op.OpExtend) {
            org.apache.jena.sparql.algebra.op.OpExtend opExtend =
                    (org.apache.jena.sparql.algebra.op.OpExtend) op;
            QueryIterator subIter = executeOp(opExtend.getSubOp(), input, execCxt);
            return QC.execute(
                    org.apache.jena.sparql.algebra.op.OpExtend.create(
                            OpTable.unit(), opExtend.getVarExprList()),
                    subIter, execCxt);
        }

        // Handle OpDistinct - wrap subOp execution result
        if (op instanceof OpDistinct) {
            OpDistinct opDistinct = (OpDistinct) op;
            QueryIterator subIter = executeOp(opDistinct.getSubOp(), input, execCxt);
            // Wrap using OpDistinct with the subIter
            return QC.execute(OpDistinct.create(OpTable.unit()), subIter, execCxt);
        }

        // Handle OpReduced - wrap subOp execution result
        if (op instanceof OpReduced) {
            OpReduced opReduced = (OpReduced) op;
            QueryIterator subIter = executeOp(opReduced.getSubOp(), input, execCxt);
            return QC.execute(OpReduced.create(OpTable.unit()), subIter, execCxt);
        }

        // Handle OpOrder - wrap subOp execution result
        if (op instanceof OpOrder) {
            OpOrder opOrder = (OpOrder) op;
            QueryIterator subIter = executeOp(opOrder.getSubOp(), input, execCxt);
            return new org.apache.jena.sparql.engine.iterator.QueryIterSort(
                    subIter, opOrder.getConditions(), execCxt);
        }

        // UNION with a GENOP in a branch. ARQ evaluated the branches itself, so a base-mode GENOP
        // in a branch was joined with the patterns next to it by equality and its branch came back
        // empty, and an OPTIONAL in a branch never reached this engine. The engine evaluates every
        // branch for every incoming solution, as ARQ does, and concatenates the results. The
        // answers of a base-mode GENOP are kept for the query execution, so its prompt is still
        // sent once.
        if (op instanceof OpUnion && planContainsGenOp(op)) {
            List<Op> branches = List.of(((OpUnion) op).getLeft(), ((OpUnion) op).getRight());
            return new org.apache.jena.sparql.engine.iterator.QueryIterRepeatApply(input, execCxt) {
                @Override
                protected QueryIterator nextStage(Binding binding) {
                    org.apache.jena.sparql.engine.iterator.QueryIterConcat concat =
                            new org.apache.jena.sparql.engine.iterator.QueryIterConcat(execCxt);
                    for (Op branch : branches) {
                        concat.add(executeOp(branch,
                                org.apache.jena.sparql.engine.iterator.QueryIterSingleton.create(binding, execCxt),
                                execCxt));
                    }
                    return concat;
                }
            };
        }

        // OPTIONAL with a GENOP anywhere in its optional part. ARQ evaluates an optional part on
        // its own, against the root binding, and without the operators of this engine. A
        // context-mode GENOP then saw its input variables unbound and sent no prompt, also when
        // a BIND after it hid it from a check that did not look below BIND. A base-mode GENOP was
        // joined with the graph pattern next to it by equality, so no generated name matched an
        // entity. A FILTER of the optional part, which becomes the condition of the left join,
        // compared generated values by their terms, and so did one whose generated values come
        // from the required part. The engine therefore evaluates such an OPTIONAL itself, and a
        // solution of the required part without any extension is kept unchanged. The same holds
        // for an OPTIONAL without a GENOP after a required part with one: ARQ evaluated the
        // whole required part, and joined a GENOP with the patterns that mention its output by
        // equality.
        if (op instanceof OpLeftJoin && (planContainsGenOp(((OpLeftJoin) op).getRight())
                || planContainsGenOp(((OpLeftJoin) op).getLeft())
                || (((OpLeftJoin) op).getExprs() != null && hasGenVariables()))) {
            OpLeftJoin opLeftJoin = (OpLeftJoin) op;
            Op optional = opLeftJoin.getRight();
            org.apache.jena.sparql.expr.ExprList exprs = opLeftJoin.getExprs();
            QueryIterator required = executeOp(opLeftJoin.getLeft(), input, execCxt);
            java.util.function.Function<Binding, QueryIterator> extensions;
            // A base-mode GENOP whose output variable the required part binds extends each
            // solution of it (Section 2.1): its values are compared with the bound term by
            // similarity, so the optional part starts from that solution as well.
            Set<Var> requiredVars = org.apache.jena.sparql.algebra.OpVars.visibleVars(opLeftJoin.getLeft());
            Set<Var> boundOutputs = new HashSet<>();
            for (OpGenerate g : allGenerate(optional)) {
                if (g.isBaseMode()) {
                    g.getOutputVariables().stream().filter(requiredVars::contains).forEach(boundOutputs::add);
                }
            }
            if (planContains(optional, g -> !g.isBaseMode()) || !boundOutputs.isEmpty()) {
                // The prompt of a context-mode GENOP names variables of the required part, so
                // the optional part is evaluated once for every solution, starting from it.
                extensions = b -> {
                    Set<Var> saved = outputsBoundByInput;
                    outputsBoundByInput = boundOutputs;
                    try {
                        return executeOp(optional,
                                org.apache.jena.sparql.engine.iterator.QueryIterSingleton.create(b, execCxt),
                                execCxt);
                    } finally {
                        outputsBoundByInput = saved;
                    }
                };
            } else {
                // No context-mode GENOP: the optional part reads nothing from the required part.
                // It is evaluated once, as SPARQL evaluates an optional part, so each base-mode
                // prompt in it is sent once, and its solutions are joined with every solution of
                // the required part by equality.
                java.util.concurrent.atomic.AtomicReference<List<Binding>> solutions =
                        new java.util.concurrent.atomic.AtomicReference<>();
                extensions = b -> {
                    if (solutions.get() == null) {
                        solutions.set(evaluateIndependently(optional, execCxt));
                    }
                    List<Binding> merged = new ArrayList<>();
                    for (Binding s : solutions.get()) {
                        if (org.apache.jena.sparql.algebra.Algebra.compatible(b, s)) {
                            merged.add(org.apache.jena.sparql.algebra.Algebra.merge(b, s));
                        }
                    }
                    return org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper.create(
                            merged.iterator(), execCxt);
                };
            }
            return new QueryIterGenOptional(required, execCxt, b -> exprs == null
                    ? extensions.apply(b)
                    : applyFilter(exprs, extensions.apply(b), execCxt));
        }

        // MINUS with a GENOP. ARQ evaluated the left side itself and joined a GENOP there with
        // the patterns that mention its output by equality. The engine evaluates the left side
        // and evaluates the right side on its own, as SPARQL does, and removes each solution of
        // the left side that shares a variable with a compatible solution of the right side.
        if (op instanceof org.apache.jena.sparql.algebra.op.OpMinus && planContainsGenOp(op)) {
            org.apache.jena.sparql.algebra.op.OpMinus opMinus = (org.apache.jena.sparql.algebra.op.OpMinus) op;
            QueryIterator left = executeOp(opMinus.getLeft(), input, execCxt);
            List<Binding> removed = evaluateIndependently(opMinus.getRight(), execCxt);
            return new org.apache.jena.sparql.engine.iterator.QueryIterProcessBinding(left, execCxt) {
                @Override
                public Binding accept(Binding b) {
                    for (Binding r : removed) {
                        if (sharesVariable(b, r) && org.apache.jena.sparql.algebra.Algebra.compatible(b, r)) {
                            return null;
                        }
                    }
                    return b;
                }
            };
        }

        // GRAPH with a GENOP. ARQ would evaluate the group inside without the similarity join, so
        // the engine evaluates it over the named graph, see executeGraph.
        if (op instanceof org.apache.jena.sparql.algebra.op.OpGraph && planContainsGenOp(op)) {
            return executeGraph((org.apache.jena.sparql.algebra.op.OpGraph) op, input, execCxt);
        }

        // Fallback to standard QC execution for other ops (BGP, etc.)
        LOG.debug("Falling back to QC.execute for: {}", op.getClass().getSimpleName());
        return QC.execute(op, input, execCxt);
    }

    /**
     * GRAPH g { P } with a GENOP in P. P is translated as every group is (Section 2.1) and
     * evaluated with the named graph as the queried graph: its triple patterns match that graph,
     * and the similarity join reads labels(t) from it (Section 2.2). For an IRI g that names no
     * graph of the dataset there is no solution. For a variable ?g, P is evaluated over each named
     * graph that an incoming solution leaves possible, and ?g is bound to the graph's name. A
     * base-mode GENOP still sends a single prompt per query, since its answers are kept per GENOP.
     * The legacy grounding path ({@code CandidateExtractor}, off by default) reads the default
     * graph only.
     */
    private QueryIterator executeGraph(org.apache.jena.sparql.algebra.op.OpGraph opGraph, QueryIterator input,
                                       ExecutionContext execCxt) {
        org.apache.jena.graph.Node name = opGraph.getNode();
        DatasetGraph dsg = execCxt.getDataset();
        if (!name.isVariable()) {
            if (!dsg.containsGraph(name)) {
                input.close();
                return org.apache.jena.sparql.engine.iterator.QueryIterNullIterator.create(execCxt);
            }
            return executeOp(opGraph.getSubOp(), input, new ExecutionContext(execCxt, dsg.getGraph(name)));
        }
        Var var = Var.alloc(name);
        List<org.apache.jena.graph.Node> names = new ArrayList<>();
        dsg.listGraphNodes().forEachRemaining(names::add);
        List<Binding> out = new ArrayList<>();
        try {
            while (input.hasNext()) {
                Binding mu = input.next();
                org.apache.jena.graph.Node bound = mu.get(var);
                for (org.apache.jena.graph.Node g : bound != null ? List.of(bound) : names) {
                    if (!dsg.containsGraph(g)) {
                        continue;
                    }
                    ExecutionContext inGraph = new ExecutionContext(execCxt, dsg.getGraph(g));
                    QueryIterator sub = executeOp(opGraph.getSubOp(),
                            org.apache.jena.sparql.engine.iterator.QueryIterSingleton.create(mu, inGraph), inGraph);
                    try {
                        while (sub.hasNext()) {
                            Binding b = sub.next();
                            org.apache.jena.graph.Node inner = b.get(var);
                            if (inner == null) {
                                out.add(org.apache.jena.sparql.engine.binding.BindingFactory.binding(b, var, g));
                            } else if (inner.equals(g)) {
                                out.add(b);
                            }
                        }
                    } finally {
                        sub.close();
                    }
                }
            }
        } finally {
            input.close();
        }
        return org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper.create(out.iterator(), execCxt);
    }

    /**
     * Get the execution plan for this engine.
     */
    public Plan getPlan() {
        return super.getPlan();
    }

    /**
     * Check if a join operation needs similarity-based matching. In both modes the patterns that
     * mention an output variable of a GENOP are joined with its solutions by similarity.
     * <ul>
     *   <li>A join whose right side has no GENOP but mentions an output variable of a GENOP
     *       on the left is such a join when the left side has a context-mode GENOP, or a
     *       base-mode GENOP below BIND, OPTIONAL or MINUS, see
     *       {@link #isGeneratingSideSimJoin}.</li>
     *   <li>Any other join with a context-mode GENOP passes the solutions of its left side to
     *       its right side, from which the GENOP reads the values of its placeholders. An output
     *       variable that these solutions already bind is then compared by similarity in the
     *       GENOP itself.</li>
     *   <li>Any other join with a base-mode GENOP and without a context-mode one is a
     *       similarity join of both sides evaluated on their own.</li>
     * </ul>
     */
    private boolean needsSimJoin(OpJoin opJoin) {
        // In an optional part evaluated for a solution of the required part, a base-mode GENOP
        // whose output that solution binds extends the solution (Section 2.1), so the join
        // passes the solutions on instead of evaluating the GENOP on its own.
        if (!outputsBoundByInput.isEmpty() && planContains(opJoin, g -> g.isBaseMode()
                && !Collections.disjoint(g.getOutputVariables(), outputsBoundByInput))) {
            LOG.debug("needsSimJoin: false (a base-mode GENOP whose output the input binds)");
            return false;
        }
        if (isGeneratingSideSimJoin(opJoin)) {
            LOG.debug("needsSimJoin: true (GENOP side and patterns that mention its output)");
            return true;
        }
        if (isQiWithGenop(opJoin)) {
            LOG.debug("needsSimJoin: true (Qi of a GENOP with a GENOP of its own)");
            return true;
        }
        boolean leftHasBaseGenOp = containsBaseGenOp(opJoin.getLeft());
        boolean rightHasBaseGenOp = containsBaseGenOp(opJoin.getRight());
        boolean leftHasContextGenOp = containsGenOpWithInputVars(opJoin.getLeft());
        boolean rightHasContextGenOp = containsGenOpWithInputVars(opJoin.getRight());

        if (leftHasContextGenOp || rightHasContextGenOp) {
            LOG.debug("needsSimJoin: false (context mode GENOP that reads the left side)");
            return false;
        }

        // Use SimJoin only for base mode GENOP. Whether some GENOP elsewhere in the query has
        // already produced generated values does not matter: a join without a base-mode GENOP
        // compares terms by equality.
        boolean result = leftHasBaseGenOp || rightHasBaseGenOp;
        LOG.debug("needsSimJoin: {} (base mode check)", result);
        return result;
    }

    /**
     * Check if the operation tree contains OpGenerate in base mode (no input variables).
     */
    private boolean containsBaseGenOp(Op op) {
        if (op instanceof OpGenerate) {
            return ((OpGenerate) op).isBaseMode();
        }

        if (op instanceof OpJoin) {
            OpJoin opJoin = (OpJoin) op;
            return containsBaseGenOp(opJoin.getLeft()) ||
                   containsBaseGenOp(opJoin.getRight());
        }

        if (op instanceof OpProject) {
            return containsBaseGenOp(((OpProject) op).getSubOp());
        }

        if (op instanceof OpFilter) {
            return containsBaseGenOp(((OpFilter) op).getSubOp());
        }

        if (op instanceof OpSequence) {
            OpSequence opSeq = (OpSequence) op;
            for (Op elem : opSeq.getElements()) {
                if (containsBaseGenOp(elem)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if the operation tree contains any OpGenerate (with or without threshold)
     */
    private boolean containsGenOp(Op op) {
        if (op instanceof OpGenerate) {
            return true;
        }

        // Check sub-operations recursively
        if (op instanceof OpJoin) {
            OpJoin opJoin = (OpJoin) op;
            return containsGenOp(opJoin.getLeft()) ||
                   containsGenOp(opJoin.getRight());
        }

        if (op instanceof OpProject) {
            return containsGenOp(((OpProject) op).getSubOp());
        }

        if (op instanceof OpFilter) {
            return containsGenOp(((OpFilter) op).getSubOp());
        }

        if (op instanceof OpSlice) {
            return containsGenOp(((OpSlice) op).getSubOp());
        }

        if (op instanceof OpSequence) {
            OpSequence opSeq = (OpSequence) op;
            for (Op elem : opSeq.getElements()) {
                if (containsGenOp(elem)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if any variables are marked as GEN type.
     */
    private boolean hasGenVariables() {
        return sourceTypes.values().contains(SourceType.GEN);
    }

    /** Whether the two solutions bind a common variable, which MINUS requires. */
    private static boolean sharesVariable(Binding a, Binding b) {
        for (java.util.Iterator<Var> it = a.vars(); it.hasNext(); ) {
            if (b.contains(it.next())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filter the solutions by the expressions. Once a GENOP has generated values, {@code =} and
     * {@code !=} compare a generated value by similarity, also inside {@code ||}, {@code &&}
     * and {@code !}, and every other expression is evaluated as in SPARQL. A FILTER of a group and the condition of a left join both go
     * through here.
     */
    private QueryIterator applyFilter(Iterable<org.apache.jena.sparql.expr.Expr> exprs,
                                      QueryIterator subIter, ExecutionContext execCxt) {
        boolean hasGenVars = hasGenVariables();
        for (org.apache.jena.sparql.expr.Expr expr : exprs) {
            if (hasGenVars && comparesValues(expr)) {
                LOG.debug("Using SimScoreFilter for expr: {}", expr);
                subIter = new QueryIterSimScoreFilter(subIter, expr, newSimScoreEvaluator(execCxt),
                        sourceTypes, execCxt);
            } else {
                subIter = new org.apache.jena.sparql.engine.iterator.QueryIterFilterExpr(
                        subIter, expr, execCxt);
            }
        }
        return subIter;
    }

    /**
     * The solutions of an operator evaluated from the empty solution, as SPARQL evaluates the
     * optional part of OPTIONAL. The source types registered before stay registered: a
     * similarity join inside the operator resets them to find its generating side, and the
     * filters above still need to know which of their variables were generated.
     */
    private List<Binding> evaluateIndependently(Op op, ExecutionContext execCxt) {
        Map<Var, SourceType> registered = new HashMap<>(sourceTypes);
        List<Binding> solutions = new ArrayList<>();
        QueryIterator it = executeOp(op,
                QueryIterRoot.create(org.apache.jena.sparql.engine.binding.BindingFactory.empty(), execCxt),
                execCxt);
        try {
            it.forEachRemaining(solutions::add);
        } finally {
            it.close();
        }
        registered.forEach(sourceTypes::putIfAbsent);
        return solutions;
    }

    /**
     * Whether an expression is = or !=, or combines one with ||, && or !: the expressions in which
     * Section 2.2 compares a generated value by similarity.
     */
    private static boolean comparesValues(org.apache.jena.sparql.expr.Expr expr) {
        if (expr instanceof org.apache.jena.sparql.expr.E_Equals
                || expr instanceof org.apache.jena.sparql.expr.E_NotEquals) {
            return true;
        }
        if (expr instanceof org.apache.jena.sparql.expr.E_LogicalOr
                || expr instanceof org.apache.jena.sparql.expr.E_LogicalAnd) {
            org.apache.jena.sparql.expr.ExprFunction2 f = (org.apache.jena.sparql.expr.ExprFunction2) expr;
            return comparesValues(f.getArg1()) || comparesValues(f.getArg2());
        }
        if (expr instanceof org.apache.jena.sparql.expr.E_LogicalNot) {
            return comparesValues(((org.apache.jena.sparql.expr.E_LogicalNot) expr).getArg());
        }
        return false;
    }

    /**
     * Execute a similarity-based join.
     *
     * For SimJoin, we execute both sides independently and then
     * perform a similarity-based cartesian product match using SimScore.
     */
    private QueryIterator executeSimJoin(OpJoin opJoin, QueryIterator input, ExecutionContext execCxt) {
        LOG.debug("executeSimJoin: Executing similarity-based join");
        return executeSimJoinForSequence(opJoin.getLeft(), opJoin.getRight(), input, execCxt);
    }

    /**
     * Execute a similarity-based join of two Op elements.
     *
     * <p>The join compares the values a GENOP generated with the KG values on the other side,
     * so the side that generates must be known: its variables are typed GEN, the other side's
     * RDF. The join is commutative, so the generating side is moved to the right whatever the
     * order the algebra arrived in. That order is not the author's to control: a group such as
     * {@code { ?team a ex:Team . GENOP("...", (?team), <model>, 0.85) }} has its KG pattern moved
     * after the GENOP, because the pattern mentions the GENOP's output variable, and with the
     * GENOP on the left its generated values were typed RDF and compared by exact equality.
     */
    private QueryIterator executeSimJoinForSequence(Op leftOp, Op rightOp, QueryIterator input, ExecutionContext execCxt) {
        if (containsGenOp(leftOp) && !containsGenOp(rightOp)) {
            Op generating = leftOp;
            leftOp = rightOp;
            rightOp = generating;
        }
        LOG.debug("executeSimJoinForSequence: left={}, right={}",
                  leftOp.getClass().getSimpleName(), rightOp.getClass().getSimpleName());

        // The similarity join is evaluated once, from the empty solution, as SPARQL evaluates a
        // group, and its solutions are then joined with every incoming solution. Reading only
        // the first incoming solution lost the others whenever the join sat below another one.
        Map<Var, SourceType> leftTypes = new HashMap<>();
        QueryIterator leftIter = executeOp(leftOp,
                QueryIterRoot.create(org.apache.jena.sparql.engine.binding.BindingFactory.empty(), execCxt), execCxt);

        // Execute right side (e.g., OpGenerate) independently with empty binding
        // For base mode GENOP, it doesn't need input bindings
        // Clear sourceTypes before executing right side to get fresh GEN types, and restore the
        // types registered before afterwards: a FILTER above still needs to know which of its
        // variables an earlier GENOP generated.
        Map<Var, SourceType> registered = new HashMap<>(sourceTypes);
        sourceTypes.clear();
        QueryIterator rightIter = executeOp(rightOp,
                QueryIterRoot.create(org.apache.jena.sparql.engine.binding.BindingFactory.empty(), execCxt), execCxt);

        // rightTypes now contains GEN variables from OpGenerate execution
        Map<Var, SourceType> rightTypes = new HashMap<>(sourceTypes);
        List<OpGenerate> generating = new ArrayList<>();
        collectGeneratingSide(rightOp, generating);
        keepOnlyCurrentOutputs(rightTypes, generating, leftOp);
        rightTypes.keySet().removeAll(patternBoundVars(rightOp));
        registered.forEach(sourceTypes::putIfAbsent);

        LOG.debug("executeSimJoinForSequence: leftTypes={}, rightTypes={}", leftTypes, rightTypes);

        QueryIterator simJoin = new QueryIterSimJoin(leftIter, rightIter,
                joinEvaluator(execCxt, generating, leftOp), leftTypes, rightTypes, execCxt);
        return org.apache.jena.sparql.engine.join.Join.join(input, simJoin, execCxt);
    }

    /**
     * Whether the join is a GENOP side and a pattern side that the semantics joins by
     * similarity: the right side has no GENOP and mentions an output variable of a GENOP on the
     * left, see {@link #collectGeneratingSide}. The planner compiles a GENOP and the elements
     * that mention its output in this shape, and so does a group in written order when the
     * planner is switched off, also when a BIND, OPTIONAL or MINUS stands between them. A left
     * side with base-mode GENOPs only that {@link #containsBaseGenOp} recognises
     * keeps the similarity join of both sides evaluated on their own.
     */
    private boolean isGeneratingSideSimJoin(OpJoin opJoin) {
        if (planContainsGenOp(opJoin.getRight())) {
            return false;
        }
        List<OpGenerate> generating = new ArrayList<>();
        collectGeneratingSide(opJoin.getLeft(), generating);
        if (generating.stream().allMatch(OpGenerate::isBaseMode) && containsBaseGenOp(opJoin.getLeft())) {
            return false;
        }
        Set<Var> mentioned = org.apache.jena.sparql.algebra.OpVars.visibleVars(opJoin.getRight());
        for (OpGenerate g : generating) {
            for (Var v : g.getOutputVariables()) {
                if (mentioned.contains(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The GENOPs whose solutions a side of a join passes on: through joins, sequences, filters,
     * BIND, the required part of OPTIONAL and the left side of MINUS, not into a sub-select, a
     * UNION, an optional part or the right side of MINUS.
     */
    private static void collectGeneratingSide(Op op, List<OpGenerate> out) {
        if (op instanceof OpGenerate) {
            out.add((OpGenerate) op);
        } else if (op instanceof OpJoin) {
            collectGeneratingSide(((OpJoin) op).getLeft(), out);
            collectGeneratingSide(((OpJoin) op).getRight(), out);
        } else if (op instanceof OpSequence) {
            for (Op e : ((OpSequence) op).getElements()) {
                collectGeneratingSide(e, out);
            }
        } else if (op instanceof OpFilter) {
            collectGeneratingSide(((OpFilter) op).getSubOp(), out);
        } else if (op instanceof org.apache.jena.sparql.algebra.op.OpExtend) {
            collectGeneratingSide(((org.apache.jena.sparql.algebra.op.OpExtend) op).getSubOp(), out);
        } else if (op instanceof OpLeftJoin) {
            collectGeneratingSide(((OpLeftJoin) op).getLeft(), out);
        } else if (op instanceof org.apache.jena.sparql.algebra.op.OpMinus) {
            collectGeneratingSide(((org.apache.jena.sparql.algebra.op.OpMinus) op).getLeft(), out);
        }
    }

    /**
     * The similarity join of a GENOP side and the patterns that mention an output variable of a
     * GENOP on that side.
     *
     * <p>The GENOP side is evaluated from the incoming solutions, because a context-mode GENOP
     * reads the values of its placeholders from them and the patterns before a GENOP are part of
     * this side, and it is materialized. The pattern side is
     * evaluated on its own, from the empty solution. Two solutions join when every output
     * variable of the current GENOP that they share has a generated value similar enough to a
     * label of the term of the pattern side, and every other shared variable has the same term
     * on both sides. The
     * joined solution keeps the term of the pattern side. The GENOP side already carries the
     * incoming solutions, so the result is not joined with them again.
     */
    private QueryIterator executeGeneratingSideSimJoin(Op generatingOp, Op patternOp, QueryIterator input,
                                                       ExecutionContext execCxt) {
        LOG.debug("executeGeneratingSideSimJoin: generating={}, patterns={}",
                generatingOp.getClass().getSimpleName(), patternOp.getClass().getSimpleName());
        QueryIterator patterns = executeOp(patternOp,
                QueryIterRoot.create(org.apache.jena.sparql.engine.binding.BindingFactory.empty(), execCxt),
                execCxt);
        // Taken after the pattern side, so that the outputs of a GENOP in it, as in a nested
        // group of Qi, stay registered for the filters above.
        Map<Var, SourceType> registered = new HashMap<>(sourceTypes);

        sourceTypes.clear();
        List<Binding> generated = new ArrayList<>();
        QueryIterator it = executeOp(generatingOp, input, execCxt);
        try {
            it.forEachRemaining(generated::add);
        } finally {
            it.close();
        }
        Map<Var, SourceType> generatedTypes = new HashMap<>(sourceTypes);
        // Part of the GENOP side may have been evaluated by ARQ, for example an OPTIONAL without
        // a GENOP in its optional part, which registers no output variables of the GENOPs below it.
        List<OpGenerate> generating = new ArrayList<>();
        collectGeneratingSide(generatingOp, generating);
        for (OpGenerate g : generating) {
            for (Var v : g.getOutputVariables()) {
                generatedTypes.putIfAbsent(v, SourceType.GEN);
            }
        }
        keepOnlyCurrentOutputs(generatedTypes, generating, patternOp);
        generatedTypes.keySet().removeAll(patternBoundVars(generatingOp));
        // The filters above still need the variables generated before this join.
        registered.forEach(sourceTypes::putIfAbsent);

        return new QueryIterSimJoin(patterns,
                org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper.create(generated.iterator(), execCxt),
                joinEvaluator(execCxt, generating, patternOp), new HashMap<>(), generatedTypes, execCxt);
    }

    /** Every GENOP in the plan, walking by arity as {@link #planContains} does. */
    private static List<OpGenerate> allGenerate(Op op) {
        List<OpGenerate> out = new ArrayList<>();
        planContains(op, g -> {
            out.add(g);
            return false;
        });
        return out;
    }

    /** The output variables of the GENOPs whose solutions a side passes on. */
    private static Set<Var> generatedOutputs(Op side) {
        List<OpGenerate> generating = new ArrayList<>();
        collectGeneratingSide(side, generating);
        Set<Var> outputs = new HashSet<>();
        generating.forEach(g -> outputs.addAll(g.getOutputVariables()));
        return outputs;
    }

    /**
     * Whether the right side holds a GENOP with an output variable of a GENOP that the left side
     * passes on: two GENOPs of a group with the same output variable, the later one on the right.
     */
    private static boolean laterGenopSharesOutput(OpJoin opJoin) {
        Set<Var> outputs = generatedOutputs(opJoin.getLeft());
        return !outputs.isEmpty() && planContains(opJoin.getRight(),
                g -> !Collections.disjoint(g.getOutputVariables(), outputs));
    }

    /**
     * Whether the join is a GENOP side and a side without GENOP that mentions an output variable
     * of a GENOP on the GENOP side, in either mode.
     */
    private static boolean mapsGeneratedOutput(OpJoin opJoin) {
        if (planContainsGenOp(opJoin.getRight())) {
            return false;
        }
        Set<Var> mentioned = org.apache.jena.sparql.algebra.OpVars.visibleVars(opJoin.getRight());
        return !Collections.disjoint(generatedOutputs(opJoin.getLeft()), mentioned);
    }

    /**
     * Whether the right side is the Qi of a GENOP on the left and contains a GENOP of its own, as
     * a nested group does: it binds an output variable of that GENOP by a graph pattern, and none
     * of its GENOPs reads a variable of the left side or generates an output of it. Section 2.1
     * evaluates Qi on its own and joins it with the solutions of the GENOP by similarity. A right
     * side whose GENOP reads the left side continues the solutions of the left side instead.
     */
    private static boolean isQiWithGenop(OpJoin opJoin) {
        Op right = opJoin.getRight();
        if (!planContainsGenOp(right)) {
            return false;
        }
        Set<Var> outputs = generatedOutputs(opJoin.getLeft());
        if (Collections.disjoint(outputs, patternBoundVars(right))) {
            return false;
        }
        Set<Var> leftVars = org.apache.jena.sparql.algebra.OpVars.visibleVars(opJoin.getLeft());
        return !planContains(right, g -> !Collections.disjoint(g.getInputVariables(), leftVars)
                || !Collections.disjoint(g.getOutputVariables(), outputs));
    }

    /**
     * Keep generated only the output variables of the current GENOP of a generating side: the
     * last one, in written order, whose output the other side mentions, Gi for the patterns Qi.
     * The output variables of the other GENOPs of the side are compared by equality, as every
     * shared variable outside v̄i is: Ti-1 has already mapped them, or they keep their
     * generated values.
     */
    private static void keepOnlyCurrentOutputs(Map<Var, SourceType> types, List<OpGenerate> generating,
                                               Op other) {
        OpGenerate current = currentGenerate(generating, other);
        if (current == null) {
            return;
        }
        Set<Var> kept = new HashSet<>(current.getOutputVariables());
        for (OpGenerate g : generating) {
            for (Var v : g.getOutputVariables()) {
                if (!kept.contains(v)) {
                    types.remove(v);
                }
            }
        }
    }

    /**
     * The variables that a graph pattern or VALUES binds on the path along which a side passes its
     * solutions on (as {@link #collectGeneratingSide} walks it: joins, sequences, filters, BIND, the
     * required part of OPTIONAL and the left side of MINUS), outside its GENOPs. An output variable
     * among them has been joined with the graph on that side, as in a group whose GENOP and
     * patterns form Ti, so its values are terms of the graph, compared by equality. An optional
     * part or the right side of MINUS does not bind the solutions that come out.
     */
    private static Set<Var> patternBoundVars(Op op) {
        Set<Var> vars = new HashSet<>();
        collectPatternBoundVars(op, vars);
        return vars;
    }

    private static void collectPatternBoundVars(Op op, Set<Var> out) {
        if (op instanceof org.apache.jena.sparql.algebra.op.OpBGP
                || op instanceof org.apache.jena.sparql.algebra.op.OpTriple
                || op instanceof org.apache.jena.sparql.algebra.op.OpPath
                || op instanceof org.apache.jena.sparql.algebra.op.OpQuadPattern
                || op instanceof OpTable) {
            out.addAll(org.apache.jena.sparql.algebra.OpVars.mentionedVars(op));
        } else if (op instanceof OpJoin) {
            collectPatternBoundVars(((OpJoin) op).getLeft(), out);
            collectPatternBoundVars(((OpJoin) op).getRight(), out);
        } else if (op instanceof OpSequence) {
            for (Op e : ((OpSequence) op).getElements()) {
                collectPatternBoundVars(e, out);
            }
        } else if (op instanceof OpFilter) {
            collectPatternBoundVars(((OpFilter) op).getSubOp(), out);
        } else if (op instanceof org.apache.jena.sparql.algebra.op.OpExtend) {
            collectPatternBoundVars(((org.apache.jena.sparql.algebra.op.OpExtend) op).getSubOp(), out);
        } else if (op instanceof OpLeftJoin) {
            collectPatternBoundVars(((OpLeftJoin) op).getLeft(), out);
        } else if (op instanceof org.apache.jena.sparql.algebra.op.OpMinus) {
            collectPatternBoundVars(((org.apache.jena.sparql.algebra.op.OpMinus) op).getLeft(), out);
        }
    }

    /**
     * The current GENOP of a generating side: the last one, in written order, whose output the
     * other side mentions, Gi for the patterns Qi. Null if the other side mentions no output.
     */
    private static OpGenerate currentGenerate(List<OpGenerate> generating, Op other) {
        Set<Var> mentioned = org.apache.jena.sparql.algebra.OpVars.visibleVars(other);
        OpGenerate current = null;
        for (OpGenerate g : generating) {
            if (!java.util.Collections.disjoint(g.getOutputVariables(), mentioned)) {
                current = g;
            }
        }
        return current;
    }

    /**
     * The evaluator of one similarity join Qi ⋈θi GENOP(...): its output variables take the
     * threshold θi of the current GENOP Gi, its own argument or θ0, and not the threshold of
     * another GENOP that generates the same variable. Without a current GENOP, each output takes
     * the threshold of the last GENOP of the side that generates it.
     */
    private SimScoreEvaluator joinEvaluator(ExecutionContext execCxt, List<OpGenerate> generating, Op other) {
        ThresholdRegistry thresholds = new ThresholdRegistry();
        thresholds.setDefaultThreshold(GenSPARQLConfig.getDefaultThreshold());
        OpGenerate current = currentGenerate(generating, other);
        for (OpGenerate g : current != null ? List.of(current) : generating) {
            for (Var v : g.getOutputVariables()) {
                thresholds.setThreshold(v, g.getEffectiveThreshold());
            }
        }
        return new SimScoreEvaluator(getSimText(execCxt), thresholds, new GraphLabels(execCxt.getActiveGraph()));
    }

    /**
     * The thresholds of the FILTER comparisons {@code ?x = t} of a query (Section 2.2): an output
     * variable takes the threshold of the first GENOP, in written order, that generates it, since a
     * solution keeps the term that the first GENOP bound. Other variables take θ0.
     */
    private static ThresholdRegistry filterThresholds(Op op) {
        ThresholdRegistry thresholds = new ThresholdRegistry();
        thresholds.setDefaultThreshold(GenSPARQLConfig.getDefaultThreshold());
        for (OpGenerate g : allGenerate(op)) {
            for (Var v : g.getOutputVariables()) {
                thresholds.setThresholdIfAbsent(v, g.getEffectiveThreshold());
            }
        }
        return thresholds;
    }

    /**
     * A SimScore evaluator for this execution. Canon(t) of an IRI is its label in the graph
     * the query runs over, so a generated name can match an entity whose IRI is opaque.
     */
    private SimScoreEvaluator newSimScoreEvaluator(ExecutionContext execCxt) {
        return new SimScoreEvaluator(getSimText(execCxt), getThresholdRegistry(execCxt),
                new GraphLabels(execCxt.getActiveGraph()));
    }

    /**
     * Get the source types map (for testing/debugging).
     */
    public Map<Var, SourceType> getSourceTypes() {
        return new HashMap<>(sourceTypes);
    }

    /**
     * Get variables that an Op provides (binds).
     * For BGP: all variables in the triple patterns
     * For OpGenerate: output variables
     */
    private Set<Var> getProvidedVariables(Op op) {
        Set<Var> vars = new HashSet<>();
        collectProvidedVariables(op, vars);
        return vars;
    }

    private void collectProvidedVariables(Op op, Set<Var> vars) {
        if (op instanceof org.apache.jena.sparql.algebra.op.OpBGP) {
            org.apache.jena.sparql.algebra.op.OpBGP bgp = (org.apache.jena.sparql.algebra.op.OpBGP) op;
            for (org.apache.jena.graph.Triple t : bgp.getPattern().getList()) {
                if (t.getSubject().isVariable()) vars.add(Var.alloc(t.getSubject()));
                if (t.getPredicate().isVariable()) vars.add(Var.alloc(t.getPredicate()));
                if (t.getObject().isVariable()) vars.add(Var.alloc(t.getObject()));
            }
        } else if (op instanceof OpGenerate) {
            vars.addAll(((OpGenerate) op).getOutputVariables());
        } else if (op instanceof OpJoin) {
            collectProvidedVariables(((OpJoin) op).getLeft(), vars);
            collectProvidedVariables(((OpJoin) op).getRight(), vars);
        } else if (op instanceof OpSequence) {
            for (Op elem : ((OpSequence) op).getElements()) {
                collectProvidedVariables(elem, vars);
            }
        } else if (op instanceof OpProject) {
            collectProvidedVariables(((OpProject) op).getSubOp(), vars);
        } else if (op instanceof OpFilter) {
            collectProvidedVariables(((OpFilter) op).getSubOp(), vars);
        }
    }

    /**
     * Get input variables that an Op requires (needs from previous bindings).
     * For OpGenerate: input variables (placeholders in template)
     */
    private Set<Var> getRequiredInputVariables(Op op) {
        Set<Var> vars = new HashSet<>();
        collectRequiredVariables(op, vars);
        return vars;
    }

    private void collectRequiredVariables(Op op, Set<Var> vars) {
        if (op instanceof OpGenerate) {
            vars.addAll(((OpGenerate) op).getInputVariables());
        } else if (op instanceof OpJoin) {
            collectRequiredVariables(((OpJoin) op).getLeft(), vars);
            collectRequiredVariables(((OpJoin) op).getRight(), vars);
        } else if (op instanceof OpSequence) {
            for (Op elem : ((OpSequence) op).getElements()) {
                collectRequiredVariables(elem, vars);
            }
        } else if (op instanceof OpProject) {
            collectRequiredVariables(((OpProject) op).getSubOp(), vars);
        } else if (op instanceof OpFilter) {
            collectRequiredVariables(((OpFilter) op).getSubOp(), vars);
        }
    }

    /**
     * Check if an Op contains an OpGenerate that requires input variables.
     */
    private boolean containsGenOpWithInputVars(Op op) {
        if (op instanceof OpGenerate) {
            return !((OpGenerate) op).isBaseMode();
        }
        if (op instanceof OpJoin) {
            return containsGenOpWithInputVars(((OpJoin) op).getLeft()) ||
                   containsGenOpWithInputVars(((OpJoin) op).getRight());
        }
        if (op instanceof OpSequence) {
            for (Op elem : ((OpSequence) op).getElements()) {
                if (containsGenOpWithInputVars(elem)) return true;
            }
        }
        if (op instanceof OpProject) {
            return containsGenOpWithInputVars(((OpProject) op).getSubOp());
        }
        if (op instanceof OpFilter) {
            return containsGenOpWithInputVars(((OpFilter) op).getSubOp());
        }
        return false;
    }

    /**
     * Check if an Op is or contains only BGP patterns (no OpGenerate).
     */
    private boolean isBGPOnly(Op op) {
        if (op instanceof org.apache.jena.sparql.algebra.op.OpBGP) {
            return true;
        }
        if (op instanceof OpGenerate) {
            return false;
        }
        if (op instanceof OpJoin) {
            return isBGPOnly(((OpJoin) op).getLeft()) && isBGPOnly(((OpJoin) op).getRight());
        }
        if (op instanceof OpSequence) {
            for (Op elem : ((OpSequence) op).getElements()) {
                if (!isBGPOnly(elem)) return false;
            }
            return true;
        }
        if (op instanceof OpProject) {
            return isBGPOnly(((OpProject) op).getSubOp());
        }
        if (op instanceof OpFilter) {
            return isBGPOnly(((OpFilter) op).getSubOp());
        }
        // OpTable, etc. are considered "BGP-like" (non-generative)
        return !(op instanceof OpGenerate);
    }

    /**
     * Reorder OpSequence elements to handle variable dependencies.
     * Returns a list with BGPs first, then OpGenerates that depend on them.
     */
    private List<Op> reorderForDependencies(List<Op> elements) {
        List<Op> bgpElements = new ArrayList<>();
        List<Op> genElements = new ArrayList<>();

        for (Op elem : elements) {
            if (isBGPOnly(elem)) {
                bgpElements.add(elem);
            } else {
                genElements.add(elem);
            }
        }

        // Combine: BGPs first, then generative elements
        List<Op> reordered = new ArrayList<>();
        reordered.addAll(bgpElements);
        reordered.addAll(genElements);

        return reordered;
    }

    /**
     * Cost-based reorder (C4): translate the sequence elements into planner items and let
     * {@link org.gensparql.engine.cost.GenOpPlanner} pick the cost-minimal legal order.
     * Dependency legality (a GENOP's inputs bound before it) is preserved; variables not
     * bound by any element are treated as externally supplied (from the incoming binding).
     * Returns {@code null} on any failure so the caller falls back to the safe heuristic.
     */
    private List<Op> reorderByCost(List<Op> elements, ExecutionContext execCxt) {
        try {
            Set<Var> allBound = new HashSet<>();
            for (Op e : elements) {
                allBound.addAll(getProvidedVariables(e));
            }

            // KG statistics over the feeding BGP patterns (C2 ↔ C3 bridge): real binding
            // count N and, per GENOP input var, the distinct-value count that determines the
            // dedup ratio. Unavailable/errored stats fall back to neutral factors.
            org.apache.jena.rdf.model.Model statsModel = null;
            String bgpPattern = null;
            long nAll = -1;
            java.util.Map<Integer, Double> cardinalityFactors = new java.util.HashMap<>();
            try {
                List<org.apache.jena.graph.Triple> triples = new ArrayList<>();
                for (Op e : elements) {
                    if (isBGPOnly(e)) {
                        org.gensparql.engine.cost.OpStats.collectTriples(e, triples);
                    }
                }
                if (!triples.isEmpty() && execCxt != null && execCxt.getActiveGraph() != null) {
                    statsModel = org.apache.jena.rdf.model.ModelFactory
                            .createModelForGraph(execCxt.getActiveGraph());
                    bgpPattern = org.gensparql.engine.cost.OpStats.buildPattern(triples);
                    nAll = org.gensparql.engine.cost.KgStats.bindings(statsModel, "", bgpPattern);
                    cardinalityFactors = perPatternFactors(elements, statsModel);
                }
            } catch (Exception statsEx) {
                LOG.debug("KG stats unavailable for cost planning: {}", statsEx.getMessage());
                statsModel = null;
                cardinalityFactors = new java.util.HashMap<>();
            }

            List<org.gensparql.engine.cost.GenOpPlanner.PlanItem> items = new ArrayList<>();
            java.util.Map<String, Op> byLabel = new java.util.HashMap<>();
            for (int i = 0; i < elements.size(); i++) {
                Op e = elements.get(i);
                String label = "e" + i;
                byLabel.put(label, e);

                Set<String> requires = new HashSet<>();
                for (Var v : getRequiredInputVariables(e)) {
                    if (allBound.contains(v)) {
                        requires.add(v.getName()); // external vars are pre-bound, not a constraint
                    }
                }
                Set<String> binds = new HashSet<>();
                for (Var v : getProvidedVariables(e)) {
                    binds.add(v.getName());
                }

                if (isBGPOnly(e)) {
                    items.add(new org.gensparql.engine.cost.GenOpPlanner.KgPattern(
                            label, requires, binds, cardinalityFactors.getOrDefault(i, 1.0)));
                } else {
                    OpGenerate g = findGenerate(e);
                    String prompt = g != null ? g.getPromptTemplate() : "";
                    double fanOut = org.gensparql.engine.cost.FanOutEstimator.promptPrior(prompt);
                    // C2 tier-2: if grounding is on, only a fraction of generated candidates
                    // survive grounding, so scale fan-out by the survival prior at this op's θ.
                    if (GenSPARQLConfig.isGroundingEnabled() && g != null) {
                        fanOut = org.gensparql.engine.cost.FanOutEstimator.effectiveFanOut(
                                fanOut, groundingThresholdOf(g));
                    }
                    int tokens = org.gensparql.engine.cost.GenOpCostModel.estimateTokens(prompt);
                    double dedupRatio = dedupRatioFor(g, statsModel, bgpPattern, nAll, allBound);
                    items.add(new org.gensparql.engine.cost.GenOpPlanner.GenOpItem(
                            label, requires, binds, fanOut, tokens, tokens, dedupRatio));
                }
            }

            org.gensparql.engine.cost.GenOpCostModel model =
                    org.gensparql.engine.cost.GenOpCostModel.builder().build();
            org.gensparql.engine.cost.GenOpPlanner.PlanResult res =
                    new org.gensparql.engine.cost.GenOpPlanner()
                            .plan(items, 1.0, model, GenSPARQLConfig.isBatchDedupEnabled());

            List<Op> ordered = new ArrayList<>(elements.size());
            for (org.gensparql.engine.cost.GenOpPlanner.PlanItem it : res.order()) {
                ordered.add(byLabel.get(it.label()));
            }
            return ordered;
        } catch (Exception ex) {
            LOG.warn("Cost-based reorder failed, falling back to dependency reorder: {}",
                    ex.getMessage());
            return null;
        }
    }

    /**
     * Flatten a join tree into the conjunctive fragment the planner can reorder, or null if
     * this tree is not one.
     *
     * <p>Joins are commutative and associative, so any dependency-respecting order of the
     * leaves computes the same result. The fragment qualifies only when every leaf is either a
     * BGP-only op or a GENOP, and at least one is a GENOP: with no GENOP there is nothing
     * expensive to place, and anything else (OPTIONAL, UNION, MINUS, SERVICE) is not freely
     * reorderable and is left to the standard join.
     */
    private List<Op> flattenConjunctiveFragment(Op op) {
        List<Op> leaves = new ArrayList<>();
        if (!collectConjunctiveLeaves(op, leaves)) {
            return null;
        }
        boolean hasGenOp = false;
        for (Op leaf : leaves) {
            if (!isBGPOnly(leaf)) {
                hasGenOp = true;
                break;
            }
        }
        return (hasGenOp && leaves.size() > 1) ? leaves : null;
    }

    /** Collect join leaves; false as soon as a leaf is neither BGP-only nor a GENOP. */
    private boolean collectConjunctiveLeaves(Op op, List<Op> out) {
        if (op instanceof OpJoin) {
            OpJoin join = (OpJoin) op;
            return collectConjunctiveLeaves(join.getLeft(), out)
                && collectConjunctiveLeaves(join.getRight(), out);
        }
        if (op instanceof OpTable && ((OpTable) op).isJoinIdentity()) {
            return true; // unit table contributes nothing to the fragment
        }
        if (op instanceof OpGenerate || isBGPOnly(op)) {
            out.add(op);
            return true;
        }
        return false;
    }

    /**
     * Per-pattern cardinality factors, keyed by element index.
     *
     * <p>The planner multiplies the factors of the patterns placed so far, so a factor has to
     * be the multiplier that pattern contributes, not a standalone count. Factors are read off
     * the cumulative joins in author order: with joint(i) the number of solutions of patterns
     * 0..i taken together, pattern i contributes joint(i) / joint(i-1). The product over any
     * prefix is then that prefix's real join cardinality, and the product over all of them is
     * the real result size.
     *
     * <p>This is what lets the planner tell placements apart. Loading the full join count onto
     * the first pattern and giving the rest 1.0 makes every placement after the first pattern
     * look equally expensive, so a GENOP sitting before a fan-out pattern costs the same as one
     * sitting after it and the planner cannot prefer the cheap order.
     *
     * <p>Non-prefix subsets reuse these factors, which is the usual independence approximation;
     * exact join cardinality is not decomposable into per-pattern multipliers.
     */
    private java.util.Map<Integer, Double> perPatternFactors(
            List<Op> elements, org.apache.jena.rdf.model.Model statsModel) {
        java.util.Map<Integer, Double> factors = new java.util.HashMap<>();
        List<org.apache.jena.graph.Triple> prefix = new ArrayList<>();
        double previous = 1.0;

        for (int i = 0; i < elements.size(); i++) {
            Op e = elements.get(i);
            if (!isBGPOnly(e)) {
                continue; // a GENOP contributes its fan-out, not a KG cardinality
            }
            org.gensparql.engine.cost.OpStats.collectTriples(e, prefix);
            if (prefix.isEmpty()) {
                continue; // e.g. a unit table: neutral
            }
            long joint = org.gensparql.engine.cost.KgStats.bindings(
                    statsModel, "", org.gensparql.engine.cost.OpStats.buildPattern(prefix));
            // An empty prefix join makes the whole fragment empty; ordering is then irrelevant,
            // so stay neutral rather than dividing by zero.
            factors.put(i, previous > 0 ? joint / previous : 1.0);
            previous = joint;
        }
        return factors;
    }

    /**
     * Grounding threshold for a GENOP, resolved the same way execution resolves it, so the
     * survival prior the planner costs matches the threshold grounding will actually apply.
     */
    private double groundingThresholdOf(OpGenerate g) {
        return g.getEffectiveGroundingThreshold();
    }

    /** Dedup ratio D/N for a GENOP's (single) input variable over the feeding BGP, or 1.0. */
    private double dedupRatioFor(OpGenerate g, org.apache.jena.rdf.model.Model statsModel,
                                 String bgpPattern, long nAll, Set<Var> allBound) {
        if (g == null || statsModel == null || bgpPattern == null || nAll <= 0) {
            return 1.0;
        }
        for (Var v : g.getInputVariables()) {
            if (allBound.contains(v)) {
                long d = org.gensparql.engine.cost.KgStats
                        .distinctBindings(statsModel, "", bgpPattern, v.getName());
                // KgStats.ratio guards d<=0 (var absent from the BGP, e.g. a chained GENOP's
                // output) -> 1.0, and clamps to (0,1] — one shared definition of the ratio.
                return org.gensparql.engine.cost.KgStats.ratio(d, nAll);
            }
        }
        return 1.0;
    }

    /** Find the first OpGenerate nested inside a sequence element, or null. */
    private OpGenerate findGenerate(Op op) {
        if (op instanceof OpGenerate) {
            return (OpGenerate) op;
        }
        if (op instanceof OpProject) {
            return findGenerate(((OpProject) op).getSubOp());
        }
        if (op instanceof OpFilter) {
            return findGenerate(((OpFilter) op).getSubOp());
        }
        if (op instanceof OpJoin) {
            OpGenerate g = findGenerate(((OpJoin) op).getLeft());
            return g != null ? g : findGenerate(((OpJoin) op).getRight());
        }
        if (op instanceof OpSequence) {
            for (Op e : ((OpSequence) op).getElements()) {
                OpGenerate g = findGenerate(e);
                if (g != null) {
                    return g;
                }
            }
        }
        return null;
    }
}
