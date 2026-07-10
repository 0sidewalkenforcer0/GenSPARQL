package org.gensparql.core.exception;

/**
 * Base exception for GenSPARQL errors.
 */
public class GenSPARQLException extends RuntimeException {

    public GenSPARQLException(String message) {
        super(message);
    }

    public GenSPARQLException(String message, Throwable cause) {
        super(message, cause);
    }

    public GenSPARQLException(Throwable cause) {
        super(cause);
    }
}
