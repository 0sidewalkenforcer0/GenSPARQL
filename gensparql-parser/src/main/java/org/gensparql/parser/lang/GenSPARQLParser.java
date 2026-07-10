package org.gensparql.parser.lang;

import org.apache.jena.query.Query;
import org.gensparql.core.exception.ParseException;
import org.gensparql.parser.element.ElementGenerate;
import org.gensparql.parser.javacc.GenSPARQLParserImpl;
import org.gensparql.parser.javacc.GenSPARQLTokenParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for GenSPARQL queries with native GENOP function support.
 *
 * This parser uses a complete JavaCC grammar that extends SPARQL 1.1
 * to support GENOP as a first-class construct in graph patterns.
 *
 * GENOP Syntax:
 *   GENOP("prompt", ?output, <model>)
 *   GENOP("prompt", (?var1, ?var2), <model>, threshold)
 *
 * GENOP appears in GraphPatternNotTriples alongside FILTER, BIND, OPTIONAL, etc.
 *
 * Modes:
 * - Base Mode: No input variables (In(g) = ∅), direct LLM call
 * - Context Mode: Prompt contains {?x} placeholders bound by BGP
 * - Multi-variable Output: LLM output parsed into multiple bindings
 * - Model Specification: Explicit model IRI
 * - Optional Threshold: Local similarity threshold θ
 */
public class GenSPARQLParser {
    private static final Logger LOG = LoggerFactory.getLogger(GenSPARQLParser.class);

    /**
     * Parse a GenSPARQL query string.
     *
     * @param queryString the query string
     * @return parsed Query with ElementGenerate elements
     * @throws ParseException if parsing fails
     */
    public static Query parse(String queryString) {
        return parse(queryString, null);
    }

    /**
     * Parse a GenSPARQL query string with base URI.
     *
     * @param queryString the query string
     * @param baseURI the base URI
     * @return parsed Query with ElementGenerate elements
     * @throws ParseException if parsing fails
     */
    public static Query parse(String queryString, String baseURI) {
        LOG.debug("Parsing GenSPARQL query:\n{}", queryString);

        try {
            Query query = GenSPARQLParserImpl.parse(queryString, baseURI);
            LOG.debug("Successfully parsed GenSPARQL query");
            return query;
        } catch (org.gensparql.parser.javacc.ParseException e) {
            throw new ParseException("Failed to parse GenSPARQL query: " + e.getMessage(), e);
        }
    }

    /**
     * Parse a standalone GENOP function call.
     *
     * @param genopText the GENOP function text
     * @return parsed ElementGenerate
     * @throws ParseException if parsing fails
     */
    public static ElementGenerate parseGenOp(String genopText) {
        try {
            return GenSPARQLTokenParser.parseGenOpFunction(genopText);
        } catch (org.gensparql.parser.javacc.ParseException e) {
            throw new ParseException("Failed to parse GENOP: " + e.getMessage(), e);
        }
    }
}
