package org.gensparql.engine.similarity;

import org.apache.jena.sparql.core.Var;
import org.gensparql.core.model.TypedValue;
import org.gensparql.core.similarity.CanonicalForm;
import org.gensparql.core.similarity.SimText;
import org.gensparql.core.similarity.ThresholdRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * Create a SimScoreEvaluator.
     *
     * @param simText           text similarity strategy
     * @param thresholdRegistry registry for variable-specific thresholds
     */
    public SimScoreEvaluator(SimText simText, ThresholdRegistry thresholdRegistry) {
        this.simText = simText;
        this.thresholdRegistry = thresholdRegistry;
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

        // Case 3: Mixed types - use Canon for RDF, Lex for GEN
        String s1, s2;
        String type1, type2;
        if (tv1.isRdf()) {
            s1 = CanonicalForm.canon(tv1.getNode());
            s2 = CanonicalForm.lex(tv2.getNode());
            type1 = "RDF(canon)";
            type2 = "GEN(lex)";
        } else {
            s1 = CanonicalForm.lex(tv1.getNode());
            s2 = CanonicalForm.canon(tv2.getNode());
            type1 = "GEN(lex)";
            type2 = "RDF(canon)";
        }

        double similarity = simText.similarity(s1, s2);
        boolean pass = similarity >= threshold;
        LOG.debug("SimScore var=?{} | {}×{} | v1=\"{}\" vs v2=\"{}\" | sim={} threshold={} -> {}",
            var.getName(), type1, type2, s1, s2, String.format("%.4f", similarity), threshold, pass ? "PASS" : "FAIL");
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

        // Mixed - use Canon for RDF, Lex for GEN
        String s1, s2;
        if (tv1.isRdf()) {
            s1 = CanonicalForm.canon(tv1.getNode());
            s2 = CanonicalForm.lex(tv2.getNode());
        } else {
            s1 = CanonicalForm.lex(tv1.getNode());
            s2 = CanonicalForm.canon(tv2.getNode());
        }

        return simText.similarity(s1, s2);
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
     * Get the SimText strategy.
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
