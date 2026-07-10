package org.gensparql.function.nlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * gen:parseJSON(?raw, ?field) - Extracts a field from JSON text.
 *
 * Useful for robust parsing of structured LLM output.
 *
 * Example:
 * BIND(gen:parseJSON(?response, "name") AS ?name)
 * BIND(gen:parseJSON(?response, "results[0].id") AS ?firstId)
 */
public class ParseJSONFunction extends FunctionBase2 {
    private static final Logger LOG = LoggerFactory.getLogger(ParseJSONFunction.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public NodeValue exec(NodeValue rawNV, NodeValue fieldNV) {
        String raw = nodeValueToString(rawNV);
        String field = nodeValueToString(fieldNV);

        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode value = navigateTo(root, field);

            if (value != null && !value.isNull()) {
                if (value.isTextual()) {
                    return NodeValue.makeString(value.asText());
                } else if (value.isNumber()) {
                    if (value.isIntegralNumber()) {
                        return NodeValue.makeInteger(value.asLong());
                    } else {
                        return NodeValue.makeDouble(value.asDouble());
                    }
                } else if (value.isBoolean()) {
                    return NodeValue.makeBoolean(value.asBoolean());
                } else {
                    // For objects/arrays, return as string
                    return NodeValue.makeString(value.toString());
                }
            }
        } catch (Exception e) {
            LOG.debug("JSON parsing failed for field '{}': {}", field, e.getMessage());
        }

        return NodeValue.makeString("");
    }

    /**
     * Navigate to a field using dot/bracket notation.
     * Supports: "field", "nested.field", "array[0]", "nested.array[0].field"
     */
    private JsonNode navigateTo(JsonNode root, String path) {
        if (path == null || path.isEmpty()) {
            return root;
        }

        JsonNode current = root;
        String[] parts = path.split("\\.");

        for (String part : parts) {
            if (current == null || current.isNull()) {
                return null;
            }

            // Check for array index notation: field[0]
            if (part.contains("[")) {
                int bracketStart = part.indexOf('[');
                int bracketEnd = part.indexOf(']');

                String fieldName = part.substring(0, bracketStart);
                int index = Integer.parseInt(part.substring(bracketStart + 1, bracketEnd));

                if (!fieldName.isEmpty()) {
                    current = current.get(fieldName);
                }

                if (current != null && current.isArray() && index < current.size()) {
                    current = current.get(index);
                } else {
                    return null;
                }
            } else {
                current = current.get(part);
            }
        }

        return current;
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
