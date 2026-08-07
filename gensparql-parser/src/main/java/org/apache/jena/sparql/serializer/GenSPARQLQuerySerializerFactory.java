package org.apache.jena.sparql.serializer;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.query.QueryVisitor;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.core.Prologue;
import org.gensparql.parser.fmt.GenSPARQLElementFormatter;

/**
 * Serializer factory that writes GenSPARQL queries, GENOP included.
 *
 * <p>It builds Jena's own {@code QuerySerializer} but hands it a
 * {@link GenSPARQLElementFormatter} in place of the stock element formatter, so everything
 * except GENOP is serialized byte-for-byte as Jena would.
 *
 * <p><b>Why this class lives in {@code org.apache.jena.sparql.serializer}.</b> The
 * {@code QuerySerializer} constructors are package-private, and the element formatter is a
 * constructor argument with no public setter, so a factory outside the package cannot supply a
 * replacement. Only this thin factory needs the package; the formatter itself is a normal
 * {@code org.gensparql} class. Note that this makes the package split across two jars, which
 * works on the classpath but not on the JPMS module path.
 */
public class GenSPARQLQuerySerializerFactory implements QuerySerializerFactory {

    private final Syntax accepted;

    /**
     * @param accepted the syntax this factory serializes; held directly so that registering the
     *                 factory does not read back a static field that is still being initialised
     */
    public GenSPARQLQuerySerializerFactory(Syntax accepted) {
        this.accepted = accepted;
    }

    @Override
    public boolean accept(Syntax syntax) {
        return accepted.equals(syntax);
    }

    @Override
    public QueryVisitor create(Syntax syntax, Prologue prologue, IndentedWriter writer) {
        return create(syntax, new SerializationContext(prologue), writer);
    }

    @Override
    public QueryVisitor create(Syntax syntax, SerializationContext context, IndentedWriter writer) {
        return new QuerySerializer(writer,
                new GenSPARQLElementFormatter(writer, context),
                new FmtExprSPARQL(writer, context),
                new FmtTemplate(writer, context));
    }
}
