package org.gensparql.function.similarity;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;
import org.gensparql.core.model.EmbedRequest;
import org.gensparql.core.model.EmbedResponse;
import org.gensparql.llm.LLMProvider;
import org.gensparql.llm.LLMProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * gen:embedding(?x) - Produces an embedding vector for text.
 *
 * Returns the embedding as a comma-separated string of floats.
 * This can be used for semantic filtering or ranking.
 */
public class EmbeddingFunction extends FunctionBase1 {
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingFunction.class);

    @Override
    public NodeValue exec(NodeValue v) {
        String text = nodeValueToString(v);

        try {
            LLMProvider provider = LLMProviderRegistry.getDefault();
            if (!provider.supportsEmbedding()) {
                LOG.warn("Default LLM provider does not support embeddings");
                return NodeValue.makeString("");
            }

            EmbedRequest request = new EmbedRequest(text, null);
            EmbedResponse response = provider.embedSync(request);

            if (response.isSuccess()) {
                float[] embedding = response.getFirstEmbedding();
                // Convert to string representation
                String embeddingStr = floatArrayToString(embedding);
                return NodeValue.makeString(embeddingStr);
            } else {
                LOG.warn("Embedding generation failed: {}", response.getErrorMessage());
                return NodeValue.makeString("");
            }
        } catch (Exception e) {
            LOG.error("Error generating embedding", e);
            return NodeValue.makeString("");
        }
    }

    private String nodeValueToString(NodeValue nv) {
        if (nv.isString()) {
            return nv.getString();
        } else if (nv.isLiteral()) {
            return nv.asNode().getLiteralLexicalForm();
        } else if (nv.isIRI()) {
            return nv.asNode().getURI();
        }
        return nv.toString();
    }

    private String floatArrayToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", arr[i]));
        }
        return sb.toString();
    }
}
