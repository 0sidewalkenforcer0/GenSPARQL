package org.gensparql.parser.fmt;

import org.apache.jena.sparql.syntax.*;
import org.gensparql.parser.element.ElementGenerate;

/**
 * Custom element formatter that handles GenSPARQL elements like GENOP.
 *
 * This formatter properly displays ElementGenerate in query pattern trees,
 * which the standard Jena formatter cannot handle.
 */
public class GenSPARQLFormatterElement {

    private final StringBuilder out;
    private int indentLevel = 0;
    private static final String INDENT = "  ";

    public GenSPARQLFormatterElement() {
        this.out = new StringBuilder();
    }

    /**
     * Format an Element to string, handling GenSPARQL extensions.
     */
    public static String asString(Element el) {
        if (el == null) {
            return "{ }";
        }
        GenSPARQLFormatterElement fmt = new GenSPARQLFormatterElement();
        fmt.format(el);
        return fmt.out.toString();
    }

    /**
     * Format an element with proper handling for ElementGenerate.
     */
    public void format(Element el) {
        if (el instanceof ElementGenerate) {
            formatGenerate((ElementGenerate) el);
        } else if (el instanceof ElementGroup) {
            formatGroup((ElementGroup) el);
        } else if (el instanceof ElementOptional) {
            formatOptional((ElementOptional) el);
        } else if (el instanceof ElementUnion) {
            formatUnion((ElementUnion) el);
        } else if (el instanceof ElementFilter) {
            formatFilter((ElementFilter) el);
        } else if (el instanceof ElementNamedGraph) {
            formatNamedGraph((ElementNamedGraph) el);
        } else if (el instanceof ElementService) {
            formatService((ElementService) el);
        } else if (el instanceof ElementSubQuery) {
            formatSubQuery((ElementSubQuery) el);
        } else if (el instanceof ElementPathBlock) {
            formatPathBlock((ElementPathBlock) el);
        } else if (el instanceof ElementTriplesBlock) {
            formatTriplesBlock((ElementTriplesBlock) el);
        } else if (el instanceof ElementBind) {
            formatBind((ElementBind) el);
        } else if (el instanceof ElementMinus) {
            formatMinus((ElementMinus) el);
        } else {
            // Fallback for unknown elements
            out.append(el.toString());
        }
    }

    private void formatGenerate(ElementGenerate gen) {
        out.append(gen.toGenOpSyntax());
    }

    private void formatGroup(ElementGroup group) {
        out.append("{");
        indentLevel++;

        boolean first = true;
        for (Element el : group.getElements()) {
            if (!first) {
                out.append(" .");
            }
            first = false;
            newLine();
            format(el);
        }

        indentLevel--;
        newLine();
        out.append("}");
    }

    private void formatOptional(ElementOptional opt) {
        out.append("OPTIONAL ");
        format(opt.getOptionalElement());
    }

    private void formatUnion(ElementUnion union) {
        boolean first = true;
        for (Element el : union.getElements()) {
            if (!first) {
                newLine();
                out.append("UNION ");
            }
            first = false;
            format(el);
        }
    }

    private void formatFilter(ElementFilter filter) {
        out.append("FILTER(").append(filter.getExpr()).append(")");
    }

    private void formatNamedGraph(ElementNamedGraph ng) {
        out.append("GRAPH ").append(ng.getGraphNameNode()).append(" ");
        format(ng.getElement());
    }

    private void formatService(ElementService service) {
        out.append("SERVICE ");
        if (service.getSilent()) {
            out.append("SILENT ");
        }
        out.append(service.getServiceNode()).append(" ");
        format(service.getElement());
    }

    private void formatSubQuery(ElementSubQuery subQuery) {
        out.append("{ ");
        out.append(subQuery.getQuery().toString());
        out.append(" }");
    }

    private void formatPathBlock(ElementPathBlock block) {
        boolean first = true;
        for (var tp : block.getPattern()) {
            if (!first) {
                out.append(" .");
                newLine();
            }
            first = false;
            out.append(tp.getSubject()).append(" ");
            out.append(tp.getPath()).append(" ");
            out.append(tp.getObject());
        }
    }

    private void formatTriplesBlock(ElementTriplesBlock block) {
        boolean first = true;
        for (var triple : block.getPattern()) {
            if (!first) {
                out.append(" .");
                newLine();
            }
            first = false;
            out.append(triple.getSubject()).append(" ");
            out.append(triple.getPredicate()).append(" ");
            out.append(triple.getObject());
        }
    }

    private void formatBind(ElementBind bind) {
        out.append("BIND(").append(bind.getExpr()).append(" AS ?").append(bind.getVar().getName()).append(")");
    }

    private void formatMinus(ElementMinus minus) {
        out.append("MINUS ");
        format(minus.getMinusElement());
    }

    private void newLine() {
        out.append("\n");
        for (int i = 0; i < indentLevel; i++) {
            out.append(INDENT);
        }
    }
}
