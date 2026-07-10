package org.gensparql.llm.parser;

import java.util.List;
import java.util.Map;

/**
 * Interface for parsing LLM response content into variable bindings.
 *
 * Different implementations can handle various output formats:
 * - JSON arrays for multiple results
 * - JSON objects for single results
 * - Plain text for single variables
 */
public interface ResponseParser {

    /**
     * Parse LLM response content into variable bindings.
     *
     * @param content    the raw LLM response content
     * @param outputVars the expected output variable names
     * @return list of bindings, where each binding is a map of varName -> value
     */
    List<Map<String, String>> parse(String content, List<String> outputVars);

    /**
     * Check if this parser can handle the given content.
     *
     * @param content the raw LLM response content
     * @return true if this parser can parse the content
     */
    boolean canParse(String content);
}
