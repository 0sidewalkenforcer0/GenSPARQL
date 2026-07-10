package org.gensparql.core.exception;

/**
 * Exception for GenSPARQL parsing errors.
 */
public class ParseException extends GenSPARQLException {

    private final int line;
    private final int column;
    private final String query;

    public ParseException(String message) {
        this(message, -1, -1, null);
    }

    public ParseException(String message, int line, int column) {
        this(message, line, column, null);
    }

    public ParseException(String message, int line, int column, String query) {
        super(formatMessage(message, line, column));
        this.line = line;
        this.column = column;
        this.query = query;
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
        this.line = -1;
        this.column = -1;
        this.query = null;
    }

    private static String formatMessage(String message, int line, int column) {
        if (line >= 0 && column >= 0) {
            return String.format("%s (line %d, column %d)", message, line, column);
        } else if (line >= 0) {
            return String.format("%s (line %d)", message, line);
        }
        return message;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public String getQuery() {
        return query;
    }

    public boolean hasLocation() {
        return line >= 0;
    }
}
