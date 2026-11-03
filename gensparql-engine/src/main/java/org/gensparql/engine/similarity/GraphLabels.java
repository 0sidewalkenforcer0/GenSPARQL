package org.gensparql.engine.similarity;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.util.iterator.ExtendedIterator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The human-readable label of an IRI, read from the graph a query runs over.
 *
 * <p>A similarity join compares a generated value with the label of a KG entity. The label
 * belongs to the graph being queried, so it is looked up here, per query, rather than through
 * a process-wide table: a server holding several datasets would otherwise compare against the
 * labels of whichever dataset set the table last. Entities whose IRI carries no readable name,
 * such as the Wikidata {@code wd:Q79800}, cannot be matched at all without this.
 *
 * <p>The label properties are tried in order: {@code rdfs:label}, {@code skos:prefLabel},
 * {@code schema:name} and {@code foaf:name}. A node with several labels of that property
 * keeps all of them, the preferred one first: a label without a language tag, then an
 * English one, then the smallest by lexical form, so that the order does not depend on the
 * order in which the graph stores triples.
 * Lookups are cached for the lifetime of the instance, which is one query execution.
 */
public final class GraphLabels implements Function<Node, String> {

    private static final List<Node> LABEL_PROPERTIES = List.of(
            NodeFactory.createURI("http://www.w3.org/2000/01/rdf-schema#label"),
            NodeFactory.createURI("http://www.w3.org/2004/02/skos/core#prefLabel"),
            NodeFactory.createURI("http://schema.org/name"),
            NodeFactory.createURI("https://schema.org/name"),
            NodeFactory.createURI("http://xmlns.com/foaf/0.1/name"));

    private final Graph graph;
    private final Map<Node, List<String>> cache = new HashMap<>();

    public GraphLabels(Graph graph) {
        this.graph = graph;
    }

    /**
     * The preferred label of the node, or {@code null} if the node is not an IRI or has no
     * label.
     */
    @Override
    public String apply(Node node) {
        List<String> labels = all(node);
        return labels.isEmpty() ? null : labels.get(0);
    }

    /**
     * Every label of the node: the values of the first label property that the node has in
     * the graph, the preferred one first. Empty if the node is not an IRI or has no label.
     * A generated name matches the node if it matches one of these labels.
     */
    public List<String> all(Node node) {
        if (graph == null || node == null || !node.isURI()) {
            return List.of();
        }
        List<String> labels = cache.get(node);
        if (labels == null) {
            labels = lookup(node);
            cache.put(node, labels);
        }
        return labels;
    }

    private List<String> lookup(Node node) {
        for (Node property : LABEL_PROPERTIES) {
            List<Node> found = new ArrayList<>();
            ExtendedIterator<Triple> it = graph.find(node, property, Node.ANY);
            try {
                while (it.hasNext()) {
                    Node object = it.next().getObject();
                    if (object.isLiteral() && !object.getLiteralLexicalForm().isBlank()) {
                        found.add(object);
                    }
                }
            } finally {
                it.close();
            }
            if (!found.isEmpty()) {
                found.sort((a, b) -> preferred(a, b) ? -1 : (preferred(b, a) ? 1 : 0));
                List<String> labels = new ArrayList<>();
                for (Node n : found) {
                    String lex = n.getLiteralLexicalForm();
                    if (!labels.contains(lex)) {
                        labels.add(lex);
                    }
                }
                return List.copyOf(labels);
            }
        }
        return List.of();
    }

    /** True if label {@code a} is to be preferred over label {@code b}. */
    private static boolean preferred(Node a, Node b) {
        int ra = rank(a.getLiteralLanguage());
        int rb = rank(b.getLiteralLanguage());
        if (ra != rb) {
            return ra < rb;
        }
        return a.getLiteralLexicalForm().compareTo(b.getLiteralLexicalForm()) < 0;
    }

    private static int rank(String language) {
        if (language == null || language.isEmpty()) {
            return 0;
        }
        return language.toLowerCase().startsWith("en") ? 1 : 2;
    }
}
