package org.gensparql.parser.lang;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementGroup;
import org.apache.jena.sparql.syntax.ElementMinus;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.sparql.syntax.ElementOptional;
import org.apache.jena.sparql.syntax.ElementService;
import org.apache.jena.sparql.syntax.ElementSubQuery;
import org.apache.jena.sparql.syntax.ElementUnion;
import org.apache.jena.sparql.syntax.PatternVars;
import org.gensparql.core.exception.ParseException;
import org.gensparql.parser.element.ElementGenerate;
import org.gensparql.parser.javacc.GenSPARQLParserImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        return parse(queryString, baseURI, true);
    }

    /**
     * @param checkPrompts whether prompt placeholders must name variables the query binds. A
     *                     standalone GENOP fragment has no surrounding pattern to bind them, so
     *                     the check applies to whole queries only.
     */
    private static Query parse(String queryString, String baseURI, boolean checkPrompts) {
        LOG.debug("Parsing GenSPARQL query:\n{}", queryString);

        try {
            Query query = GenSPARQLParserImpl.parse(queryString, baseURI);
            expandStarWithGeneratedVars(query);
            if (checkPrompts) {
                checkPromptVariables(query);
            }
            LOG.debug("Successfully parsed GenSPARQL query");
            return query;
        } catch (org.gensparql.parser.javacc.ParseException e) {
            throw new ParseException("Failed to parse GenSPARQL query: " + e.getMessage(), e);
        }
    }

    /**
     * Reject a prompt placeholder naming a variable the query never mentions.
     *
     * <p>A GENOP's input variables are read out of the prompt string, so the grammar cannot
     * check them: {@code GENOP("position of {?nmae}", ...)} parses. At execution the variable is
     * never bound, every row is skipped, and the query returns an empty result having issued no
     * calls, which looks exactly like a query whose pattern matched nothing.
     *
     * <p>The test is deliberately weak: the variable has to be absent from the entire pattern,
     * not merely out of scope at that point. A variable that appears somewhere might still be
     * bound at run time — one bound inside OPTIONAL, for instance — and rejecting those would
     * turn working queries into errors. A variable that appears nowhere cannot ever be bound, so
     * rejecting it cannot take away a query that would have worked.
     */
    private static void checkPromptVariables(Query query) {
        Element pattern = query.getQueryPattern();
        if (pattern == null) {
            return;
        }
        Set<String> mentioned = new LinkedHashSet<>();
        collectMentionedVars(pattern, mentioned);

        List<ElementGenerate> genOps = new ArrayList<>();
        collectGenOps(pattern, genOps);

        for (ElementGenerate genOp : genOps) {
            for (Var in : genOp.getInputVariables()) {
                if (!mentioned.contains(in.getVarName())) {
                    throw new ParseException(
                            "GENOP prompt refers to {?" + in.getVarName() + "}, which the query "
                          + "never binds. Variables in this query: " + mentioned);
                }
            }
        }
    }

    /** Every variable the pattern mentions, GENOP inputs and outputs included. */
    private static void collectMentionedVars(Element element, Set<String> out) {
        if (element instanceof ElementGenerate) {
            ElementGenerate genOp = (ElementGenerate) element;
            genOp.getOutputVariables().forEach(v -> out.add(v.getVarName()));
            return; // its inputs are what we are checking; they do not count as bindings
        }
        if (element instanceof ElementSubQuery) {
            Query sub = ((ElementSubQuery) element).getQuery();
            sub.getResultVars().forEach(out::add);
            if (sub.getQueryPattern() != null) {
                collectMentionedVars(sub.getQueryPattern(), out);
            }
            return;
        }
        PatternVars.vars(element).forEach(v -> out.add(v.getVarName()));
        forEachChild(element, child -> collectMentionedVars(child, out));
    }

    /** Every GENOP in the pattern, at any depth. */
    private static void collectGenOps(Element element, List<ElementGenerate> out) {
        if (element instanceof ElementGenerate) {
            out.add((ElementGenerate) element);
            return;
        }
        if (element instanceof ElementSubQuery) {
            Element sub = ((ElementSubQuery) element).getQuery().getQueryPattern();
            if (sub != null) {
                collectGenOps(sub, out);
            }
            return;
        }
        forEachChild(element, child -> collectGenOps(child, out));
    }

    /** Visit the nested patterns of a group-like element. */
    private static void forEachChild(Element element, java.util.function.Consumer<Element> visit) {
        if (element instanceof ElementGroup) {
            ((ElementGroup) element).getElements().forEach(visit);
        } else if (element instanceof ElementUnion) {
            ((ElementUnion) element).getElements().forEach(visit);
        } else if (element instanceof ElementOptional) {
            visit.accept(((ElementOptional) element).getOptionalElement());
        } else if (element instanceof ElementMinus) {
            visit.accept(((ElementMinus) element).getMinusElement());
        } else if (element instanceof ElementNamedGraph) {
            visit.accept(((ElementNamedGraph) element).getElement());
        } else if (element instanceof ElementService) {
            visit.accept(((ElementService) element).getElement());
        }
    }

    /**
     * Make {@code SELECT *} include the variables a GENOP binds.
     *
     * <p>Jena expands the star from the variables it can find in the pattern, and it finds them
     * by walking the syntax tree with a collector it wraps in an ElementWalker. The walker is
     * what reaches an element, so ElementGenerate cannot report its variables through that
     * route, and the generated columns were computed but never projected: {@code SELECT *}
     * returned every row with the generated value missing while naming it explicitly worked.
     *
     * <p>Expanding the star first and appending afterwards keeps the ordinary pattern variables
     * in their usual position, with the generated ones after them.
     */
    private static void expandStarWithGeneratedVars(Query query) {
        if (!query.isQueryResultStar()) {
            return;
        }
        List<Var> generated = new ArrayList<>();
        collectGeneratedVars(query.getQueryPattern(), generated);
        if (generated.isEmpty()) {
            return;
        }
        query.setResultVars();
        for (Var v : generated) {
            if (!query.getResultVars().contains(v.getVarName())) {
                query.addResultVar(v);
            }
        }
    }

    /**
     * Collect the output variables of every GENOP that is in scope for the enclosing group.
     *
     * <p>Scoping follows SPARQL: OPTIONAL, UNION, GRAPH and SERVICE contribute their variables,
     * while the right side of MINUS and the body of EXISTS/NOT EXISTS do not. A sub-select
     * exposes only what it projects, which Jena already accounts for.
     */
    private static void collectGeneratedVars(Element element, List<Var> out) {
        if (element instanceof ElementGenerate) {
            out.addAll(((ElementGenerate) element).getOutputVariables());
        } else if (element instanceof ElementGroup) {
            for (Element e : ((ElementGroup) element).getElements()) {
                collectGeneratedVars(e, out);
            }
        } else if (element instanceof ElementOptional) {
            collectGeneratedVars(((ElementOptional) element).getOptionalElement(), out);
        } else if (element instanceof ElementUnion) {
            for (Element e : ((ElementUnion) element).getElements()) {
                collectGeneratedVars(e, out);
            }
        } else if (element instanceof ElementNamedGraph) {
            collectGeneratedVars(((ElementNamedGraph) element).getElement(), out);
        } else if (element instanceof ElementService) {
            collectGeneratedVars(((ElementService) element).getElement(), out);
        }
    }

    /**
     * Parse a standalone GENOP function call.
     *
     * <p>The fragment is parsed by the same grammar that parses whole queries, by wrapping it
     * in a minimal query and lifting the element back out. A separate fragment grammar would be
     * a second definition of what GENOP accepts, and the two would drift.
     *
     * @param genopText the GENOP function text
     * @return parsed ElementGenerate
     * @throws ParseException if the text is not exactly one GENOP
     */
    public static ElementGenerate parseGenOp(String genopText) {
        Query wrapper = parse("SELECT * WHERE { " + genopText + " }", null, false);

        Element pattern = wrapper.getQueryPattern();
        if (pattern instanceof ElementGroup group
                && group.size() == 1
                && group.get(0) instanceof ElementGenerate genOp) {
            return genOp;
        }
        // Trailing patterns would otherwise be accepted and silently discarded.
        throw new ParseException("Expected exactly one GENOP, got: " + genopText);
    }
}
