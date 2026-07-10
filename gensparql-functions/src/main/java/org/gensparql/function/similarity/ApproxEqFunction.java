package org.gensparql.function.similarity;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase3;

/**
 * gen:approxEq(?x, ?y, ?threshold) - Approximate equality test.
 *
 * Returns true if similarity(?x, ?y) >= threshold.
 */
public class ApproxEqFunction extends FunctionBase3 {

    private final SimilarityFunction similarityFunction = new SimilarityFunction();

    @Override
    public NodeValue exec(NodeValue v1, NodeValue v2, NodeValue threshold) {
        double thresholdValue = threshold.getDouble();

        // Compute similarity
        NodeValue simResult = similarityFunction.exec(v1, v2);
        double similarity = simResult.getDouble();

        return NodeValue.makeBoolean(similarity >= thresholdValue);
    }
}
