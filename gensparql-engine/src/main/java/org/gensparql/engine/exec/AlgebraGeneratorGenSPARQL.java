package org.gensparql.engine.exec;

import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.BasicPattern;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.Expr;
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
        Op current = OpTable.unit();

        for (Element e : group.getElements()) {
            Op next = compileElement(e);

            if (current instanceof OpTable && ((OpTable) current).isJoinIdentity()) {
                current = next;
            } else if (next instanceof OpFilter) {
                // Filters apply to the accumulated pattern
                OpFilter filter = (OpFilter) next;
                current = OpFilter.filterBy(filter.getExprs(), current);
            } else {
                current = OpJoin.create(current, next);
            }
        }

        return current;
    }

    private static Op compileOptional(ElementOptional optional) {
        Op left = OpTable.unit();  // Placeholder - actual left should come from context
        Op right = compileElement(optional.getOptionalElement());
        // Use explicit null cast to resolve ambiguity
        return OpLeftJoin.create(left, right, (Expr) null);
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
        return OpExtend.create(OpTable.unit(), bind.getVar(), bind.getExpr());
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
        Op left = OpTable.unit();  // Placeholder
        Op right = compileElement(minus.getMinusElement());
        return OpMinus.create(left, right);
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
