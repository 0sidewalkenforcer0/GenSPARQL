package org.gensparql.engine.exec;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.expr.Expr;
import org.apache.jena.sparql.expr.ExprFunction;
import org.apache.jena.sparql.expr.ExprFunctionOp;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.ExprVars;
import org.apache.jena.sparql.syntax.*;
import org.gensparql.engine.GenSPARQLConfig;
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
        } else if (element instanceof ElementNamedGraph) {
            return compileNamedGraph((ElementNamedGraph) element);
        } else if (element instanceof ElementData) {
            return compileData((ElementData) element);
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

        // A filter that reads only the patterns placed before GENOP runs before GENOP, so the
        // LLM never receives the solutions it removes (see earlyFilters).
        Set<Element> early = elements == group.getElements()
                ? Collections.emptySet() : earlyFilters(elements);
        boolean earlyApplied = early.isEmpty();
        for (Element e : elements) {
            if (e instanceof ElementFilter) {
                if (!early.contains(e)) {
                    filters.add(((ElementFilter) e).getExpr());
                }
            } else {
                if (!earlyApplied && generates(e)) {
                    ExprList before = new ExprList();
                    for (Element f : early) {
                        before.add(((ElementFilter) f).getExpr());
                    }
                    current = OpFilter.filterBy(before, current);
                    earlyApplied = true;
                }
                current = accumulate(current, e);
            }
        }

        if (!filters.isEmpty()) {
            current = OpFilter.filterBy(filters, current);
        }
        return current;
    }

    /**
     * Reorder a group's elements so each ElementGenerate runs after every triple or path
     * pattern that does not mention its output variables. Such a pattern belongs to the pattern
     * P of the GENOP, and the LLM then receives only the solutions that P selects. The unit is
     * the single pattern, not the block of consecutive patterns: a block that binds a
     * placeholder variable and also mentions an output variable is split, so the GENOP still
     * receives its input. The patterns that mention an output variable form one block after the
     * GENOP operators, which is joined with their solutions. Applied only to "simple" groups
     * (triple/path blocks, GENOP, FILTER); any OPTIONAL/UNION/MINUS/BIND/etc. leaves the order
     * untouched.
     */
    private static List<Element> reorderGenopLast(List<Element> original) {
        // Switched off (gensparql.planner.reorder=false), the query keeps the order in
        // which it is written, and no filter moves before GENOP either.
        if (!GenSPARQLConfig.isGenopReorderEnabled()) {
            return original;
        }
        // With cost-based planning on, leave the author's order alone. Moving GENOP to the end
        // here makes the surrounding patterns adjacent, and adjacent patterns get merged into a
        // single BGP downstream; once that happens the boundary between a selective pattern and
        // a fan-out pattern is gone and no later stage can place the GENOP between them.
        if (GenSPARQLConfig.isCostBasedPlanningEnabled()) {
            return original;
        }

        boolean hasGenop = false, complex = false;
        for (Element e : original) {
            if (e instanceof ElementGenerate) { hasGenop = true; }
            else if (!(e instanceof ElementFilter || e instanceof ElementTriplesBlock
                       || e instanceof ElementPathBlock)) {
                complex = true;
            }
        }
        if (!hasGenop) {
            return original;
        }
        if (complex) {
            return movePatternsBeforeFirstGenop(original);
        }
        // The GENOP operators keep their written order, and each later one has the earlier ones
        // in its pattern P. A pattern that mentions output variables goes right after the last
        // GENOP whose output it mentions, so the GENOP operators after it receive the join.
        List<ElementGenerate> gens = new ArrayList<>();
        for (Element e : original) {
            if (e instanceof ElementGenerate) {
                gens.add((ElementGenerate) e);
            }
        }
        List<Element> before = new ArrayList<>(), filters = new ArrayList<>();
        List<ElementPathBlock> after = new ArrayList<>();
        for (int i = 0; i < gens.size(); i++) {
            after.add(new ElementPathBlock());
        }
        for (Element e : original) {
            if (e instanceof ElementFilter) {
                filters.add(e);
            } else if (!(e instanceof ElementGenerate)) {
                List<org.apache.jena.sparql.core.TriplePath> patterns = patternsOf(e);
                ElementPathBlock independent = new ElementPathBlock();
                for (org.apache.jena.sparql.core.TriplePath tp : patterns) {
                    int last = -1;
                    for (int i = 0; i < gens.size(); i++) {
                        if (mentionsAny(tp, new HashSet<>(gens.get(i).getOutputVariables()))) {
                            last = i;
                        }
                    }
                    if (last >= 0) {
                        after.get(last).addTriplePath(tp);
                    } else {
                        independent.addTriplePath(tp);
                    }
                }
                if (independent.getPattern().size() == patterns.size()) {
                    before.add(e);   // the block mentions no output variable and stays whole
                } else if (!independent.isEmpty()) {
                    before.add(independent);
                }
            }
        }
        List<Element> ordered = new ArrayList<>(original.size() + gens.size());
        ordered.addAll(before);
        boolean alone = before.isEmpty() && gens.size() == 1;
        for (int i = 0; i < gens.size(); i++) {
            ElementGenerate gen = gens.get(i);
            ElementPathBlock joined = after.get(i);
            if (joined.isEmpty()) {
                ordered.add(gen);
            } else if (gen.isBaseMode() && !alone) {
                // A base-mode GENOP and the patterns that mention its output form a group of
                // their own, whose similarity join maps the generated names to entities before
                // anything else reads them. A group that holds nothing else stays as it is.
                ElementGroup mapped = new ElementGroup();
                mapped.addElement(gen);
                mapped.addElement(joined);
                ordered.add(mapped);
            } else {
                ordered.add(gen);
                ordered.add(joined);
            }
        }
        ordered.addAll(filters);
        return ordered;
    }

    /**
     * A group with OPTIONAL, MINUS, BIND, VALUES or a nested pattern keeps its written order,
     * except that a triple or path pattern written after the first GENOP moves before it when
     * it mentions no output variable and the move leaves the solutions unchanged. A pattern t
     * crosses an element e of the group in between only when
     * <ul>
     *   <li>e is a GENOP of the group, a FILTER or another triple or path pattern;</li>
     *   <li>e is OPTIONAL or MINUS without a GENOP, and every variable that t shares with its
     *       pattern is bound by a triple or path pattern written before e, so the left join
     *       and the difference see the same solutions;</li>
     *   <li>e is BIND, t does not mention its variable, and every variable that t shares with
     *       its expression is bound by a triple or path pattern written before e;</li>
     *   <li>e is VALUES, a group, a UNION, a sub-select or GRAPH without a GENOP, which are
     *       joined with the rest and commute with t.</li>
     * </ul>
     * Any other element stops the move.
     */
    private static List<Element> movePatternsBeforeFirstGenop(List<Element> original) {
        int first = -1;
        Set<Var> genOut = new HashSet<>();
        for (int i = 0; i < original.size(); i++) {
            if (original.get(i) instanceof ElementGenerate) {
                if (first < 0) {
                    first = i;
                }
                genOut.addAll(((ElementGenerate) original.get(i)).getOutputVariables());
            }
        }
        // The variables that the triple and path patterns written before each element bind.
        Set<Var> certain = new HashSet<>();
        Map<Element, Set<Var>> certainBefore = new IdentityHashMap<>();
        for (Element e : original) {
            certainBefore.put(e, new HashSet<>(certain));
            if (e instanceof ElementTriplesBlock || e instanceof ElementPathBlock) {
                certain.addAll(PatternVars.vars(e));
            }
        }
        ElementPathBlock moved = new ElementPathBlock();
        List<Element> tail = new ArrayList<>();
        List<Element> crossed = new ArrayList<>();
        for (int i = first; i < original.size(); i++) {
            Element e = original.get(i);
            if (e instanceof ElementTriplesBlock || e instanceof ElementPathBlock) {
                List<org.apache.jena.sparql.core.TriplePath> patterns = patternsOf(e);
                ElementPathBlock stay = new ElementPathBlock();
                for (org.apache.jena.sparql.core.TriplePath tp : patterns) {
                    if (!mentionsAny(tp, genOut) && crossesAll(tp, crossed, certainBefore)) {
                        moved.addTriplePath(tp);
                    } else {
                        stay.addTriplePath(tp);
                    }
                }
                if (stay.getPattern().size() == patterns.size()) {
                    tail.add(e);
                } else if (!stay.isEmpty()) {
                    tail.add(stay);
                }
            } else {
                tail.add(e);
                crossed.add(e);
            }
        }
        if (moved.isEmpty()) {
            return original;
        }
        List<Element> ordered = new ArrayList<>(original.subList(0, first));
        ordered.add(moved);
        ordered.addAll(tail);
        return ordered;
    }

    /** Whether the pattern can move before every element of the list, see above. */
    private static boolean crossesAll(org.apache.jena.sparql.core.TriplePath tp, List<Element> crossed,
                                      Map<Element, Set<Var>> certainBefore) {
        Set<Var> vars = new HashSet<>();
        for (org.apache.jena.graph.Node n
                : new org.apache.jena.graph.Node[]{tp.getSubject(), tp.getPredicate(), tp.getObject()}) {
            if (n != null && n.isVariable()) {
                vars.add(Var.alloc(n));
            }
        }
        for (Element e : crossed) {
            if (e instanceof ElementGenerate || e instanceof ElementFilter) {
                continue;
            }
            if (elementContainsGenerate(e)) {
                return false;
            }
            Set<Var> shared = new HashSet<>(vars);
            if (e instanceof ElementOptional) {
                shared.retainAll(PatternVars.vars(((ElementOptional) e).getOptionalElement()));
            } else if (e instanceof ElementMinus) {
                shared.retainAll(PatternVars.vars(((ElementMinus) e).getMinusElement()));
            } else if (e instanceof ElementBind) {
                if (vars.contains(((ElementBind) e).getVar())) {
                    return false;
                }
                shared.retainAll(ExprVars.getVarsMentioned(((ElementBind) e).getExpr()));
            } else if (e instanceof ElementData || e instanceof ElementGroup || e instanceof ElementUnion
                    || e instanceof ElementSubQuery || e instanceof ElementNamedGraph) {
                continue;
            } else {
                return false;
            }
            if (!certainBefore.get(e).containsAll(shared)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a GENOP appears anywhere inside the element. */
    private static boolean elementContainsGenerate(Element e) {
        if (e instanceof ElementGenerate) {
            return true;
        }
        if (e instanceof ElementGroup) {
            for (Element inner : ((ElementGroup) e).getElements()) {
                if (elementContainsGenerate(inner)) {
                    return true;
                }
            }
        } else if (e instanceof ElementUnion) {
            for (Element inner : ((ElementUnion) e).getElements()) {
                if (elementContainsGenerate(inner)) {
                    return true;
                }
            }
        } else if (e instanceof ElementOptional) {
            return elementContainsGenerate(((ElementOptional) e).getOptionalElement());
        } else if (e instanceof ElementMinus) {
            return elementContainsGenerate(((ElementMinus) e).getMinusElement());
        } else if (e instanceof ElementNamedGraph) {
            return elementContainsGenerate(((ElementNamedGraph) e).getElement());
        } else if (e instanceof ElementService) {
            return elementContainsGenerate(((ElementService) e).getElement());
        } else if (e instanceof ElementSubQuery) {
            return elementContainsGenerate(((ElementSubQuery) e).getQuery().getQueryPattern());
        }
        return false;
    }

    /** Whether a group element of a reordered group is or contains a GENOP. */
    private static boolean generates(Element e) {
        if (e instanceof ElementGenerate) {
            return true;
        }
        if (e instanceof ElementGroup) {
            for (Element inner : ((ElementGroup) e).getElements()) {
                if (inner instanceof ElementGenerate) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The triple and path patterns of a block, in written order. */
    private static List<org.apache.jena.sparql.core.TriplePath> patternsOf(Element block) {
        List<org.apache.jena.sparql.core.TriplePath> patterns = new ArrayList<>();
        if (block instanceof ElementPathBlock) {
            patterns.addAll(((ElementPathBlock) block).getPattern().getList());
        } else {
            for (org.apache.jena.graph.Triple t : ((ElementTriplesBlock) block).getPattern().getList()) {
                patterns.add(new org.apache.jena.sparql.core.TriplePath(t));
            }
        }
        return patterns;
    }

    /** Whether the pattern mentions one of the variables. A path expression has none. */
    private static boolean mentionsAny(org.apache.jena.sparql.core.TriplePath tp, Set<Var> vars) {
        for (org.apache.jena.graph.Node n
                : new org.apache.jena.graph.Node[]{tp.getSubject(), tp.getPredicate(), tp.getObject()}) {
            if (n != null && n.isVariable() && vars.contains(Var.alloc(n))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The filters of a reordered group that read only variables of the patterns placed before
     * the first GENOP. A filter scopes over the whole group, but those patterns bind each of
     * its variables in every solution, and GENOP and the patterns after it only extend these
     * solutions. The filter therefore keeps the same solutions when it runs before GENOP.
     * A filter with EXISTS or NOT EXISTS reads a graph pattern and stays at the end.
     */
    private static Set<Element> earlyFilters(List<Element> ordered) {
        Set<Var> bound = new HashSet<>();
        for (Element e : ordered) {
            if (generates(e)) {
                break;
            }
            if (e instanceof ElementTriplesBlock || e instanceof ElementPathBlock) {
                bound.addAll(PatternVars.vars(e));
            }
        }
        Set<Element> early = new LinkedHashSet<>();
        for (Element e : ordered) {
            if (e instanceof ElementFilter) {
                Expr expr = ((ElementFilter) e).getExpr();
                if (!readsPattern(expr) && bound.containsAll(ExprVars.getVarsMentioned(expr))) {
                    early.add(e);
                }
            }
        }
        return early;
    }

    private static boolean readsPattern(Expr expr) {
        if (expr instanceof ExprFunctionOp) {
            return true;
        }
        if (expr instanceof ExprFunction) {
            for (Expr arg : ((ExprFunction) expr).getArgs()) {
                if (readsPattern(arg)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Compile a path block.
     *
     * <p>Jena's {@code PathLib.pathToTriples} does the right thing for a mixed block: runs of
     * plain triples become BGPs and each genuine path step becomes an OpPath, sequenced
     * together. Collecting {@code tp.asTriple()} instead would drop every step that is a real
     * path, because that method returns null for those, and the query would then run as if the
     * pattern had never been written.
     */
    private static Op compilePathBlock(ElementPathBlock block) {
        return org.apache.jena.sparql.path.PathLib.pathToTriples(block.getPattern());
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

    /** GRAPH ?g { ... } — the inner pattern evaluated against the named graph. */
    private static Op compileNamedGraph(ElementNamedGraph namedGraph) {
        return new OpGraph(namedGraph.getGraphNameNode(), compileElement(namedGraph.getElement()));
    }

    /** VALUES — an inline table of bindings, joined with the rest of the group. */
    private static Op compileData(ElementData data) {
        return OpTable.create(data.getTable());
    }

    /**
     * A sub-select, compiled by this generator rather than by Jena's.
     *
     * <p>Jena's generator rejects ElementGenerate, so routing the subquery through it meant a
     * GENOP inside a sub-select failed outright. Compiling it here also means the subquery's own
     * modifiers apply, so its LIMIT, ORDER BY and aggregates behave the same inside as out.
     */
    private static Op compileSubQuery(ElementSubQuery subQuery) {
        return compileQuery(subQuery.getQuery());
    }

    /**
     * Compile a whole query: its pattern, then its solution modifiers.
     */
    public static Op compileQuery(Query query) {
        return applyModifiers(query, compile(query.getQueryPattern()));
    }

    /**
     * Apply the solution modifiers on top of a pattern's algebra.
     *
     * <p>The stages follow SPARQL 1.1 §18.2.4/§18.2.5: group, having, the SELECT expressions,
     * order, project, distinct/reduced, then offset/limit. The order is not cosmetic. ORDER BY
     * has to run before projection so a query can sort on a variable it does not select, and
     * DISTINCT has to run after it so it deduplicates the selected columns rather than the wider
     * intermediate rows.
     */
    public static Op applyModifiers(Query query, Op op) {
        if (query.hasGroupBy() || query.hasAggregators()) {
            op = OpGroup.create(op, query.getGroupBy(), query.getAggregators());
        }

        if (query.hasHaving()) {
            for (Expr expr : query.getHavingExprs()) {
                op = OpFilter.filter(expr, op);
            }
        }

        // (expr AS ?v) in the SELECT clause binds before ORDER BY and projection can use it.
        org.apache.jena.sparql.core.VarExprList projectExprs = query.getProject();
        if (projectExprs != null) {
            org.apache.jena.sparql.core.VarExprList extend = new org.apache.jena.sparql.core.VarExprList();
            for (Var v : projectExprs.getVars()) {
                Expr e = projectExprs.getExpr(v);
                if (e == null) {
                    continue;
                }
                if (e instanceof org.apache.jena.sparql.expr.ExprAggregator) {
                    // (COUNT(*) AS ?n) binds ?n to the value the group already computed, which
                    // it left in the aggregator's own variable. Keeping the aggregator
                    // expression here instead would re-evaluate it outside any group, where it
                    // sees no rows and yields the identity — 0 for a count.
                    Var aggVar = ((org.apache.jena.sparql.expr.ExprAggregator) e).getVar();
                    extend.add(v, new org.apache.jena.sparql.expr.ExprVar(aggVar));
                } else {
                    extend.add(v, e);
                }
            }
            if (!extend.isEmpty()) {
                op = OpExtend.create(op, extend);
            }
        }

        if (query.hasOrderBy()) {
            op = new OpOrder(op, query.getOrderBy());
        }

        if (query.isSelectType() && query.getProjectVars() != null && !query.getProjectVars().isEmpty()) {
            op = new OpProject(op, query.getProjectVars());
        }

        if (query.isDistinct()) {
            op = OpDistinct.create(op);
        }
        if (query.isReduced()) {
            op = OpReduced.create(op);
        }

        if (query.hasLimit() || query.hasOffset()) {
            long start = query.hasOffset() ? query.getOffset() : 0;
            long length = query.hasLimit() ? query.getLimit() : Query.NOLIMIT;
            op = new OpSlice(op, start, length);
        }

        return op;
    }
}
