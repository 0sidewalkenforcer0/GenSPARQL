package org.gensparql.llm.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON response parser for LLM outputs.
 *
 * Handles:
 * - JSON arrays for multiple results
 * - JSON objects for single results
 * - Markdown code block removal
 * - Multiple JSON blocks (uses the last valid one)
 */
public class JsonResponseParser implements ResponseParser {
    private static final Logger LOG = LoggerFactory.getLogger(JsonResponseParser.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // Pattern to match markdown code blocks: ```json ... ``` or ``` ... ```
    private static final Pattern MARKDOWN_CODE_BLOCK = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    @Override
    public List<Map<String, String>> parse(String content, List<String> outputVars) {
        List<Map<String, String>> bindings = new ArrayList<>();

        if (content == null || content.trim().isEmpty()) {
            LOG.debug("Empty content, returning empty bindings");
            return bindings;
        }

        if (outputVars == null || outputVars.isEmpty()) {
            bindings.add(Map.of("result", content.trim()));
            return bindings;
        }

        // Try to parse as JSON (works for both single and multiple variables)
        try {
            String jsonContent = extractJson(content);
            LOG.debug("Extracted JSON content: {}", truncate(jsonContent, 300));

            JsonNode json = objectMapper.readTree(jsonContent);

            // A syntactically valid JSON array or object is an authoritative, structured
            // response: honour its contents verbatim, including the empty case. An empty
            // array MUST yield zero bindings (the model saying "no matches") — it must NOT
            // fall through to the raw-text fallback, which would fabricate a single bogus
            // row containing the literal text "[]".
            if (json.isArray()) {
                LOG.debug("Parsing JSON array with {} elements", json.size());
                for (JsonNode item : json) {
                    Map<String, String> binding = parseJsonObject(item, outputVars);
                    if (!binding.isEmpty()) {
                        bindings.add(binding);
                    }
                }
                LOG.info("Parsed {} bindings from JSON array ({} elements)", bindings.size(), json.size());
                return bindings;
            } else if (json.isObject()) {
                LOG.debug("Parsing single JSON object");
                Map<String, String> binding = parseJsonObject(json, outputVars);
                if (!binding.isEmpty()) {
                    bindings.add(binding);
                }
                LOG.info("Parsed {} bindings from JSON object", bindings.size());
                return bindings;
            }
            // Otherwise the extracted content was a bare scalar / non-structured JSON:
            // fall through to the raw-text fallback below.
            LOG.debug("Extracted JSON was neither array nor object; using raw-text fallback");
        } catch (Exception e) {
            LOG.warn("Failed to parse JSON: {}", e.getMessage());
        }

        // Fallback: use raw text for first variable (only reached when the content was
        // not a valid JSON array/object).
        LOG.debug("Falling back to raw text for first variable");
        bindings.add(Map.of(outputVars.get(0), content.trim()));
        return bindings;
    }

    @Override
    public boolean canParse(String content) {
        if (content == null || content.trim().isEmpty()) {
            return false;
        }
        String extracted = extractJson(content);
        return extracted.startsWith("[") || extracted.startsWith("{");
    }

    /**
     * Attempt to repair common JSON syntax errors.
     * Handles missing commas between array elements and JavaScript-style comments.
     */
    private String repairJson(String json) {
        if (json == null || json.isEmpty()) return json;

        String original = json;

        // Remove JavaScript-style single-line comments: // ... (to end of line)
        // But be careful not to remove // inside strings
        json = removeJsComments(json);

        // Fix missing commas between objects in arrays: }{ -> },{
        // Pattern: } followed by optional whitespace/newlines followed by {
        json = json.replaceAll("\\}(\\s*)\\{", "},$1{");

        // Fix missing commas after strings followed by objects: "value"{ -> "value",{
        json = json.replaceAll("\"(\\s*)\\{", "\",$1{");

        // Fix missing commas between array elements: ]["  or ]{ patterns
        json = json.replaceAll("\\](\\s*)\\[", "],$1[");

        if (!json.equals(original)) {
            LOG.debug("Repaired JSON: fixed syntax issues");
        }

        return json;
    }

    /**
     * Remove JavaScript-style single-line comments from JSON.
     * Carefully handles comments that might appear inside strings.
     */
    private String removeJsComments(String json) {
        StringBuilder result = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        int i = 0;

        while (i < json.length()) {
            char c = json.charAt(i);

            if (escaped) {
                result.append(c);
                escaped = false;
                i++;
                continue;
            }

            if (c == '\\' && inString) {
                result.append(c);
                escaped = true;
                i++;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                result.append(c);
                i++;
                continue;
            }

            // Check for // comment start (only outside strings)
            if (!inString && c == '/' && i + 1 < json.length() && json.charAt(i + 1) == '/') {
                // Skip until end of line
                while (i < json.length() && json.charAt(i) != '\n') {
                    i++;
                }
                // Don't skip the newline itself
                continue;
            }

            result.append(c);
            i++;
        }

        return result.toString();
    }

    /**
     * Extract JSON from content that might be wrapped in markdown code blocks.
     * Handles multiple code blocks by extracting and combining their contents.
     */
    private String extractJson(String content) {
        if (content == null) return "";

        String text = content.trim();

        // Step 1: Extract content from all markdown code blocks
        Matcher matcher = MARKDOWN_CODE_BLOCK.matcher(text);
        StringBuilder extracted = new StringBuilder();
        boolean foundCodeBlocks = false;

        while (matcher.find()) {
            foundCodeBlocks = true;
            String blockContent = matcher.group(1).trim();
            if (!blockContent.isEmpty()) {
                extracted.append(blockContent).append("\n");
            }
        }

        if (foundCodeBlocks && extracted.length() > 0) {
            text = extracted.toString().trim();
        }

        // Step 2: Find all JSON structures and return the last valid JSON array
        List<String> jsonCandidates = findAllJsonStructures(text);

        // Prefer the last valid JSON array (LLM might correct itself)
        for (int i = jsonCandidates.size() - 1; i >= 0; i--) {
            String candidate = jsonCandidates.get(i);
            // Try original first
            if (isValidJson(candidate)) {
                LOG.debug("Using JSON candidate #{}: {}", i + 1, truncate(candidate, 100));
                return candidate;
            }
            // Try repaired version
            String repaired = repairJson(candidate);
            if (isValidJson(repaired)) {
                LOG.debug("Using repaired JSON candidate #{}: {}", i + 1, truncate(repaired, 100));
                return repaired;
            }
        }

        // Step 3: Try to extract first JSON structure directly
        String firstJson = extractFirstJsonStructure(text);

        // Try repairing it as well
        if (!isValidJson(firstJson)) {
            String repaired = repairJson(firstJson);
            if (isValidJson(repaired)) {
                LOG.debug("Using repaired first JSON structure");
                return repaired;
            }
        }

        return firstJson;
    }

    /**
     * Find all potential JSON structures (arrays or objects) in the text.
     */
    private List<String> findAllJsonStructures(String text) {
        List<String> structures = new ArrayList<>();
        int pos = 0;

        while (pos < text.length()) {
            int arrayStart = text.indexOf('[', pos);
            int objectStart = text.indexOf('{', pos);

            if (arrayStart < 0 && objectStart < 0) {
                break;
            }

            int start;
            char openChar, closeChar;

            if (arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)) {
                start = arrayStart;
                openChar = '[';
                closeChar = ']';
            } else {
                start = objectStart;
                openChar = '{';
                closeChar = '}';
            }

            String structure = extractBalancedStructure(text, start, openChar, closeChar);
            if (structure != null) {
                structures.add(structure);
                pos = start + structure.length();
            } else {
                pos = start + 1;
            }
        }

        return structures;
    }

