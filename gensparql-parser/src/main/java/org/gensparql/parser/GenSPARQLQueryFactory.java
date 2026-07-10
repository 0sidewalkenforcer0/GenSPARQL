package org.gensparql.parser;

import org.apache.jena.query.Query;
import org.gensparql.parser.lang.GenSPARQLParser;

/**
 * Factory for creating GenSPARQL queries.
 *
 * Usage:
 * Query q = GenSPARQLQueryFactory.create(queryString);
 */
public class GenSPARQLQueryFactory {

    /**
     * Create a GenSPARQL query from a query string.
     *
     * @param queryString the GenSPARQL query string
     * @return the parsed Query object
     */
    public static Query create(String queryString) {
        return GenSPARQLParser.parse(queryString);
    }

    /**
     * Create a GenSPARQL query with a base URI.
     *
     * @param queryString the GenSPARQL query string
     * @param baseURI the base URI for relative references
     * @return the parsed Query object
     */
    public static Query create(String queryString, String baseURI) {
        return GenSPARQLParser.parse(queryString, baseURI);
    }

    // Prevent instantiation
    private GenSPARQLQueryFactory() {}
}
