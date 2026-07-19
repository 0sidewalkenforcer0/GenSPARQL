package org.gensparql.function.nlp;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;
import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * gen:classify(?text, ?labels) - Classifies text into one of the provided labels.
 *
 * ?labels should be a comma-separated string of label options.
 *
 * Example:
 * FILTER(gen:classify(?description, "positive,negative,neutral") = "positive")
 */
public class ClassifyFunction extends FunctionBase2 {
    private static final Logger LOG = LoggerFactory.getLogger(ClassifyFunction.class);

    @Override
    public NodeValue exec(NodeValue textValue, NodeValue labelsValue) {
        String text = nodeValueToString(textValue);
        String labelsStr = nodeValueToString(labelsValue);

        String[] labels = labelsStr.split(",");
        for (int i = 0; i < labels.length; i++) {
            labels[i] = labels[i].trim();
        }

        try {
            LLMProvider provider = LLMProviderRegistry.getDefault();

            // Build classification prompt
            String prompt = buildClassificationPrompt(text, labels);

            GenerateRequest request = GenerateRequest.builder()
                    .prompt(prompt)
                    .modelSpec(ModelSpec.builder()
                            .provider(provider.getName())
                            .model(provider.getDefaultModel())
                            .temperature(0.0)  // Deterministic
                            .build())
                    .outputVariables(List.of("label"))
                    .build();

            GenerateResponse response = provider.generateSync(request);

            if (response.isSuccess() && response.getRawText() != null) {
                String result = response.getRawText().trim().toLowerCase();
                String matched = matchLabel(result, labels);
                if (matched != null) {
                    return NodeValue.makeString(matched);
                }
                // Return the raw result if no label matched
                return NodeValue.makeString(result);
            }
        } catch (Exception e) {
            LOG.error("Classification failed", e);
        }

        return NodeValue.makeString("");
    }

    /**
     * Pick the label the response refers to. Matching is order-INSENSITIVE and prefers the
     * most specific label so overlapping labels (e.g. "cat" vs "category") are disambiguated:
     * <ol>
     *   <li>exact equality with the trimmed response;</li>
     *   <li>otherwise the longest label that appears as a whole word;</li>
     *   <li>otherwise the longest label that appears as a substring.</li>
     * </ol>
     * Returns the original (untrimmed-case) label, or {@code null} if none match.
     */
    private static String matchLabel(String result, String[] labels) {
        String exact = null, bestWord = null, bestSub = null;
        for (String label : labels) {
            String lc = label.toLowerCase();
            if (lc.isEmpty()) continue;
            if (result.equals(lc)) {
                exact = label;
            }
            if (containsWord(result, lc) && (bestWord == null || lc.length() > bestWord.length())) {
                bestWord = label;
            }
            if (result.contains(lc) && (bestSub == null || lc.length() > bestSub.length())) {
                bestSub = label;
            }
        }
        if (exact != null) return exact;
        if (bestWord != null) return bestWord;
        return bestSub;
    }

    private static boolean containsWord(String haystack, String word) {
        return java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(word) + "\\b")
                .matcher(haystack).find();
    }

    private String buildClassificationPrompt(String text, String[] labels) {
        StringBuilder sb = new StringBuilder();
        sb.append("Classify the following text into exactly one of these categories: ");
        sb.append(String.join(", ", labels));
        sb.append("\n\nText: ").append(text);
        sb.append("\n\nRespond with only the category name, nothing else.");
        return sb.toString();
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
