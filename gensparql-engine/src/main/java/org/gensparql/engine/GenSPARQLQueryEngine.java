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
import org.gensparql.engine.iterator.QueryIterSimJoin;
import org.gensparql.engine.iterator.QueryIterSimScoreFilter;
import org.gensparql.engine.op.OpGenerate;
import org.gensparql.engine.similarity.EmbeddingSimText;
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

    public GenSPARQLQueryEngine(Op op, DatasetGraph dataset, Binding input, Context context) {
        super(op, dataset, input, context);
        LOG.debug("[DEBUG GenSPARQLQueryEngine CONSTRUCTOR] Op: " + op.getClass().getSimpleName());
    }

    /**
     * Skip ARQ's optimizer for a plan containing a GENOP.
     *
     * <p>The optimizer reasons about which variables an operator uses and binds, and for an
     * OpExt it reads that off {@code effectiveOp()}. OpGenerate reports a unit table there, so
     * the optimizer is told the GENOP neither reads nor binds anything and is free to drop the
     * patterns that only feed it. That is what happened to
     * {@code SELECT ?g { { SELECT ?g { ?s rdfs:label ?l . GENOP("...{?l}...", (?g), M) } } }}:
     * the pattern binding ?l was pruned because nothing else projected it, the prompt then had
     * an unbound variable, every row was skipped and the query returned nothing without
     * issuing a single call. Projecting ?l as well made the same query work, which is not a
     * distinction the semantics should draw.
     *
     * <p>Rewriting a plan on an analysis that cannot see the operator is not worth the gain, so
     * these plans keep their shape. GENOP placement is handled by the cost planner, which does
     * understand the operator.
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
        if (op instanceof OpGenerate) {
            return true;
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.Op1) {
            return planContainsGenOp(((org.apache.jena.sparql.algebra.op.Op1) op).getSubOp());
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.Op2) {
            org.apache.jena.sparql.algebra.op.Op2 op2 = (org.apache.jena.sparql.algebra.op.Op2) op;
            return planContainsGenOp(op2.getLeft()) || planContainsGenOp(op2.getRight());
        }
        if (op instanceof org.apache.jena.sparql.algebra.op.OpN) {
            for (Op e : ((org.apache.jena.sparql.algebra.op.OpN) op).getElements()) {
                if (planContainsGenOp(e)) {
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

        ExecutionContext execCxt = new ExecutionContext(context, dsg.getDefaultGraph(), dsg, QC.getFactory(context));
        QueryIterator qIter = QueryIterRoot.create(input, execCxt);
        LOG.debug("[DEBUG GenSPARQLQueryEngine.eval] Created QueryIterRoot, about to call executeOp");

        // Clear source types for new query
        sourceTypes.clear();

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
                LOG.debug("Could not initialize EmbeddingSimText, using Jaccard fallback");
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

            // Register thresholds if specified
            if (opGen.hasThreshold()) {
                ThresholdRegistry registry = getThresholdRegistry(execCxt);
                Double threshold = opGen.getThreshold();
                for (Var outputVar : opGen.getOutputVariables()) {
                    registry.setThreshold(outputVar, threshold);
                    LOG.debug("Registered threshold {} for variable ?{}", threshold, outputVar.getName());
                }
            }

            LOG.debug("[DEBUG GenSPARQLQueryEngine] Calling opGen.eval()...");
            return opGen.eval(input, execCxt);
        }

        // Handle OpProject
        if (op instanceof OpProject) {
            OpProject opProject = (OpProject) op;
            QueryIterator subIter = executeOp(opProject.getSubOp(), input, execCxt);
            return new org.apache.jena.sparql.engine.iterator.QueryIterProject(
                    subIter, opProject.getVars(), execCxt);
        }

        // Handle OpJoin - potentially use SimJoin for semantic matching
        if (op instanceof OpJoin) {
            OpJoin opJoin = (OpJoin) op;

            boolean simEnabled = isSimJoinEnabled(execCxt);
            boolean needs = needsSimJoin(opJoin);
            LOG.debug("OpJoin: simJoinEnabled={}, needsSimJoin={}", simEnabled, needs);

            // Check if similarity join is needed (when GEN variables are involved)
            if (simEnabled && needs) {
                LOG.debug("Using similarity join for OpJoin");
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

            // Check if any element contains OpGenerate (for SimJoin) - with or without threshold
            boolean hasGenOp = false;
            boolean hasGenOpWithThreshold = false;
            // Check if any element contains OpGenerate with input variables (needs reordering)
            boolean hasGenOpWithInputVars = false;

            for (Op elem : elements) {
                if (containsGenOp(elem)) {
                    hasGenOp = true;
                }
                if (containsGenOpWithThreshold(elem)) {
                    hasGenOpWithThreshold = true;
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

            // Use SimJoin ONLY for base mode GENOP (no input variables)
            // Context mode GENOP needs standard sequence to receive bindings from previous elements
            if (isSimJoinEnabled(execCxt) && hasGenOp && !hasGenOpWithInputVars && elements.size() == 2) {
                if (hasGenOpWithThreshold) {
                    LOG.debug("Using SimJoin for OpSequence (base mode with explicit threshold)");
                } else {
                    LOG.debug("Using SimJoin for OpSequence (base mode with default threshold 0.8)");
                }
                // Treat as a join between the two elements (now in correct order)
                return executeSimJoinForSequence(elements.get(0), elements.get(1), input, execCxt);
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

            // Check if GEN types are involved - use SimScore-aware filter
            boolean hasGenVars = hasGenVariables();

            // Apply filters manually
            for (org.apache.jena.sparql.expr.Expr expr : opFilter.getExprs()) {
                if (hasGenVars && isEqualityExpr(expr)) {
                    // Use SimScore-aware filter for equality expressions with GEN types
                    SimText simText = getSimText(execCxt);
                    ThresholdRegistry registry = getThresholdRegistry(execCxt);
                    SimScoreEvaluator evaluator = new SimScoreEvaluator(simText, registry);
                    LOG.debug("Using SimScoreFilter for expr: {}", expr);
                    subIter = new QueryIterSimScoreFilter(subIter, expr, evaluator, sourceTypes, execCxt);
                } else {
                    subIter = new org.apache.jena.sparql.engine.iterator.QueryIterFilterExpr(
                            subIter, expr, execCxt);
                }
            }
            return subIter;
        }

        // Handle OpTable
        if (op instanceof OpTable) {
            OpTable opTable = (OpTable) op;
            if (opTable.isJoinIdentity()) {
                // Unit table - pass through input
                return input;
            }
            return opTable.getTable().iterator(execCxt);
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

        // Fallback to standard QC execution for other ops (BGP, etc.)
        LOG.debug("Falling back to QC.execute for: {}", op.getClass().getSimpleName());
        return QC.execute(op, input, execCxt);
    }

    /**
     * Get the execution plan for this engine.
     */
    public Plan getPlan() {
        return super.getPlan();
    }

    /**
     * Check if a join operation needs similarity-based matching.
     * Returns true ONLY for base mode GENOP (no input variables) that needs SimJoin.
     * Context mode GENOP (with input variables) should use standard join to pass bindings.
     */
    private boolean needsSimJoin(OpJoin opJoin) {
        // Only use SimJoin for base mode GENOP (no input variables)
        // Context mode GENOP needs standard join to receive bindings from left side
        boolean leftHasBaseGenOp = containsBaseGenOp(opJoin.getLeft());
        boolean rightHasBaseGenOp = containsBaseGenOp(opJoin.getRight());
        boolean leftHasContextGenOp = containsGenOpWithInputVars(opJoin.getLeft());
        boolean rightHasContextGenOp = containsGenOpWithInputVars(opJoin.getRight());

        // If there's a context mode GENOP, don't use SimJoin - use standard join
        if (leftHasContextGenOp || rightHasContextGenOp) {
            LOG.debug("needsSimJoin: false (has context mode GENOP)");
            return false;
        }

        // Use SimJoin only for base mode GENOP
        boolean result = leftHasBaseGenOp || rightHasBaseGenOp || hasGenVariables();
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
     * Check if the operation tree contains OpGenerate with explicit threshold
     */
    private boolean containsGenOpWithThreshold(Op op) {
        if (op instanceof OpGenerate) {
            return ((OpGenerate) op).hasThreshold();
        }

        // Check sub-operations recursively
        if (op instanceof OpJoin) {
            OpJoin opJoin = (OpJoin) op;
            return containsGenOpWithThreshold(opJoin.getLeft()) ||
                   containsGenOpWithThreshold(opJoin.getRight());
        }

        if (op instanceof OpProject) {
            return containsGenOpWithThreshold(((OpProject) op).getSubOp());
        }

        if (op instanceof OpFilter) {
            return containsGenOpWithThreshold(((OpFilter) op).getSubOp());
        }

        if (op instanceof OpSlice) {
            return containsGenOpWithThreshold(((OpSlice) op).getSubOp());
        }

        if (op instanceof OpDistinct) {
            return containsGenOpWithThreshold(((OpDistinct) op).getSubOp());
        }

        if (op instanceof OpReduced) {
            return containsGenOpWithThreshold(((OpReduced) op).getSubOp());
        }

        if (op instanceof OpOrder) {
            return containsGenOpWithThreshold(((OpOrder) op).getSubOp());
        }

        return false;
    }

    /**
     * Check if any variables are marked as GEN type.
     */
    private boolean hasGenVariables() {
        return sourceTypes.values().contains(SourceType.GEN);
    }

    /**
     * Check if an expression is an equality or non-equality expression.
     * These are the expressions that need SimScore semantics for GEN types.
     */
    private boolean isEqualityExpr(org.apache.jena.sparql.expr.Expr expr) {
        return expr instanceof org.apache.jena.sparql.expr.E_Equals ||
               expr instanceof org.apache.jena.sparql.expr.E_NotEquals;
    }

    /**
     * Execute a similarity-based join.
     *
     * For SimJoin, we execute both sides independently and then
     * perform a similarity-based cartesian product match using SimScore.
     */
    private QueryIterator executeSimJoin(OpJoin opJoin, QueryIterator input, ExecutionContext execCxt) {
        LOG.debug("executeSimJoin: Executing similarity-based join");

        // Get the initial binding from input (typically empty root binding)
        Binding initialBinding = input.hasNext() ? input.next() : org.apache.jena.sparql.engine.binding.BindingFactory.empty();

        // Execute left side (e.g., BGP) - these are RDF sourced
        // All variables from left side default to RDF type
        Map<Var, SourceType> leftTypes = new HashMap<>();
        QueryIterator leftIter = executeOp(opJoin.getLeft(), QueryIterRoot.create(initialBinding, execCxt), execCxt);

        // Execute right side (e.g., OpGenerate) independently with empty binding
        // For base mode GENOP, it doesn't need input bindings
        // Clear sourceTypes before executing right side to get fresh GEN types
        sourceTypes.clear();
        QueryIterator rightIter = executeOp(opJoin.getRight(),
                QueryIterRoot.create(org.apache.jena.sparql.engine.binding.BindingFactory.empty(), execCxt), execCxt);

        // rightTypes now contains GEN variables from OpGenerate execution
        Map<Var, SourceType> rightTypes = new HashMap<>(sourceTypes);

        LOG.debug("executeSimJoin: leftTypes={}, rightTypes={}", leftTypes, rightTypes);

        // Create SimScore evaluator
        SimText simText = getSimText(execCxt);
        ThresholdRegistry registry = getThresholdRegistry(execCxt);
        SimScoreEvaluator evaluator = new SimScoreEvaluator(simText, registry);

        return new QueryIterSimJoin(leftIter, rightIter, evaluator, leftTypes, rightTypes, execCxt);
    }

    /**
     * Execute a similarity-based join for OpSequence elements.
     *
     * Similar to executeSimJoin but takes two Op elements directly.
     */
    private QueryIterator executeSimJoinForSequence(Op leftOp, Op rightOp, QueryIterator input, ExecutionContext execCxt) {
        LOG.debug("executeSimJoinForSequence: left={}, right={}",
                  leftOp.getClass().getSimpleName(), rightOp.getClass().getSimpleName());

        // Get the initial binding from input (typically empty root binding)
        Binding initialBinding = input.hasNext() ? input.next() : org.apache.jena.sparql.engine.binding.BindingFactory.empty();

        // Execute left side (e.g., BGP) - these are RDF sourced
        // All variables from left side default to RDF type
        Map<Var, SourceType> leftTypes = new HashMap<>();
        QueryIterator leftIter = executeOp(leftOp, QueryIterRoot.create(initialBinding, execCxt), execCxt);

        // Execute right side (e.g., OpGenerate) independently with empty binding
        // For base mode GENOP, it doesn't need input bindings
        // Clear sourceTypes before executing right side to get fresh GEN types
        sourceTypes.clear();
        QueryIterator rightIter = executeOp(rightOp,
                QueryIterRoot.create(org.apache.jena.sparql.engine.binding.BindingFactory.empty(), execCxt), execCxt);

        // rightTypes now contains GEN variables from OpGenerate execution
        Map<Var, SourceType> rightTypes = new HashMap<>(sourceTypes);

        LOG.debug("executeSimJoinForSequence: leftTypes={}, rightTypes={}", leftTypes, rightTypes);

        // Create SimScore evaluator
        SimText simText = getSimText(execCxt);
        ThresholdRegistry registry = getThresholdRegistry(execCxt);
        SimScoreEvaluator evaluator = new SimScoreEvaluator(simText, registry);

        return new QueryIterSimJoin(leftIter, rightIter, evaluator, leftTypes, rightTypes, execCxt);
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
