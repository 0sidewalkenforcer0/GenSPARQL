package org.gensparql.test;

import org.gensparql.parser.GenSPARQLQueryFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a GENOP may stand. Section 2.1 of the paper places GENOP outside EXISTS and NOT EXISTS,
 * and a GENOP inside SERVICE would have to run at the remote endpoint. The parser rejects both
 * with a message that says why, and accepts SERVICE without a GENOP.
 */
class GenopPlacementRulesTest {

    private static final String G = "GENOP(\"List the teams.\", (?x), <model:mock:test>)";

    private static String rejection(String query) {
        Exception e = assertThrows(Exception.class, () -> GenSPARQLQueryFactory.create(query));
        return String.valueOf(e.getMessage());
    }

    @Test
    void aGenopInsideExistsOrNotExistsIsRejected() {
        assertTrue(rejection("SELECT * WHERE { ?s ?p ?o FILTER EXISTS { " + G + " } }").contains("EXISTS"));
        assertTrue(rejection("SELECT * WHERE { ?s ?p ?o FILTER NOT EXISTS { " + G + " } }").contains("EXISTS"));
    }

    @Test
    void aGenopInsideServiceIsRejectedWithAReason() {
        String message = rejection("SELECT * WHERE { SERVICE <http://example.org/sparql> { " + G + " } }");
        assertTrue(message.contains("SERVICE") && message.contains("remote endpoint"), message);
    }

    @Test
    void serviceWithoutAGenopIsAccepted() {
        assertDoesNotThrow(() -> GenSPARQLQueryFactory.create(
                "SELECT * WHERE { SERVICE <http://example.org/sparql> { ?s ?p ?o } " + G + " }"));
    }
}
