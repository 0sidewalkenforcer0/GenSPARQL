package org.gensparql.llm.prompt;

import java.util.Collection;
import java.util.List;

/**
 * Utility class for building enhanced prompts for LLM requests.
 *
 * When multiple output variables are expected, this builder adds
 * structured format instructions to guide the LLM to return JSON.
 *
 * Supports Constrained Generation by injecting candidate entity lists,
 * converting open-domain generation into a selection task.
 */
public class PromptBuilder {

    /** Maximum number of candidates to include in prompt (to avoid context overflow) */
    private static final int DEFAULT_MAX_CANDIDATES = 500;

    private PromptBuilder() {
        // Utility class
    }

    /**
     * Build a prompt with structured output format instructions.
     *
     * For single variable outputs, returns the original prompt unchanged.
     * For multiple variables, appends JSON format requirements.
     *
     * @param userPrompt the original user prompt
     * @param outputVars list of expected output variable names
     * @return enhanced prompt with format instructions if needed
     */
    public static String buildStructuredPrompt(String userPrompt, List<String> outputVars) {
        if (userPrompt == null) {
            return "";
        }

        if (outputVars == null || outputVars.size() <= 1) {
            return userPrompt;
        }

        StringBuilder sb = new StringBuilder(userPrompt);
        sb.append("\n\n");
        sb.append("[OUTPUT FORMAT REQUIREMENT]\n");
        sb.append("You MUST respond with ONLY a valid JSON array.\n");
        sb.append("Each object in the array must contain exactly these fields: ");
        sb.append(String.join(", ", outputVars));
        sb.append("\n\nIMPORTANT: Objects in the array MUST be separated by commas!\n");
        sb.append("\nExample format (note the commas between objects):\n");
        sb.append("[\n  {");

        // First object
        for (int i = 0; i < outputVars.size(); i++) {
            sb.append("\"").append(outputVars.get(i)).append("\": \"value1\"");
            if (i < outputVars.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("},\n  {");  // comma between objects!

        // Second object
        for (int i = 0; i < outputVars.size(); i++) {
            sb.append("\"").append(outputVars.get(i)).append("\": \"value2\"");
            if (i < outputVars.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("},\n  {");  // comma between objects!

        // Third object
        for (int i = 0; i < outputVars.size(); i++) {
            sb.append("\"").append(outputVars.get(i)).append("\": \"value3\"");
            if (i < outputVars.size() - 1) {
                sb.append(", ");
            }
        }

        sb.append("}\n]\n\n");
        sb.append("Do NOT include any markdown formatting, code blocks, or explanations.\n");
        sb.append("Return ONLY the raw JSON array, nothing else.");

        return sb.toString();
    }

    /**
     * Build a prompt for single-row structured output.
     *
     * @param userPrompt the original user prompt
     * @param outputVars list of expected output variable names
     * @return enhanced prompt requesting a single JSON object
     */
    public static String buildSingleRowPrompt(String userPrompt, List<String> outputVars) {
        if (userPrompt == null) {
            return "";
        }

        if (outputVars == null || outputVars.size() <= 1) {
            return userPrompt;
        }

        StringBuilder sb = new StringBuilder(userPrompt);
        sb.append("\n\n");
        sb.append("[OUTPUT FORMAT REQUIREMENT]\n");
        sb.append("You MUST respond with ONLY a valid JSON object.\n");
        sb.append("The object must contain exactly these fields: ");
        sb.append(String.join(", ", outputVars));
        sb.append("\n\nExample format:\n");
        sb.append("{");

        for (int i = 0; i < outputVars.size(); i++) {
            sb.append("\"").append(outputVars.get(i)).append("\": \"value\"");
            if (i < outputVars.size() - 1) {
                sb.append(", ");
            }
        }

        sb.append("}\n\n");
        sb.append("Do NOT include any markdown formatting, code blocks, or explanations.\n");
        sb.append("Return ONLY the raw JSON object, nothing else.");

        return sb.toString();
    }

    /**
     * Build a prompt with candidate entity list for constrained generation.
     *
     * Instead of asking LLM to generate arbitrary answers, this converts
     * the task into a selection problem where LLM must choose from the
     * provided candidate list.
     *
     * @param userPrompt the original user prompt (will be adapted)
     * @param candidates collection of candidate entity labels
     * @param outputVars list of expected output variable names
     * @return enhanced prompt with candidate list and format instructions
     */
    public static String buildConstrainedPrompt(String userPrompt, Collection<String> candidates,
                                                 List<String> outputVars) {
        return buildConstrainedPrompt(userPrompt, candidates, outputVars, DEFAULT_MAX_CANDIDATES);
    }

    /**
     * Build a prompt with candidate entity list for constrained generation.
     *
     * @param userPrompt the original user prompt
     * @param candidates collection of candidate entity labels
     * @param outputVars list of expected output variable names
     * @param maxCandidates maximum number of candidates to include
     * @return enhanced prompt with candidate list and format instructions
     */
    public static String buildConstrainedPrompt(String userPrompt, Collection<String> candidates,
                                                 List<String> outputVars, int maxCandidates) {
        if (userPrompt == null) {
            return "";
        }

        if (candidates == null || candidates.isEmpty()) {
            // Fall back to standard prompt building
            return buildStructuredPrompt(userPrompt, outputVars);
        }

        StringBuilder sb = new StringBuilder();

        // Add task description
        sb.append("[TASK]\n");
        sb.append(userPrompt);
        sb.append("\n\n");

        // Add candidate list
        sb.append("[CANDIDATE ENTITIES]\n");
        sb.append("You MUST select your answers ONLY from the following list of entities.\n");
        sb.append("Do NOT generate any entity that is not in this list.\n\n");

        int count = 0;
        for (String candidate : candidates) {
            if (count >= maxCandidates) {
                sb.append("... and ").append(candidates.size() - maxCandidates).append(" more candidates\n");
                break;
            }
            sb.append("- ").append(candidate).append("\n");
            count++;
        }
        sb.append("\n");

        // Add output format instructions
        sb.append("[OUTPUT FORMAT]\n");
        if (outputVars == null || outputVars.size() <= 1) {
            sb.append("Return ONLY a JSON array of the selected entity names from the candidate list.\n");
            sb.append("Example: [\"entity1\", \"entity2\", \"entity3\"]\n\n");
            sb.append("IMPORTANT:\n");
            sb.append("- Only include entities that match the task criteria\n");
            sb.append("- Use EXACT names from the candidate list (case-sensitive)\n");
            sb.append("- Return an empty array [] if no candidates match\n");
            sb.append("- Do NOT include any explanations, just the JSON array\n");
        } else {
            sb.append("Return a JSON array where each object contains: ");
            sb.append(String.join(", ", outputVars));
            sb.append("\n\nExample format:\n[\n  {");
            for (int i = 0; i < outputVars.size(); i++) {
                sb.append("\"").append(outputVars.get(i)).append("\": \"selected_entity\"");
                if (i < outputVars.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("}\n]\n\n");
            sb.append("IMPORTANT: Use EXACT names from the candidate list.\n");
        }

        return sb.toString();
    }

    /**
     * Build a selection-style prompt that asks LLM to classify candidates.
     *
     * This is an alternative approach that presents the task as a yes/no
     * classification for each candidate.
     *
     * @param taskDescription description of what to look for
     * @param candidates collection of candidate entity labels
     * @return prompt asking for classification of candidates
     */
    public static String buildSelectionPrompt(String taskDescription, Collection<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return taskDescription;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("For the following task, identify which entities from the list apply.\n\n");
        sb.append("Task: ").append(taskDescription).append("\n\n");
        sb.append("Entities to evaluate:\n");

        int idx = 1;
        for (String candidate : candidates) {
            if (idx > DEFAULT_MAX_CANDIDATES) {
                break;
            }
            sb.append(idx).append(". ").append(candidate).append("\n");
            idx++;
        }

        sb.append("\nReturn ONLY a JSON array with the numbers of matching entities.\n");
        sb.append("Example: [1, 3, 5] means entities 1, 3, and 5 match.\n");
        sb.append("Return [] if none match.\n");

        return sb.toString();
    }
}
