package org.gensparql.engine.iterator;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.iterator.QueryIteratorBase;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.gensparql.core.model.SourceType;
import org.gensparql.core.model.TypedBinding;
import org.gensparql.core.model.TypedValue;
import org.gensparql.engine.similarity.SimScoreEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Similarity-based join iterator for GenSPARQL.
 *
 * Uses SimScore instead of exact equality for binding compatibility.
 * Implements the COM (compatible) predicate from the GenSPARQL paper:
 *
 * COM(μ₁, μ₂) ⟺ ∀v ∈ dom(μ₁) ∩ dom(μ₂): SimScore(μ₁(v), μ₂(v), v) = ⊤
 *
 * This allows LLM-generated values to be joined with RDF values
 * based on semantic similarity rather than exact string matching.
 */
public class QueryIterSimJoin extends QueryIteratorBase {
    private static final Logger LOG = LoggerFactory.getLogger(QueryIterSimJoin.class);

    private final List<TypedBinding> leftBindings;
    private final List<TypedBinding> rightBindings;
    private final SimScoreEvaluator simScoreEvaluator;
    private final ExecutionContext execCxt;
    private final Map<Var, SourceType> leftSourceTypes;
    private final Map<Var, SourceType> rightSourceTypes;

    private Iterator<TypedBinding> leftIter;
    private TypedBinding currentLeft;
    private Iterator<TypedBinding> matchIter;
    private boolean exhausted = false;

    /**
     * Create a similarity join iterator.
     *
     * @param left            left side iterator
     * @param right           right side iterator
     * @param evaluator       SimScore evaluator
     * @param leftSourceTypes source types for left side variables
     * @param rightSourceTypes source types for right side variables
     * @param execCxt         execution context
     */
    public QueryIterSimJoin(QueryIterator left, QueryIterator right,
                            SimScoreEvaluator evaluator,
                            Map<Var, SourceType> leftSourceTypes,
                            Map<Var, SourceType> rightSourceTypes,
                            ExecutionContext execCxt) {
        this.simScoreEvaluator = evaluator;
        this.execCxt = execCxt;
        this.leftSourceTypes = leftSourceTypes != null ? leftSourceTypes : Collections.emptyMap();
        this.rightSourceTypes = rightSourceTypes != null ? rightSourceTypes : Collections.emptyMap();

        // Materialize both sides (needed for N×M comparison)
        this.leftBindings = materialize(left, this.leftSourceTypes);
        this.rightBindings = materialize(right, this.rightSourceTypes);
        this.leftIter = leftBindings.iterator();

        // Debug output
        if (LOG.isDebugEnabled()) {
            LOG.debug("SimJoin Initialization: {} left bindings, {} right bindings",
                    leftBindings.size(), rightBindings.size());
            for (TypedBinding tb : leftBindings) {
                LOG.debug("  Left: {}", tb);
            }
            for (TypedBinding tb : rightBindings) {
                LOG.debug("  Right: {}", tb);
            }
            LOG.debug("Left source types: {}, Right source types: {}",
                    this.leftSourceTypes, this.rightSourceTypes);
        }
    }

    /**
     * Simplified constructor that infers source types.
     */
    public QueryIterSimJoin(QueryIterator left, QueryIterator right,
                            SimScoreEvaluator evaluator,
                            ExecutionContext execCxt) {
        this(left, right, evaluator, null, null, execCxt);
    }

    /**
     * Materialize an iterator into a list of TypedBindings.
     */
    private List<TypedBinding> materialize(QueryIterator iter, Map<Var, SourceType> sourceTypes) {
        List<TypedBinding> list = new ArrayList<>();
        while (iter.hasNext()) {
            Binding b = iter.next();
            TypedBinding tb = toTypedBinding(b, sourceTypes);
            list.add(tb);
        }
        iter.close();
        return list;
    }

