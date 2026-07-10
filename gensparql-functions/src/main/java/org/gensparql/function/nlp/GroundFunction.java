package org.gensparql.function.nlp;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;
import org.gensparql.core.model.GenerateRequest;
import org.gensparql.core.model.GenerateResponse;
import org.gensparql.core.model.ModelSpec;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * gen:ground(?text) - Maps a natural-language mention to a KG entity (IRI).
 *
 * Performs entity linking/grounding using LLM.
 * Returns an IRI string or empty string if grounding fails.
 *
 * Example:
 * BIND(gen:ground("the capital of France") AS ?entityURI)
 */
public class GroundFunction extends FunctionBase1 {
    private static final Logger LOG = LoggerFactory.getLogger(GroundFunction.class);

    // Common knowledge base prefixes
    private static final String WIKIDATA_PREFIX = "http://www.wikidata.org/entity/";
    private static final String DBPEDIA_PREFIX = "http://dbpedia.org/resource/";

    @Override
    public NodeValue exec(NodeValue textValue) {
        String text = nodeValueToString(textValue);

        try {
            LLMProvider provider = LLMProviderRegistry.getDefault();

            String prompt = buildGroundingPrompt(text);

            GenerateRequest request = GenerateRequest.builder()
                    .prompt(prompt)
                    .modelSpec(ModelSpec.builder()
                            .provider(provider.getName())
                            .model(provider.getDefaultModel())
                            .temperature(0.0)
                            .build())
                    .outputVariables(List.of("entity"))
                    .build();

            GenerateResponse response = provider.generateSync(request);

            if (response.isSuccess() && response.getRawText() != null) {
                String result = response.getRawText().trim();
                // Try to construct a valid URI
                return NodeValue.makeString(toEntityURI(result));
            }
        } catch (Exception e) {
            LOG.error("Entity grounding failed", e);
        }

        return NodeValue.makeString("");
    }

    private String buildGroundingPrompt(String text) {
        return "Identify the entity mentioned in the following text and provide its Wikidata Q identifier " +
               "(e.g., Q90 for Paris) or DBpedia resource name.\n\n" +
               "Text: " + text + "\n\n" +
               "Respond with only the entity identifier, nothing else. " +
               "If no clear entity can be identified, respond with 'NONE'.";
    }

    private String toEntityURI(String result) {
        if (result == null || result.isEmpty() || "NONE".equalsIgnoreCase(result)) {
            return "";
        }

        result = result.trim();

        // Already a URI
        if (result.startsWith("http://") || result.startsWith("https://")) {
            return result;
        }

        // Wikidata Q identifier
        if (result.matches("Q\\d+")) {
            return WIKIDATA_PREFIX + result;
        }

        // DBpedia-style name (convert spaces to underscores)
        String dbpediaName = result.replace(" ", "_");
        return DBPEDIA_PREFIX + dbpediaName;
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
