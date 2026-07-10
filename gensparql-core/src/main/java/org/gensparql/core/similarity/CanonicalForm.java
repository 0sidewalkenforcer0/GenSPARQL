package org.gensparql.core.similarity;

import org.apache.jena.graph.Node;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Canonical string representation for RDF terms.
 *
 * Implements Canon(t) and Lex(t) functions from the GenSPARQL paper:
 * - Canon(t): Label if exists (rdfs:label), else Local Name for IRIs
 * - Lex(t): Direct lexical form extraction
 *
 * These functions are used by SimScore for comparing RDF and GEN values.
 */
public class CanonicalForm {

    // Cache for rdfs:label lookups to avoid repeated queries
    private static final Map<String, String> labelCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 10000;

    // Label lookup function (can be set by the engine)
    private static volatile Function<String, String> labelLookup = null;

    /**
     * Set the label lookup function for rdfs:label queries.
     * This should be called during engine initialization.
     *
     * @param lookup function that takes a URI and returns its rdfs:label, or null if not found
     */
    public static void setLabelLookup(Function<String, String> lookup) {
        labelLookup = lookup;
        labelCache.clear();
    }

    /**
     * Clear the label cache.
     */
    public static void clearLabelCache() {
        labelCache.clear();
    }

    /**
     * Get canonical form of an RDF term for similarity comparison.
     * Implements Canon(t) function from the paper.
     *
     * For IRIs: first tries rdfs:label (if lookup is configured), then Local Name.
     * For literals, returns the lexical form.
     * For blank nodes, returns the blank node label.
     *
     * @param node the RDF node
     * @return canonical string representation
     */
    public static String canon(Node node) {
        if (node == null) {
            return "";
        }

        if (node.isLiteral()) {
            // For literals: use lexical form
            return node.getLiteralLexicalForm();
        }

        if (node.isURI()) {
            String uri = node.getURI();

            // Step 1: Try rdfs:label lookup (paper specification)
            String label = lookupLabel(uri);
            if (label != null && !label.isEmpty()) {
                return label;
            }

            // Step 2: Fall back to local name extraction
            return extractLocalName(uri);
        }

        if (node.isBlank()) {
            return node.getBlankNodeLabel();
        }

        return node.toString();
    }

    /**
     * Get canonical form with explicit label lookup function.
     * Use this when you have a specific Model/Dataset to query.
     *
     * @param node   the RDF node
     * @param lookup function to lookup rdfs:label for a URI
     * @return canonical string representation
     */
    public static String canon(Node node, Function<String, String> lookup) {
        if (node == null) {
            return "";
        }

        if (node.isLiteral()) {
            return node.getLiteralLexicalForm();
        }

        if (node.isURI()) {
            String uri = node.getURI();

            // Try rdfs:label lookup first
            if (lookup != null) {
                String label = lookup.apply(uri);
                if (label != null && !label.isEmpty()) {
                    return label;
                }
            }

            // Fall back to local name
            return extractLocalName(uri);
        }

        if (node.isBlank()) {
            return node.getBlankNodeLabel();
        }

        return node.toString();
    }

    /**
     * Lookup rdfs:label for a URI using the configured lookup function.
     */
    private static String lookupLabel(String uri) {
        if (labelLookup == null) {
            return null;
        }

        // Check cache first
        String cached = labelCache.get(uri);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        // Perform lookup
        String label = labelLookup.apply(uri);

        // Cache result (even empty ones to avoid repeated lookups)
        if (labelCache.size() < MAX_CACHE_SIZE) {
            labelCache.put(uri, label != null ? label : "");
        }

        return label;
    }

    /**
     * Extract local name from a URI (fragment after # or last /).
     * For Freebase URIs like m_0407yj__Cars_2, extracts the label part (-> "Cars 2").
     *
     * <p>The Freebase local name is {@code m_<mid>_<label>} or {@code m_<mid>__<label>},
     * where {@code <mid>} is the machine id (lowercase alphanumeric). The label is the
     * whole remainder after the first underscore that follows the mid. Two previous bugs
     * are fixed here:
     * <ul>
     *   <li>the label may start with a digit (e.g. "3 Idiots", "(500) Days of Summer"),
     *       so we must NOT scan for the first {@code _[A-Z]} (which dropped the digits);</li>
     *   <li>labels contain percent escapes (e.g. %26 -&gt; '&amp;', %2F -&gt; '/',
     *       %3A -&gt; ':'), so we URL-decode them.</li>
     * </ul>
     */
    private static String extractLocalName(String uri) {
        int hashIdx = uri.lastIndexOf('#');
        int slashIdx = uri.lastIndexOf('/');
        int idx = Math.max(hashIdx, slashIdx);
        if (idx >= 0 && idx < uri.length() - 1) {
            String localName = uri.substring(idx + 1);

            // Freebase URIs: m_<mid>_<label> or m_<mid>__<label>
            if (localName.startsWith("m_")) {
                int sep = localName.indexOf('_', 2); // first underscore after the mid
                if (sep > 0) {
                    int labelStart = sep + 1;
                    // skip the second underscore of a "__" separator
                    if (labelStart < localName.length() && localName.charAt(labelStart) == '_') {
                        labelStart++;
                    }
                    if (labelStart < localName.length()) {
                        return cleanLabel(localName.substring(labelStart));
                    }
                }
                // no label component -> fall through to generic handling
            }

            // NELL URIs: concept_xxx_yyy -> "xxx yyy"
            if (localName.startsWith("concept_")) {
                return cleanLabel(localName.substring(8));
            }

            return cleanLabel(localName);
        }
        return uri;
    }

    /**
     * Normalize an extracted label: URL-decode percent escapes, then turn
     * underscores and hyphens into spaces and collapse whitespace.
     */
    private static String cleanLabel(String label) {
        String decoded;
        try {
            // protect any literal '+' (URLDecoder would otherwise turn it into a space)
            decoded = java.net.URLDecoder.decode(
                    label.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            decoded = label;
        }
        return decoded.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
    }

    /**
     * Get lexical form of a term.
     * Implements Lex(t) function from the paper.
     *
     * For LLM-generated terms, this extracts the direct lexical form.
     *
     * @param node the RDF node
     * @return lexical string representation
     */
    public static String lex(Node node) {
        if (node == null) {
            return "";
        }

        if (node.isLiteral()) {
            return node.getLiteralLexicalForm();
        }

        return node.toString();
    }

    /**
     * Normalize a string for comparison.
     * Converts to lowercase and trims whitespace.
     *
     * @param s the string to normalize
     * @return normalized string
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase().trim();
    }

    /**
     * Extract a human-readable label from a URI or node.
     * Converts camelCase and snake_case to space-separated words.
     *
     * @param node the RDF node
     * @return human-readable label
     */
    public static String toLabel(Node node) {
        String canon = canon(node);
        if (canon.isEmpty()) {
            return "";
        }

        // Convert camelCase to spaces
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < canon.length(); i++) {
            char c = canon.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                sb.append(' ');
            }
            if (c == '_' || c == '-') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }

        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
