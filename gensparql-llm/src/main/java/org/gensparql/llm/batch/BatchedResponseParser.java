package org.gensparql.llm.batch;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;

/**
 * Parser for batched LLM responses.
 * Extracts individual results from a batched JSON array response.
 */
public class BatchedResponseParser {

    private static final Gson gson = new Gson();

    /**
     * Parse a batched response into individual result maps.
     *
     * @param response the raw response text
     * @param expectedCount expected number of results
     * @return list of result maps, one per prompt (indexed by prompt_id - 1)
     * @throws BatchParsingException if parsing fails
     */
    public static List<Map<String, String>> parse(String response, int expectedCount)
            throws BatchParsingException {

        if (response == null || response.trim().isEmpty()) {
            throw new BatchParsingException("Response is null or empty");
        }

        // Extract JSON array from response (handle markdown code blocks)
        String jsonText = extractJsonArray(response);

        try {
            JsonArray jsonArray = JsonParser.parseString(jsonText).getAsJsonArray();

            // Initialize result list with empty maps
            List<Map<String, String>> results = new ArrayList<>(expectedCount);
            for (int i = 0; i < expectedCount; i++) {
                results.add(new HashMap<>());
            }

            // Parse each result object
            for (JsonElement element : jsonArray) {
                if (!element.isJsonObject()) {
                    continue; // Skip non-object elements
                }

                JsonObject obj = element.getAsJsonObject();

                // Get prompt_id (required)
                if (!obj.has("prompt_id")) {
                    throw new BatchParsingException("Missing 'prompt_id' field in result object");
                }

                int promptId = obj.get("prompt_id").getAsInt();
                if (promptId < 1 || promptId > expectedCount) {
                    throw new BatchParsingException(
                        "Invalid prompt_id: " + promptId + " (expected 1-" + expectedCount + ")");
                }

                // Extract all fields except prompt_id
                Map<String, String> resultMap = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    String key = entry.getKey();
                    if (!"prompt_id".equals(key)) {
                        resultMap.put(key, entry.getValue().getAsString());
                    }
                }

                // Store in results list (prompt_id is 1-based, list is 0-based)
                results.set(promptId - 1, resultMap);
            }

            // Check if all prompts got results
            for (int i = 0; i < expectedCount; i++) {
                if (results.get(i).isEmpty()) {
                    throw new BatchParsingException(
                        "Missing result for prompt " + (i + 1));
                }
            }

            return results;

        } catch (Exception e) {
            throw new BatchParsingException("Failed to parse batched response: " + e.getMessage(), e);
        }
    }

    /**
     * Extract JSON array from response text, handling markdown code blocks.
     */
    private static String extractJsonArray(String response) throws BatchParsingException {
        String cleaned = response.trim();

        // Remove markdown code blocks
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }

        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        cleaned = cleaned.trim();

        // Find JSON array boundaries
        int startIdx = cleaned.indexOf('[');
        int endIdx = cleaned.lastIndexOf(']');

        if (startIdx < 0 || endIdx < 0 || endIdx <= startIdx) {
            throw new BatchParsingException(
                "Could not find JSON array in response. Response preview: " +
                response.substring(0, Math.min(200, response.length())));
        }

        return cleaned.substring(startIdx, endIdx + 1);
    }

    /**
     * Parse a single-variable batched response (simpler case).
     *
     * @param response the raw response text
     * @param expectedCount expected number of results
     * @param variableName the output variable name
     * @return list of values
     * @throws BatchParsingException if parsing fails
     */
    public static List<String> parseSingleVariable(String response, int expectedCount, String variableName)
            throws BatchParsingException {

        List<Map<String, String>> results = parse(response, expectedCount);
        List<String> values = new ArrayList<>(expectedCount);

        for (Map<String, String> result : results) {
            String value = result.get(variableName);
            if (value == null || value.isEmpty()) {
                throw new BatchParsingException(
                    "Missing or empty value for variable '" + variableName + "'");
            }
            values.add(value);
        }

        return values;
    }

    /**
     * Exception thrown when batch parsing fails.
     */
    public static class BatchParsingException extends Exception {
        public BatchParsingException(String message) {
            super(message);
        }

        public BatchParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
