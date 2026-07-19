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
     * where {@code <mid>} is the machine id. This extraction is inherently <b>best-effort</b>:
     * the mid uses a base-32 alphabet that itself contains underscores, so a URI such as
     * {@code m_0_2v_Asian_Development_Bank} (mid {@code 0_2v}) cannot be split unambiguously
     * from the string alone. For FB15k, supply an external MID&rarr;name map via
     * {@link #setLabelLookup(Function)} / {@link #canon(Node, Function)} — that lookup is the
     * authoritative path and this method is only the fallback.
     *
     * <p>Handled here:
     * <ul>
     *   <li><b>Double-underscore separator preferred.</b> The common FB15k encoding separates
     *       mid and label with {@code __} (e.g. {@code m_0407yj__Cars_2} &rarr; "Cars 2"); when
     *       present this is unambiguous and used first.</li>
     *   <li>the label may start with a digit (e.g. "3 Idiots"), so we do NOT scan for the
     *       first {@code _[A-Z]} (which dropped leading digits);</li>
     *   <li>labels contain percent escapes (%26 -&gt; '&amp;', %2F -&gt; '/', %3A -&gt; ':'),
     *       so we URL-decode them.</li>
     * </ul>
     * The single-underscore fallback below can still misfire on underscore-containing mids —
     * that case is genuinely unrecoverable without the label lookup.
     */
    private static String extractLocalName(String uri) {
        int hashIdx = uri.lastIndexOf('#');
        int slashIdx = uri.lastIndexOf('/');
        int idx = Math.max(hashIdx, slashIdx);
        if (idx >= 0 && idx < uri.length() - 1) {
            String localName = uri.substring(idx + 1);

            // Freebase URIs: m_<mid>_<label> or m_<mid>__<label>
            if (localName.startsWith("m_")) {
                // Preferred, unambiguous: split on the double-underscore separator.
                int dbl = localName.indexOf("__", 2);
                if (dbl > 0 && dbl + 2 < localName.length()) {
                    return cleanLabel(localName.substring(dbl + 2));
                }
                // Best-effort fallback: single-underscore split after the mid. May misfire
                // when the mid contains underscores (unrecoverable without a label lookup).
                int sep = localName.indexOf('_', 2);
                if (sep > 0 && sep + 1 < localName.length()) {
                    return cleanLabel(localName.substring(sep + 1));
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
        return WHITESPACE.matcher(decoded.replace('_', ' ').replace('-', ' ')).replaceAll(" ").trim();
    }

    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

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
