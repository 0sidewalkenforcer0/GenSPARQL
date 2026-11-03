package org.gensparql.engine.similarity;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.gensparql.core.model.TypedValue;
import org.gensparql.core.similarity.CanonicalForm;
import org.gensparql.core.similarity.SimText;
import org.gensparql.core.similarity.ThresholdRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

/**
 * Evaluates SimScore between two typed values.
 *
 * Implements: SimScore((t, τ), (t', τ'), v) → {⊤, ⊥}
 *
 * According to the GenSPARQL paper:
 * - If both RDF: require exact match (t = t')
 * - If both GEN: SimText(Lex(t), Lex(t')) >= θ(v)
 * - If mixed: SimText(Lex(t), Canon(t')) >= θ(v) or SimText(Canon(t), Lex(t')) >= θ(v)
 */
public class SimScoreEvaluator {
    private static final Logger LOG = LoggerFactory.getLogger(SimScoreEvaluator.class);

    private final SimText simText;
    private final ThresholdRegistry thresholdRegistry;
    private final Function<Node, String> labels;

    /**
     * Create a SimScoreEvaluator whose Canon(t) knows only the process-wide label table of
     * {@link CanonicalForm}, falling back to the IRI local name.
     *
     * @param simText           text similarity strategy
     * @param thresholdRegistry registry for variable-specific thresholds
     */
    public SimScoreEvaluator(SimText simText, ThresholdRegistry thresholdRegistry) {
        this(simText, thresholdRegistry, null);
    }

    /**
     * Create a SimScoreEvaluator whose Canon(t) reads the label of an IRI from {@code labels}
     * first, normally the graph the query runs over (see {@link GraphLabels}).
     *
     * @param simText           text similarity strategy
     * @param thresholdRegistry registry for variable-specific thresholds
     * @param labels            label of an IRI, or null if it has none; may itself be null
     */
    public SimScoreEvaluator(SimText simText, ThresholdRegistry thresholdRegistry,
                             Function<Node, String> labels) {
        this.simText = simText;
        this.thresholdRegistry = thresholdRegistry;
        this.labels = labels;
    }

    /**
     * Canon(t): the label of an IRI if it has one, otherwise what {@link CanonicalForm#canon}
     * gives (the process-wide label table, then the local name). Literals and blank nodes are
     * unaffected.
     *
     * @param node the RDF node
     * @return the string a generated value is compared with
     */
    public String canon(Node node) {
        if (labels != null && node != null && node.isURI()) {
            String label = labels.apply(node);
            if (label != null && !label.isEmpty()) {
                return label;
            }
        }
        return CanonicalForm.canon(node);
    }

    /**
     * Every string a generated value is compared with for this node: all labels of an IRI
     * that has labels in the graph, otherwise the one string of {@link #canon}.
     */
    public List<String> canonAll(Node node) {
        if (labels instanceof GraphLabels && node != null && node.isURI()) {
            List<String> all = ((GraphLabels) labels).all(node);
            if (!all.isEmpty()) {
                return all;
            }
        }
        return List.of(canon(node));
    }

    /** The highest similarity between a generated value and the strings of an RDF node. */
    private double bestSimilarity(Node rdfNode, String generated) {
        double best = 0.0;
        for (String s : canonAll(rdfNode)) {
            best = Math.max(best, simText.similarity(s, generated));
        }
        return best;
    }

