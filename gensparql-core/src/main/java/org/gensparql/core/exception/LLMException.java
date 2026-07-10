package org.gensparql.core.exception;

/**
 * Exception for LLM-related errors.
 */
public class LLMException extends GenSPARQLException {

    private final String provider;
    private final String model;
    private final int statusCode;

    public LLMException(String message) {
        this(message, null, null, -1);
    }

    public LLMException(String message, Throwable cause) {
        this(message, null, null, -1, cause);
    }

    public LLMException(String message, String provider, String model, int statusCode) {
        super(message);
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
    }

    public LLMException(String message, String provider, String model, int statusCode, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.model = model;
        this.statusCode = statusCode;
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRateLimitError() {
        return statusCode == 429;
    }

    public boolean isAuthenticationError() {
        return statusCode == 401 || statusCode == 403;
    }

    public boolean isServerError() {
        return statusCode >= 500;
    }

    public boolean isRetryable() {
        return isRateLimitError() || isServerError();
    }
}
