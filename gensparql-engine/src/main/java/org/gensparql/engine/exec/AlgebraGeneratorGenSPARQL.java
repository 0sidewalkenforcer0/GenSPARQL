package org.gensparql.engine.exec;

import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.syntax.*;
import org.gensparql.engine.op.OpGenerate;
import org.gensparql.parser.element.ElementGenerate;

import java.util.*;

/**
 * Converts GenSPARQL Element trees to Algebra Op trees.
 *
 * Extends standard SPARQL algebra generation to handle ElementGenerate.
 */
public class AlgebraGeneratorGenSPARQL {

    /**
     * Convert a query pattern Element to an algebra Op.
     */
    public static Op compile(Element element) {
        if (element == null) {
            return OpTable.unit();
        }
        return compileElement(element);
    }

    private static Op compileElement(Element element) {
        if (element instanceof ElementGenerate) {
            return compileGenerate((ElementGenerate) element);
        } else if (element instanceof ElementGroup) {
            return compileGroup((ElementGroup) element);
        } else if (element instanceof ElementOptional) {
            return compileOptional((ElementOptional) element);
        } else if (element instanceof ElementUnion) {
            return compileUnion((ElementUnion) element);
        } else if (element instanceof ElementFilter) {
            return compileFilter((ElementFilter) element);
        } else if (element instanceof ElementBind) {
            return compileBind((ElementBind) element);
        } else if (element instanceof ElementPathBlock) {
            return compilePathBlock((ElementPathBlock) element);
        } else if (element instanceof ElementTriplesBlock) {
            return compileTriplesBlock((ElementTriplesBlock) element);
        } else if (element instanceof ElementMinus) {
            return compileMinus((ElementMinus) element);
        } else if (element instanceof ElementService) {
            return compileService((ElementService) element);
        } else if (element instanceof ElementSubQuery) {
            return compileSubQuery((ElementSubQuery) element);
        }

        // Fallback - shouldn't happen for valid queries
        throw new UnsupportedOperationException("Unknown element type: " + element.getClass());
    }

    private static Op compileGenerate(ElementGenerate element) {
        return OpGenerate.fromElement(element);
    }

    private static Op compileGroup(ElementGroup group) {
        // A FILTER scopes over the whole group graph pattern, not just the
        // elements that precede it, so collect filters and apply them once over
        // the fully-accumulated pattern (SPARQL 1.1 algebra semantics).
        ExprList filters = new ExprList();
        Op current = OpTable.unit();

        // Cost-based placement of GENOP: because context-mode GENOP issues one LLM call per
        // input binding, we push it AFTER every KG pattern that constrains its inputs (and does
        // not depend on its outputs), so the LLM fires only on the already-filtered bindings.
        // Applied only to "simple" groups (plain BGP/path + GENOP + FILTER) where basic-pattern
        // joins commute, so the rewrite is semantics-preserving.
        List<Element> elements = reorderGenopLast(group.getElements());

        for (Element e : elements) {
            if (e instanceof ElementFilter) {
                filters.add(((ElementFilter) e).getExpr());
            } else {
                current = accumulate(current, e);
            }
        }

        if (!filters.isEmpty()) {
            current = OpFilter.filterBy(filters, current);
        }
        return current;
    }

    /**
     * Reorder a group's elements so each ElementGenerate runs after every plain KG pattern
     * that does NOT depend on its output variables. Since basic-pattern joins commute, moving
     * GENOP-independent triple/path blocks ahead of GENOP is semantics-preserving, and it means
     * context-mode GENOP fires only on the bindings the KG has already constrained — turning an
     * O(all-entities) fan-out into O(selected). Applied only to "simple" groups (triple/path
     * blocks, GENOP, FILTER); any OPTIONAL/UNION/MINUS/BIND/etc. leaves the order untouched.
     */
    private static List<Element> reorderGenopLast(List<Element> original) {
        boolean hasGenop = false;
        for (Element e : original) {
            if (e instanceof ElementGenerate) { hasGenop = true; }
            else if (!(e instanceof ElementFilter || e instanceof ElementTriplesBlock
                       || e instanceof ElementPathBlock)) {
                return original;   // complex group -> preserve author order
            }
        }
        if (!hasGenop) {
            return original;
        }
        Set<Var> genOut = new HashSet<>();
        for (Element e : original) {
            if (e instanceof ElementGenerate) {
                genOut.addAll(((ElementGenerate) e).getOutputVariables());
            }
        }
        List<Element> before = new ArrayList<>(), gens = new ArrayList<>(),
                after = new ArrayList<>(), filters = new ArrayList<>();
        for (Element e : original) {
            if (e instanceof ElementFilter) {
                filters.add(e);
            } else if (e instanceof ElementGenerate) {
                gens.add(e);
            } else {
                Set<Var> v = new HashSet<>(PatternVars.vars(e));
                v.retainAll(genOut);
                (v.isEmpty() ? before : after).add(e);   // depends on GENOP output -> after
            }
        }
        List<Element> ordered = new ArrayList<>(original.size());
        ordered.addAll(before);
        ordered.addAll(gens);
        ordered.addAll(after);
        ordered.addAll(filters);
        return ordered;
    }