    /**
     * Evaluate SimScore for a variable.
     *
     * @param tv1 first typed value (t, τ)
     * @param tv2 second typed value (t', τ')
     * @param var variable for threshold lookup θ(v)
     * @return true (⊤) if compatible, false (⊥) otherwise
     */
    public boolean evaluate(TypedValue tv1, TypedValue tv2, Var var) {
        // Both null = compatible (vacuous truth)
        if (tv1 == null && tv2 == null) {
            LOG.debug("SimScore var=?{} | Both null -> PASS", var.getName());
            return true;
        }

        // One null, one non-null = not compatible
        if (tv1 == null || tv2 == null) {
            LOG.debug("SimScore var=?{} | One null -> FAIL", var.getName());
            return false;
        }

        // Both have null nodes = compatible
        if (tv1.getNode() == null && tv2.getNode() == null) {
            LOG.debug("SimScore var=?{} | Both null nodes -> PASS", var.getName());
            return true;
        }

        // One null node, one non-null = not compatible
        if (tv1.getNode() == null || tv2.getNode() == null) {
            LOG.debug("SimScore var=?{} | One null node -> FAIL", var.getName());
            return false;
        }

        // Case 1: Both RDF - require exact match
        if (tv1.isRdf() && tv2.isRdf()) {
            boolean match = tv1.getNode().equals(tv2.getNode());
            LOG.debug("SimScore var=?{} | RDF×RDF | v1=\"{}\" vs v2=\"{}\" | exact={} -> {}",
                var.getName(), tv1.getLexicalForm(), tv2.getLexicalForm(), match, match ? "PASS" : "FAIL");
            return match;
        }

        double threshold = thresholdRegistry.getThreshold(var);

        // Case 2: Both GEN - use SimText on lexical forms
        if (tv1.isGen() && tv2.isGen()) {
            String lex1 = CanonicalForm.lex(tv1.getNode());
            String lex2 = CanonicalForm.lex(tv2.getNode());
            double similarity = simText.similarity(lex1, lex2);
            boolean pass = similarity >= threshold;
            LOG.debug("SimScore var=?{} | GEN×GEN | v1=\"{}\" vs v2=\"{}\" | sim={} threshold={} -> {}",
                var.getName(), lex1, lex2, String.format("%.4f", similarity), threshold, pass ? "PASS" : "FAIL");
            return pass;
        }

        // Case 3: Mixed types - the generated value against every label of the RDF node
        Node rdfNode = tv1.isRdf() ? tv1.getNode() : tv2.getNode();
        String generated = CanonicalForm.lex(tv1.isRdf() ? tv2.getNode() : tv1.getNode());
        double similarity = bestSimilarity(rdfNode, generated);
        boolean pass = similarity >= threshold;
        LOG.debug("SimScore var=?{} | RDF×GEN | labels={} vs gen=\"{}\" | sim={} threshold={} -> {}",
            var.getName(), canonAll(rdfNode), generated, String.format("%.4f", similarity), threshold, pass ? "PASS" : "FAIL");
        return pass;
    }

    /**
     * Compute the similarity score without applying threshold.
     *
     * @param tv1 first typed value
     * @param tv2 second typed value
     * @return similarity score in [0.0, 1.0]
     */
    public double computeSimilarity(TypedValue tv1, TypedValue tv2) {
        if (tv1 == null || tv2 == null ||
            tv1.getNode() == null || tv2.getNode() == null) {
            return 0.0;
        }

        // Both RDF - exact match or not
        if (tv1.isRdf() && tv2.isRdf()) {
            return tv1.getNode().equals(tv2.getNode()) ? 1.0 : 0.0;
        }

        // Both GEN - compare lexical forms
        if (tv1.isGen() && tv2.isGen()) {
            String lex1 = CanonicalForm.lex(tv1.getNode());
            String lex2 = CanonicalForm.lex(tv2.getNode());
            return simText.similarity(lex1, lex2);
        }

        // Mixed - the generated value against every label of the RDF node
        Node rdfNode = tv1.isRdf() ? tv1.getNode() : tv2.getNode();
        String generated = CanonicalForm.lex(tv1.isRdf() ? tv2.getNode() : tv1.getNode());
        return bestSimilarity(rdfNode, generated);
    }

    /**
     * Get the threshold for a variable.
     *
     * @param var the variable
     * @return threshold value
     */
    public double getThreshold(Var var) {
        return thresholdRegistry.getThreshold(var);
    }

    /**
     * The text similarity strategy in use.
     *
     * <p>Callers use this to tell which pruning is sound: the candidate index in the similarity
     * join carries a bound that belongs to Jaccard, so it must not be applied to another
     * strategy.
     *
     * @return sim text strategy
     */
    public SimText getSimText() {
        return simText;
    }

    /**
     * Get the threshold registry.
     *
     * @return threshold registry
     */
    public ThresholdRegistry getThresholdRegistry() {
        return thresholdRegistry;
    }
}
