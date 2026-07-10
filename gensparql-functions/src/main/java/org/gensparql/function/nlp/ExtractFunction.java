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
 * gen:extract(?text, ?schema) - Extracts structured fields from text.
 *
 * ?schema defines the fields to extract (e.g., "name,date,location").
 * Returns JSON string with extracted values.
 *
 * Example:
 * BIND(gen:extract(?paragraph, "person,date,event") AS ?extracted)
 */
public class ExtractFunction extends FunctionBase2 {
    private static final Logger LOG = LoggerFactory.getLogger(ExtractFunction.class);

    @Override
    public NodeValue exec(NodeValue textValue, NodeValue schemaValue) {
        String text = nodeValueToString(textValue);
        String schema = nodeValueToString(schemaValue);

        String[] fields = schema.split(",");
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].trim();
        }

        try {
            LLMProvider provider = LLMProviderRegistry.getDefault();

            String prompt = buildExtractionPrompt(text, fields);

            GenerateRequest request = GenerateRequest.builder()
                    .prompt(prompt)
                    .modelSpec(ModelSpec.builder()
                            .provider(provider.getName())
                            .model(provider.getDefaultModel())
                            .temperature(0.0)
                            .build())
                    .outputVariables(List.of(fields))
                    .outputFormat(GenerateRequest.OutputFormat.JSON)
                    .build();

            GenerateResponse response = provider.generateSync(request);

            if (response.isSuccess() && response.getRawText() != null) {
                // Return raw JSON response
                return NodeValue.makeString(response.getRawText().trim());
            }
        } catch (Exception e) {
            LOG.error("Extraction failed", e);
        }

        return NodeValue.makeString("{}");
    }

    private String buildExtractionPrompt(String text, String[] fields) {
        StringBuilder sb = new StringBuilder();
        sb.append("Extract the following information from the text:\n");
        for (String field : fields) {
            sb.append("- ").append(field).append("\n");
        }
        sb.append("\nText: ").append(text);
        sb.append("\n\nRespond with a JSON object containing only these fields. ");
        sb.append("Use null for any field not found in the text.");
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
