package org.gensparql.llm.batch;

import java.util.List;

/**
 * Builder for creating batched LLM prompts.
 * Combines multiple individual prompts into a single batched request.
 */
public class BatchedPromptBuilder {

    /**
     * Build a batched prompt from multiple individual prompts.
     *
     * @param prompts list of individual prompts
     * @param outputVars list of output variable names
     * @return batched prompt string
     */
    public static String buildBatchedPrompt(List<String> prompts, List<String> outputVars) {
        if (prompts == null || prompts.isEmpty()) {
            throw new IllegalArgumentException("Prompts list cannot be null or empty");
        }

        int count = prompts.size();
        StringBuilder sb = new StringBuilder();

        // Header instructions
        sb.append("I will provide you with ").append(count)
          .append(" prompts numbered 1 to ").append(count)
          .append(". For each prompt, generate the requested output.\n\n");

        // Add each prompt with numbering
        for (int i = 0; i < count; i++) {
            sb.append("PROMPT ").append(i + 1).append(":\n");
            sb.append(prompts.get(i));
            sb.append("\n\n");
        }

        // Response format instructions
        sb.append("IMPORTANT: Return your response as a JSON array with exactly ")
          .append(count).append(" objects.\n");
        sb.append("Each object must have:\n");
        sb.append("- A 'prompt_id' field with value from 1 to ").append(count).append("\n");

        if (outputVars != null && !outputVars.isEmpty()) {
            sb.append("- Fields for the output variables: ");
            for (int i = 0; i < outputVars.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("'").append(outputVars.get(i)).append("'");
            }
            sb.append("\n");
        }

        sb.append("\nExpected format:\n");
        sb.append("[\n");

        // Example format
        for (int i = 0; i < Math.min(2, count); i++) {
            sb.append("  {\"prompt_id\": ").append(i + 1);
            if (outputVars != null && !outputVars.isEmpty()) {
                for (String var : outputVars) {
                    sb.append(", \"").append(var).append("\": \"...\"");
                }
            }
            sb.append("}");
            if (i < count - 1) sb.append(",");
            sb.append("\n");
        }

        if (count > 2) {
            sb.append("  ...\n");
            sb.append("  {\"prompt_id\": ").append(count);
            if (outputVars != null && !outputVars.isEmpty()) {
                for (String var : outputVars) {
                    sb.append(", \"").append(var).append("\": \"...\"");
                }
            }
            sb.append("}\n");
        }

        sb.append("]\n\n");
        sb.append("Ensure all ").append(count).append(" prompts are answered in order.");

        return sb.toString();
    }

    /**
     * Estimate the size of the batched prompt in characters.
     *
     * @param prompts list of individual prompts
     * @param outputVars list of output variable names
     * @return estimated character count
     */
    public static int estimateBatchedPromptSize(List<String> prompts, List<String> outputVars) {
        if (prompts == null || prompts.isEmpty()) {
            return 0;
        }

        int totalPromptLength = prompts.stream().mapToInt(String::length).sum();
        int overhead = 500; // Approximate overhead from instructions and formatting
        int outputVarOverhead = outputVars != null ? outputVars.size() * 50 : 0;

        return totalPromptLength + overhead + outputVarOverhead;
    }

    /**
     * Check if a batch would exceed a token limit (rough estimate: 1 token ≈ 4 chars).
     *
     * @param prompts list of individual prompts
     * @param outputVars list of output variable names
     * @param maxTokens maximum token limit
     * @return true if batch is within limits
     */
    public static boolean isBatchWithinLimits(List<String> prompts, List<String> outputVars, int maxTokens) {
        int estimatedChars = estimateBatchedPromptSize(prompts, outputVars);
        int estimatedTokens = estimatedChars / 4; // Rough approximation
        return estimatedTokens <= maxTokens * 0.7; // Use 70% to leave room for response
    }
}
