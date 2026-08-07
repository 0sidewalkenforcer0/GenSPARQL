package org.gensparql.parser.fmt;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.sparql.serializer.FormatterElement;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.gensparql.parser.element.ElementGenerate;

/**
 * Element formatter that knows how to write {@link ElementGenerate} back out as GENOP syntax.
 *
 * <p>Jena's {@link FormatterElement} implements {@code ElementVisitor}, which has no case for
 * GENOP. {@code ElementGenerate.visit} only dispatches to visitors implementing
 * {@link ElementGenerate.GenSPARQLElementVisitor}, so under the stock formatter the element is
 * skipped and the serialized query silently loses its GENOP. This subclass adds the missing
 * case; every other element type is formatted exactly as Jena would.
 */
public class GenSPARQLElementFormatter extends FormatterElement
        implements ElementGenerate.GenSPARQLElementVisitor {

    public GenSPARQLElementFormatter(IndentedWriter out, SerializationContext context) {
        super(out, context);
    }

    @Override
    public void visit(ElementGenerate el) {
        out.print(el.toGenOpSyntax());
    }
}
