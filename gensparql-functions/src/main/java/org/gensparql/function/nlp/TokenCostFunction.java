package org.gensparql.function.nlp;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gen:tokenCost(?prompt) - Estimates token usage for a prompt.
 *
 * Useful for cost-based query planning.
 * Returns estimated token count (approximate, based on word count heuristic).
 *
 * Example:
 * BIND(gen:tokenCost(?myPrompt) AS ?estimatedTokens)
 * FILTER(gen:tokenCost(?prompt) < 1000)
 */
public class TokenCostFunction extends FunctionBase1 {
    private static final Logger LOG = LoggerFactory.getLogger(TokenCostFunction.class);

    // Average ratio of tokens to characters for English text
    // Empirically, ~4 characters per token for GPT models
    private static final double CHARS_PER_TOKEN = 4.0;

    // Alternative: tokens per word (average ~1.3 tokens per word)
    private static final double TOKENS_PER_WORD = 1.3;

    @Override
    public NodeValue exec(NodeValue promptNV) {
        String prompt = nodeValueToString(promptNV);

        if (prompt == null || prompt.isEmpty()) {
            return NodeValue.makeInteger(0);
        }

        // Estimate using both methods and average
        int charBasedEstimate = (int) Math.ceil(prompt.length() / CHARS_PER_TOKEN);

        String[] words = prompt.split("\\s+");
        int wordBasedEstimate = (int) Math.ceil(words.length * TOKENS_PER_WORD);

        // Use the average of both estimates
        int estimate = (charBasedEstimate + wordBasedEstimate) / 2;

        LOG.debug("Token estimate for {} chars / {} words: {}", prompt.length(), words.length, estimate);

        return NodeValue.makeInteger(estimate);
    }

    private String nodeValueToString(NodeValue nv) {
        if (nv.isString()) {
            return nv.getString();
        } else if (nv.isLiteral()) {
            return nv.asNode().getLiteralLexicalForm();
        }
        return nv.toString();
    }
}