    /**
     * Combine the pattern accumulated so far with the next group element.
     * OPTIONAL/MINUS/BIND must see the accumulated left-hand side rather than a
     * unit placeholder; otherwise OPTIONAL degrades to an inner join (dropping
     * rows with no optional match), MINUS subtracts nothing, and BIND cannot
     * reference previously bound variables.
     */
    private static Op accumulate(Op current, Element e) {
        if (e instanceof ElementOptional) {
            Op right = compileElement(((ElementOptional) e).getOptionalElement());
            // A FILTER inside the OPTIONAL folds into the left-join condition so
            // it can reference variables bound on the required (left) side.
            if (right instanceof OpFilter) {
                OpFilter f = (OpFilter) right;
                return OpLeftJoin.create(current, f.getSubOp(), f.getExprs());
            }
            return OpLeftJoin.create(current, right, (Expr) null);
        }
        if (e instanceof ElementMinus) {
            Op right = compileElement(((ElementMinus) e).getMinusElement());
            return OpMinus.create(current, right);
        }
        if (e instanceof ElementBind) {
            ElementBind b = (ElementBind) e;
            return OpExtend.create(current, b.getVar(), b.getExpr());
        }
        Op next = compileElement(e);
        if (current instanceof OpTable && ((OpTable) current).isJoinIdentity()) {
            return next;
        }
        return OpJoin.create(current, next);
    }

    private static Op compileOptional(ElementOptional optional) {
        // Standalone fallback (OPTIONAL is normally handled within a group, where
        // it can see the accumulated left-hand side via accumulate()).
        return accumulate(OpTable.unit(), optional);
    }

    private static Op compileUnion(ElementUnion union) {
        List<Op> ops = new ArrayList<>();
        for (Element e : union.getElements()) {
            ops.add(compileElement(e));
        }

        if (ops.isEmpty()) {
            return OpTable.empty();
        }

        Op result = ops.get(0);
        for (int i = 1; i < ops.size(); i++) {
            result = OpUnion.create(result, ops.get(i));
        }
        return result;
    }

    private static Op compileFilter(ElementFilter filter) {
        return OpFilter.filter(filter.getExpr(), OpTable.unit());
    }

    private static Op compileBind(ElementBind bind) {
        return accumulate(OpTable.unit(), bind);
    }

    private static Op compilePathBlock(ElementPathBlock block) {
        BasicPattern bgp = new BasicPattern();
        block.getPattern().getList().forEach(tp -> {
            Triple t = tp.asTriple();
            if (t != null) {
                bgp.add(t);
            }
        });
        return new OpBGP(bgp);
    }

    private static Op compileTriplesBlock(ElementTriplesBlock block) {
        return new OpBGP(block.getPattern());
    }

    private static Op compileMinus(ElementMinus minus) {
        return accumulate(OpTable.unit(), minus);
    }

    private static Op compileService(ElementService service) {
        Op subOp = compileElement(service.getElement());
        return new OpService(service.getServiceNode(), subOp, service.getSilent());
    }

    private static Op compileSubQuery(ElementSubQuery subQuery) {
        // In Jena 5.x, compile subquery to algebra and wrap with project
        Query query = subQuery.getQuery();
        Op subOp = Algebra.compile(query);

        // Wrap in project if needed
        List<Var> projectVars = query.getProjectVars();
        if (projectVars != null && !projectVars.isEmpty()) {
            return new OpProject(subOp, projectVars);
        }
        return subOp;
    }
}