    /**
     * Convert a Binding to a TypedBinding with source type information.
     */
    private TypedBinding toTypedBinding(Binding binding, Map<Var, SourceType> knownTypes) {
        TypedBinding.Builder builder = TypedBinding.builder();
        binding.vars().forEachRemaining(var -> {
            Node node = binding.get(var);
            SourceType type = knownTypes.getOrDefault(var, SourceType.RDF);
            builder.add(var, node, type);
        });
        return builder.build();
    }

    @Override
    protected boolean hasNextBinding() {
        if (exhausted) {
            return false;
        }

        while (true) {
            // Check if we have more matches for current left binding
            if (matchIter != null && matchIter.hasNext()) {
                return true;
            }

            // Move to next left binding
            if (!leftIter.hasNext()) {
                exhausted = true;
                return false;
            }

            currentLeft = leftIter.next();
            matchIter = findCompatibleBindings(currentLeft).iterator();
        }
    }

    @Override
    protected Binding moveToNextBinding() {
        TypedBinding rightBinding = matchIter.next();
        TypedBinding merged = TypedBinding.merge(currentLeft, rightBinding);
        return merged.getBaseBinding();
    }

    /**
     * Find all right bindings compatible with left binding using SimScore.
     * Implements the COM predicate check.
     */
    private List<TypedBinding> findCompatibleBindings(TypedBinding left) {
        List<TypedBinding> compatible = new ArrayList<>();

        for (TypedBinding right : rightBindings) {
            if (areCompatible(left, right)) {
                compatible.add(right);
            }
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("Found {} compatible bindings for left: {}", compatible.size(), left);
        }

        return compatible;
    }

    /**
     * Check COM(μ₁, μ₂): compatibility using SimScore.
     *
     * Two bindings are compatible if for all shared variables,
     * SimScore evaluates to true.
     */
    private boolean areCompatible(TypedBinding b1, TypedBinding b2) {
        // Find shared variables
        Set<Var> shared = new HashSet<>();
        Set<Var> b1Vars = b1.varSet();
        Set<Var> b2Vars = b2.varSet();

        for (Var v : b1Vars) {
            if (b2Vars.contains(v)) {
                shared.add(v);
            }
        }

        // If no shared variables, they are compatible (can be merged)
        if (shared.isEmpty()) {
            return true;
        }

        // Check SimScore for each shared variable
        for (Var var : shared) {
            TypedValue tv1 = b1.getTypedValue(var);
            TypedValue tv2 = b2.getTypedValue(var);

            if (!simScoreEvaluator.evaluate(tv1, tv2, var)) {
                if (LOG.isTraceEnabled()) {
                    LOG.trace("Bindings not compatible at var ?{}: {} vs {}",
                            var.getName(),
                            tv1 != null ? tv1.getLexicalForm() : "null",
                            tv2 != null ? tv2.getLexicalForm() : "null");
                }
                return false;  // Not compatible
            }
        }

        return true;  // All shared variables compatible
    }

    @Override
    protected void closeIterator() {
        // No resources to close beyond what was materialized
    }

    @Override
    protected void requestCancel() {
        // Cancel by exhausting
        exhausted = true;
    }

    @Override
    public void output(IndentedWriter out, SerializationContext sCxt) {
        out.print("QueryIterSimJoin(");
        out.print("left=" + leftBindings.size());
        out.print(", right=" + rightBindings.size());
        out.print(")");
    }

    /**
     * Get statistics about this join operation.
     */
    public JoinStats getStats() {
        return new JoinStats(leftBindings.size(), rightBindings.size());
    }

    /**
     * Statistics about the join operation.
     */
    public static class JoinStats {
        private final int leftCount;
        private final int rightCount;

        public JoinStats(int leftCount, int rightCount) {
            this.leftCount = leftCount;
            this.rightCount = rightCount;
        }

        public int getLeftCount() {
            return leftCount;
        }

        public int getRightCount() {
            return rightCount;
        }

        public long getMaxComparisons() {
            return (long) leftCount * rightCount;
        }

        @Override
        public String toString() {
            return String.format("JoinStats{left=%d, right=%d, maxComparisons=%d}",
                    leftCount, rightCount, getMaxComparisons());
        }
    }
}
