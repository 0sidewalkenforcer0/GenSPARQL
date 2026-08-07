package org.gensparql.test;

import org.apache.jena.sparql.algebra.OpVars;
import org.apache.jena.sparql.core.Var;

import java.util.Set;
import org.gensparql.engine.op.OpGenerate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a GENOP tells plan analysis about its variables.
 *
 * <p>ARQ asks an extension operator for an equivalent plain-SPARQL shape and reads the variables
 * off that. OpGenerate answered with a unit table, so every caller was told a GENOP neither reads
 * nor binds anything: the operator was invisible to variable analysis, and a plan could be
 * rewritten as though the patterns feeding it were unused.
 */
@DisplayName("GENOP variable analysis")
public class OpGenerateVarsTest {

    private static OpGenerate genOp(String prompt, String... outputs) {
        OpGenerate.Builder b = OpGenerate.builder()
                .promptTemplate(prompt)
                .modelURI("model:mock:t");
        for (String out : outputs) {
            b.addOutputVariable(out);
        }
        return b.build();
    }

    @Test
    @DisplayName("the output variables are reported as bound")
    void outputsAreBound() {
        assertEquals(Set.of(Var.alloc("g")),
                OpVars.fixedVars(genOp("say {?l}", "g")));
    }

    @Test
    @DisplayName("every prompt variable is reported as read")
    void inputsAreMentioned() {
        assertEquals(Set.of(Var.alloc("l"), Var.alloc("m")),
                OpVars.mentionedVars(genOp("say {?l} and {?m}", "g")),
                "a pattern binding ?l or ?m is needed by this operator and must not look unused");
    }

    @Test
    @DisplayName("several output variables are all reported")
    void multipleOutputs() {
        assertEquals(Set.of(Var.alloc("a"), Var.alloc("b")),
                OpVars.fixedVars(genOp("split {?l}", "a", "b")));
    }

    @Test
    @DisplayName("base mode reads nothing and still binds its output")
    void baseMode() {
        OpGenerate op = genOp("list the teams", "t");

        assertTrue(op.isBaseMode());
        assertEquals(Set.of(Var.alloc("t")), OpVars.fixedVars(op));
        assertTrue(OpVars.mentionedVars(op).isEmpty(),
                "a constant prompt reads no variables");
    }

    @Test
    @DisplayName("the reported shape binds and reads, rather than being empty")
    void effectiveOpShape() {
        String shape = genOp("say {?l}", "g").effectiveOp().toString();

        assertTrue(shape.contains("?g"), "must show the variable it binds: " + shape);
        assertTrue(shape.contains("?l"), "must show the variable it reads: " + shape);
    }
}
