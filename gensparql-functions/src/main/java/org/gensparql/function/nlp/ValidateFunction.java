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
 * gen:validate(?value, ?schema) - Checks whether a value conforms to a schema.
 *
 * ?schema describes the expected format or constraints.
 * Returns true if valid, false otherwise.
 *
 * Example:
 * FILTER(gen:validate(?email, "valid email address"))
 * FILTER(gen:validate(?date, "ISO 8601 date format"))
 */
public class ValidateFunction extends FunctionBase2 {
    private static final Logger LOG = LoggerFactory.getLogger(ValidateFunction.class);

    @Override
    public NodeValue exec(NodeValue valueNV, NodeValue schemaNV) {
        String value = nodeValueToString(valueNV);
        String schema = nodeValueToString(schemaNV);

        try {
            LLMProvider provider = LLMProviderRegistry.getDefault();

            String prompt = buildValidationPrompt(value, schema);

            GenerateRequest request = GenerateRequest.builder()
                    .prompt(prompt)
                    .modelSpec(ModelSpec.builder()
                            .provider(provider.getName())
                            .model(provider.getDefaultModel())
                            .temperature(0.0)
                            .build())
                    .outputVariables(List.of("valid"))
                    .build();

            GenerateResponse response = provider.generateSync(request);

            if (response.isSuccess() && response.getRawText() != null) {
                String result = response.getRawText().trim().toLowerCase();
                boolean isValid = result.contains("true") ||
                                 result.contains("yes") ||
                                 result.contains("valid");
                return NodeValue.makeBoolean(isValid);
            }
        } catch (Exception e) {
            LOG.error("Validation failed", e);
        }

        return NodeValue.makeBoolean(false);
    }

    private String buildValidationPrompt(String value, String schema) {
        return "Check if the following value conforms to the given schema/constraint.\n\n" +
               "Value: " + value + "\n" +
               "Schema/Constraint: " + schema + "\n\n" +
               "Respond with only 'true' or 'false'.";
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
