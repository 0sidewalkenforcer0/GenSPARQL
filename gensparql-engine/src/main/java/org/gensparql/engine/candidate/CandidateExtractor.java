package org.gensparql.engine.candidate;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Dataset;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.gensparql.core.similarity.CanonicalForm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Extracts candidate entities from a Knowledge Graph based on relations.
 *
 * This is used for Constrained Generation in GenSPARQL:
 * Instead of letting LLM generate arbitrary answers, we provide a list of
 * candidates from the KG and ask it to select from them.
 *
 * Example: For relation "clothingtogowithclothing", extract all entities
 * that appear as object (or subject) of this relation.
 */
public class CandidateExtractor {
    private static final Logger LOG = LoggerFactory.getLogger(CandidateExtractor.class);

    private final DatasetGraph dsg;
    private final Map<String, Set<String>> candidateCache = new HashMap<>();

    public CandidateExtractor(DatasetGraph dsg) {
        this.dsg = dsg;
    }

    public CandidateExtractor(Dataset dataset) {
        this(dataset.asDatasetGraph());
    }

    /**
     * Extract all entities that appear as OBJECT of the given relation.
     *
     * @param relationUri the relation URI (e.g., "http://nell.cs.cmu.edu/relation/concept_clothingtogowithclothing")
     * @return set of entity URIs/labels
     */
    public Set<String> extractObjectCandidates(String relationUri) {
        String cacheKey = "obj:" + relationUri;
        if (candidateCache.containsKey(cacheKey)) {
            return candidateCache.get(cacheKey);
        }

        Set<String> candidates = new LinkedHashSet<>();
        Node predicate = NodeFactory.createURI(relationUri);

        ExtendedIterator<Triple> iter = dsg.getDefaultGraph().find(Node.ANY, predicate, Node.ANY);
        try {
            while (iter.hasNext()) {
                Triple t = iter.next();
                Node obj = t.getObject();
                String label = extractLabel(obj);
                if (label != null) {
                    candidates.add(label);
                }
            }
        } finally {
            iter.close();
        }

        LOG.debug("Extracted {} object candidates for relation {}", candidates.size(), relationUri);
        candidateCache.put(cacheKey, candidates);
        return candidates;
    }

    /**
     * Extract all entities that appear as SUBJECT of the given relation.
     *
     * @param relationUri the relation URI
     * @return set of entity URIs/labels
     */
    public Set<String> extractSubjectCandidates(String relationUri) {
        String cacheKey = "subj:" + relationUri;
        if (candidateCache.containsKey(cacheKey)) {
            return candidateCache.get(cacheKey);
        }

        Set<String> candidates = new LinkedHashSet<>();
        Node predicate = NodeFactory.createURI(relationUri);

        ExtendedIterator<Triple> iter = dsg.getDefaultGraph().find(Node.ANY, predicate, Node.ANY);
        try {
            while (iter.hasNext()) {
                Triple t = iter.next();
                Node subj = t.getSubject();
                String label = extractLabel(subj);
                if (label != null) {
                    candidates.add(label);
                }
            }
        } finally {
            iter.close();
        }

        LOG.debug("Extracted {} subject candidates for relation {}", candidates.size(), relationUri);
        candidateCache.put(cacheKey, candidates);
        return candidates;
    }

