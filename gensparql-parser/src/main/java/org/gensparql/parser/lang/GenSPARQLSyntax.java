package org.gensparql.parser.lang;

import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.serializer.GenSPARQLQuerySerializerFactory;
import org.apache.jena.sparql.serializer.SerializerRegistry;

/**
 * The GenSPARQL query syntax: SPARQL 1.1 plus GENOP.
 *
 * <p>Parsed queries carry this syntax rather than {@code Syntax.syntaxSPARQL_11} so that
 * {@code Query.toString()} routes through a serializer that can write GENOP back out. Tagging
 * the query instead of overriding the SPARQL 1.1 serializer keeps the change local: an
 * application that also uses plain Jena sees no difference in how its own queries serialize.
 *
 * <p>Touching this class installs the serializer, so any code path that reaches a GenSPARQL
 * query has already registered what that query needs in order to serialize itself.
 */
public final class GenSPARQLSyntax extends Syntax {

    /** URI identifying SPARQL 1.1 + GENOP. */
    public static final String URI = "http://gensparql.org/syntax#GenSPARQL";

    /**
     * Syntax identifier for SPARQL 1.1 + GENOP.
     *
     * <p>Built directly rather than via {@code Syntax.make}, which only resolves the syntaxes
     * Jena ships with and returns null for anything else.
     */
    public static final Syntax syntaxGenSPARQL = new GenSPARQLSyntax(URI);

    static {
        install();
    }

    private GenSPARQLSyntax(String uri) {
        super(uri);
    }

    /**
     * Register the GenSPARQL query serializer. Idempotent, and safe to call more than once.
     */
    public static synchronized void install() {
        if (!SerializerRegistry.get().containsQuerySerializer(syntaxGenSPARQL)) {
            SerializerRegistry.get().addQuerySerializer(
                    syntaxGenSPARQL, new GenSPARQLQuerySerializerFactory(syntaxGenSPARQL));
        }
    }
}
