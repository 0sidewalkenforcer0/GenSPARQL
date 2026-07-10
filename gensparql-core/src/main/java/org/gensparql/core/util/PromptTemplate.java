package org.gensparql.core.util;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for handling prompt templates with variable placeholders.
 *
 * Prompt templates use {?varName} syntax for variable placeholders.
 * Example: "Translate '{?name}' to {?targetLanguage}"
 */
public class PromptTemplate {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\?(\\w+)\\}");

    private final String template;
    private final Set<Var> inputVariables;

    public PromptTemplate(String template) {
        this.template = Objects.requireNonNull(template, "template cannot be null");
        this.inputVariables = extractVariables(template);
    }

    /**
     * Get the raw template string.
     */
    public String getTemplate() {
        return template;
    }

    /**
     * Get the set of input variables (placeholders) in this template.
     */
    public Set<Var> getInputVariables() {
        return inputVariables;
    }

    /**
     * Check if this template has any input variables.
     */
    public boolean hasInputVariables() {
        return !inputVariables.isEmpty();
    }

    /**
     * Resolve the template by substituting variable bindings.
     *
     * @param binding the variable bindings to use
     * @return the resolved prompt string
     */
    public String resolve(Binding binding) {
        if (!hasInputVariables()) {
            return template;
        }

        String result = template;
        for (Var var : inputVariables) {
            Node value = binding.get(var);
            String replacement = nodeToString(value);
            result = result.replace("{?" + var.getName() + "}", replacement);
        }
        return result;
    }

    /**
     * Resolve the template with a map of variable name -> value.
     *
     * @param values the variable values
     * @return the resolved prompt string
     */
    public String resolve(Map<String, String> values) {
        if (!hasInputVariables()) {
            return template;
        }

        String result = template;
        for (Var var : inputVariables) {
            String value = values.get(var.getName());
            if (value != null) {
                result = result.replace("{?" + var.getName() + "}", value);
            }
        }
        return result;
    }

    /**
     * Check if all input variables are bound in the given binding.
     */
    public boolean allVariablesBound(Binding binding) {
        for (Var var : inputVariables) {
            if (!binding.contains(var)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get unbound variables for a given binding.
     */
    public Set<Var> getUnboundVariables(Binding binding) {
        Set<Var> unbound = new HashSet<>();
        for (Var var : inputVariables) {
            if (!binding.contains(var)) {
                unbound.add(var);
            }
        }
        return unbound;
    }

    /**
     * Extract variable names from a template string.
     */
    public static Set<Var> extractVariables(String template) {
        Set<Var> variables = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            String varName = matcher.group(1);
            variables.add(Var.alloc(varName));
        }
        return Collections.unmodifiableSet(variables);
    }

    /**
     * Convert a Node to its string representation for prompt substitution.
     */
    public static String nodeToString(Node node) {
        if (node == null) {
            return "";
        }
        if (node.isLiteral()) {
            return node.getLiteralLexicalForm();
        }
        if (node.isURI()) {
            return node.getURI();
        }
        if (node.isBlank()) {
            return node.getBlankNodeLabel();
        }
        return node.toString();
    }

    /**
     * Validate that a template is well-formed.
     *
     * @param template the template to validate
     * @throws IllegalArgumentException if the template is invalid
     */
    public static void validate(String template) {
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException("Template cannot be null or empty");
        }

        // Check for unmatched braces
        int openCount = 0;
        for (char c : template.toCharArray()) {
            if (c == '{') openCount++;
            if (c == '}') openCount--;
            if (openCount < 0) {
                throw new IllegalArgumentException("Unmatched closing brace in template");
            }
        }
        if (openCount != 0) {
            throw new IllegalArgumentException("Unmatched opening brace in template");
        }

        // Validate variable names
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            String varName = matcher.group(1);
            if (!isValidVariableName(varName)) {
                throw new IllegalArgumentException("Invalid variable name: " + varName);
            }
        }
    }

    /**
     * Check if a variable name is valid.
     */
    public static boolean isValidVariableName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        return name.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PromptTemplate that = (PromptTemplate) o;
        return Objects.equals(template, that.template);
    }

    @Override
    public int hashCode() {
        return Objects.hash(template);
    }

    @Override
    public String toString() {
        return "PromptTemplate{" +
               "template='" + truncate(template, 50) + '\'' +
               ", inputVariables=" + inputVariables +
               '}';
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
}
