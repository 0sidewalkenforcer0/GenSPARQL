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
 * gen:entail(?premise, ?hypothesis) - Performs textual entailment.
 *
 * Checks if the premise entails the hypothesis.
 * Returns true if entailment holds, false otherwise.
 *
 * Example:
 * FILTER(gen:entail(?paragraph, "The product is recommended"))
 */
public class EntailFunction extends FunctionBase2 {
    private static final Logger LOG = LoggerFactory.getLogger(EntailFunction.class);

    @Override
    public NodeValue exec(NodeValue premiseNV, NodeValue hypothesisNV) {
        String premise = nodeValueToString(premiseNV);
        String hypothesis = nodeValueToString(hypothesisNV);

        try {
            LLMProvider provider = LLMProviderRegistry.getDefault();

            String prompt = buildEntailmentPrompt(premise, hypothesis);

            GenerateRequest request = GenerateRequest.builder()
                    .prompt(prompt)
                    .modelSpec(ModelSpec.builder()
                            .provider(provider.getName())
                            .model(provider.getDefaultModel())
                            .temperature(0.0)
                            .build())
                    .outputVariables(List.of("entails"))
                    .build();

            GenerateResponse response = provider.generateSync(request);

            if (response.isSuccess() && response.getRawText() != null) {
                String result = response.getRawText().trim().toLowerCase();
                boolean entails = result.contains("entailment") ||
                                 result.contains("yes") ||
                                 result.contains("true");
                return NodeValue.makeBoolean(entails);
            }
        } catch (Exception e) {
            LOG.error("Entailment check failed", e);
        }

        return NodeValue.makeBoolean(false);
    }

    private String buildEntailmentPrompt(String premise, String hypothesis) {
        return "Given the premise, determine if the hypothesis is entailed (logically follows).\n\n" +
               "Premise: " + premise + "\n" +
               "Hypothesis: " + hypothesis + "\n\n" +
               "Answer with one of: 'entailment', 'contradiction', or 'neutral'.";
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
