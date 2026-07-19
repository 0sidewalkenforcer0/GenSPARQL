package org.gensparql.core.similarity;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link CanonicalForm}, focused on Freebase/NELL local-name extraction — the most
 * regression-prone code in the module and previously untested.
 */
class CanonicalFormTest {

    private static final String FB = "http://rdf.freebase.com/ns/";

    private static Node uri(String local) {
        return NodeFactory.createURI(FB + local);
    }

    /** Double-underscore separated FB15k URIs — the unambiguous, common case. */
    @Test
    void extractsLabelFromDoubleUnderscoreUris() {
        assertEquals("Cars 2", CanonicalForm.canon(uri("m_0407yj__Cars_2"), null));
        assertEquals("DVD", CanonicalForm.canon(uri("m_029j__DVD"), null));
        assertEquals("Austin Powers: International",
                CanonicalForm.canon(uri("m_01k1k4__Austin_Powers%3A_International"), null));
        assertEquals("Julie & Julia", CanonicalForm.canon(uri("m_xyz__Julie_%26_Julia"), null));
        assertEquals("50/50", CanonicalForm.canon(uri("m_0cmdwwg__50%2F50"), null));
    }

    /** Single-underscore fallback: digit-leading labels must be preserved (regression). */
    @Test
    void preservesDigitLeadingLabelInSingleUnderscoreFallback() {
        assertEquals("3 Idiots", CanonicalForm.canon(uri("m_047q2k1_3_Idiots"), null));
    }

    /** NELL concept_ URIs drop the "concept_" prefix. */
    @Test
    void extractsNellConceptLabel() {
        Node n = NodeFactory.createURI("http://nell.example/concept_stateorprovince_wyoming");
        assertEquals("stateorprovince wyoming", CanonicalForm.canon(n, null));
    }

    /** Plain URIs fall back to the fragment / last path segment. */
    @Test
    void extractsPlainLocalName() {
        assertEquals("Foo", CanonicalForm.canon(NodeFactory.createURI("http://example.org/Foo"), null));
        assertEquals("Bar", CanonicalForm.canon(NodeFactory.createURI("http://example.org/x#Bar"), null));
    }

    /**
     * The label lookup is authoritative: an underscore-containing mid is unrecoverable from
     * the URI alone, so a supplied MID→name map must win over string extraction.
     */
    @Test
    void labelLookupOverridesAmbiguousExtraction() {
        Node n = uri("m_0_2v_Asian_Development_Bank");
        String viaLookup = CanonicalForm.canon(n, u -> "Asian Development Bank");
        assertEquals("Asian Development Bank", viaLookup);
    }

    /** Literals return their lexical form unchanged. */
    @Test
    void literalsReturnLexicalForm() {
        assertEquals("Einstein", CanonicalForm.canon(NodeFactory.createLiteralString("Einstein"), null));
    }
}