    /**
     * Extract a balanced JSON structure starting at the given position.
     */
    private String extractBalancedStructure(String text, int start, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (c == openChar) {
                depth++;
            } else if (c == closeChar) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }

        return null;
    }

    /**
     * Extract the first JSON structure from text.
     */
    private String extractFirstJsonStructure(String text) {
        int arrayStart = text.indexOf('[');
        int objectStart = text.indexOf('{');

        if (arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)) {
            String result = extractBalancedStructure(text, arrayStart, '[', ']');
            return result != null ? result : text;
        } else if (objectStart >= 0) {
            String result = extractBalancedStructure(text, objectStart, '{', '}');
            return result != null ? result : text;
        }

        return text;
    }

    /**
     * Check if a string is valid JSON.
     */
    private boolean isValidJson(String json) {
        try {
            objectMapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Parse a JSON object into a variable binding map.
     * Handles various field naming conventions:
     * - Exact match with variable name
     * - Common field names like "entity", "value", "result"
     * - First field if only one field exists
     */
    private Map<String, String> parseJsonObject(JsonNode json, List<String> outputVars) {
        Map<String, String> binding = new HashMap<>();

        for (String var : outputVars) {
            String value = null;

            // Try exact match first (always).
            if (json.has(var)) {
                value = nodeToString(json.get(var));
            }
            // Fuzzy field-name / shape fallbacks only make sense for a SINGLE output
            // variable; with multiple vars a missing field must stay unbound (null),
            // otherwise one field's value leaks into another var.
            else if (outputVars.size() == 1) {
                if (json.has("entity")) {
                    value = nodeToString(json.get("entity"));
                }
                else if (json.has("value")) {
                    value = nodeToString(json.get("value"));
                }
                else if (json.has("result")) {
                    value = nodeToString(json.get("result"));
                }
                else if (json.has("name")) {
                    value = nodeToString(json.get("name"));
                }
                // If object has only one field, use it
                else if (json.isObject() && json.size() == 1) {
                    Iterator<JsonNode> it = json.elements();
                    if (it.hasNext()) {
                        value = nodeToString(it.next());
                    }
                }
                // If it's a simple value (string, number), use it directly
                else if (json.isValueNode()) {
                    value = nodeToString(json);
                }
            }

            // Keep any value that matched a field, including an explicit empty/null
            // (a present-but-null field binds to ""); a missing field stays unbound.
            if (value != null) {
                binding.put(var, value);
            }
        }

        return binding;
    }

    /**
     * Convert a JSON node to a string value.
     */
    private String nodeToString(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isNumber()) {
            return node.asText();
        } else if (node.isBoolean()) {
            return String.valueOf(node.asBoolean());
        } else if (node.isNull()) {
            return "";
        } else {
            return node.toString();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
