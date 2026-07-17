package org.gensparql.engine.cost;

import org.apache.jena.graph.Triple;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.shared.impl.PrefixMappingImpl;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.util.FmtUtils;

import java.util.List;

/**
 * Helpers to turn algebra fragments into KG-statistics queries for the cost planner (C2/C4).
 * Extracts the triples of BGP-only elements and serializes them into a SPARQL WHERE body that
 * {@link KgStats} can count, so the planner can estimate real binding counts and dedup ratios.
 */
public final class OpStats {

    private OpStats() {
    }

    /** Collect all triples from BGP(-only) parts of an algebra op, recursing through wrappers. */
    public static void collectTriples(Op op, List<Triple> out) {
        if (op instanceof OpBGP) {
            out.addAll(((OpBGP) op).getPattern().getList());
        } else if (op instanceof OpProject) {
            collectTriples(((OpProject) op).getSubOp(), out);
        } else if (op instanceof OpFilter) {
            collectTriples(((OpFilter) op).getSubOp(), out);
        } else if (op instanceof OpJoin) {
            collectTriples(((OpJoin) op).getLeft(), out);
            collectTriples(((OpJoin) op).getRight(), out);
        } else if (op instanceof OpSequence) {
            for (Op e : ((OpSequence) op).getElements()) {
                collectTriples(e, out);
            }
        }
    }

    /** Serialize triples into a SPARQL WHERE body with full URIs (no prefix declarations). */
    public static String buildPattern(List<Triple> triples) {
        PrefixMapping pm = new PrefixMappingImpl();
        StringBuilder sb = new StringBuilder();
        for (Triple t : triples) {
            sb.append(FmtUtils.stringForNode(t.getSubject(), pm)).append(' ')
              .append(FmtUtils.stringForNode(t.getPredicate(), pm)).append(' ')
              .append(FmtUtils.stringForNode(t.getObject(), pm)).append(" . ");
        }
        return sb.toString();
    }
}