    /**
     * Extract all entities that appear as either subject or object of the given relation.
     *
     * @param relationUri the relation URI
     * @return set of entity URIs/labels
     */
    public Set<String> extractAllCandidates(String relationUri) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.addAll(extractSubjectCandidates(relationUri));
        candidates.addAll(extractObjectCandidates(relationUri));
        return candidates;
    }

    /**
     * Extract candidates for a specific subject and relation (find all objects).
     *
     * @param subjectUri the subject entity URI
     * @param relationUri the relation URI
     * @return set of object entity labels
     */
    public Set<String> extractCandidatesForSubject(String subjectUri, String relationUri) {
        Set<String> candidates = new LinkedHashSet<>();
        Node subject = NodeFactory.createURI(subjectUri);
        Node predicate = NodeFactory.createURI(relationUri);

        ExtendedIterator<Triple> iter = dsg.getDefaultGraph().find(subject, predicate, Node.ANY);
        try {
            while (iter.hasNext()) {
                Triple t = iter.next();
                String label = extractLabel(t.getObject());
                if (label != null) {
                    candidates.add(label);
                }
            }
        } finally {
            iter.close();
        }

        return candidates;
    }

    /**
     * Extract a human-readable label from a node, using the same canonicalization
     * as {@link CanonicalForm} so candidate labels are consistent with the labels
     * the similarity join and grounding compare against. For example the Freebase
     * URI {@code m_0407yj__Cars_2} yields {@code "Cars 2"} (not the raw local name),
     * and percent escapes are decoded.
     */
    private String extractLabel(Node node) {
        if (node == null) {
            return null;
        }
        String label = CanonicalForm.canon(node);
        return (label == null || label.isEmpty()) ? null : label;
    }

    /**
     * Get the full URI for a candidate label.
     * Useful for mapping LLM-selected candidates back to URIs.
     *
     * @param label the entity label
     * @param relationUri the relation URI (to find the namespace)
     * @return the full entity URI, or null if not found
     */
    public String getLabelToUri(String label, String relationUri) {
        Node predicate = NodeFactory.createURI(relationUri);

        ExtendedIterator<Triple> iter = dsg.getDefaultGraph().find(Node.ANY, predicate, Node.ANY);
        try {
            while (iter.hasNext()) {
                Triple t = iter.next();
                if (label.equals(extractLabel(t.getObject()))) {
                    return t.getObject().getURI();
                }
                if (label.equals(extractLabel(t.getSubject()))) {
                    return t.getSubject().getURI();
                }
            }
        } finally {
            iter.close();
        }

        return null;
    }

    /**
     * Clear the candidate cache.
     */
    public void clearCache() {
        candidateCache.clear();
    }

    /**
     * Extract all object candidates with their URIs for a relation.
     * Returns a mapping from label to full URI for grounding purposes.
     *
     * @param relationUri the relation URI
     * @return map of label -> URI
     */
    public Map<String, String> extractCandidatesWithUris(String relationUri) {
        Map<String, String> labelToUri = new LinkedHashMap<>();
        Node predicate = NodeFactory.createURI(relationUri);

        ExtendedIterator<Triple> iter = dsg.getDefaultGraph().find(Node.ANY, predicate, Node.ANY);
        try {
            while (iter.hasNext()) {
                Triple t = iter.next();
                Node obj = t.getObject();
                if (obj.isURI()) {
                    String label = extractLabel(obj);
                    if (label != null && !labelToUri.containsKey(label)) {
                        labelToUri.put(label, obj.getURI());
                    }
                }
            }
        } finally {
            iter.close();
        }

        LOG.debug("Extracted {} candidates with URIs for relation {}", labelToUri.size(), relationUri);
        return labelToUri;
    }

    /**
     * Extract all unique entities from the dataset.
     * Returns labels for all subjects and objects that are URIs.
     *
     * @return set of entity labels
     */
    public Set<String> extractAllEntities() {
        Set<String> entities = new LinkedHashSet<>();

        ExtendedIterator<Triple> iter = dsg.getDefaultGraph().find(Node.ANY, Node.ANY, Node.ANY);
        try {
            while (iter.hasNext()) {
                Triple t = iter.next();

                // Add subject if it's a URI
                Node subj = t.getSubject();
                if (subj.isURI()) {
                    String label = extractLabel(subj);
                    if (label != null) {
                        entities.add(label);
                    }
                }

                // Add object if it's a URI
                Node obj = t.getObject();
                if (obj.isURI()) {
                    String label = extractLabel(obj);
                    if (label != null) {
                        entities.add(label);
                    }
                }
            }
        } finally {
            iter.close();
        }

        LOG.debug("Extracted {} total entities from dataset", entities.size());
        return entities;
    }

    /**
     * Extract all entities with their URIs from the dataset.
     *
     * @return map of label -> URI for all entities
     */
    public Map<String, String> extractAllEntitiesWithUris() {
        Map<String, String> labelToUri = new LinkedHashMap<>();

        ExtendedIterator<Triple> iter = dsg.getDefaultGraph().find(Node.ANY, Node.ANY, Node.ANY);
        try {
            while (iter.hasNext()) {
                Triple t = iter.next();

                // Add subject if it's a URI
                Node subj = t.getSubject();
                if (subj.isURI()) {
                    String label = extractLabel(subj);
                    if (label != null && !labelToUri.containsKey(label)) {
                        labelToUri.put(label, subj.getURI());
                    }
                }

                // Add object if it's a URI
                Node obj = t.getObject();
                if (obj.isURI()) {
                    String label = extractLabel(obj);
                    if (label != null && !labelToUri.containsKey(label)) {
                        labelToUri.put(label, obj.getURI());
                    }
                }
            }
        } finally {
            iter.close();
        }

        LOG.debug("Extracted {} total entities with URIs", labelToUri.size());
        return labelToUri;
    }
}
