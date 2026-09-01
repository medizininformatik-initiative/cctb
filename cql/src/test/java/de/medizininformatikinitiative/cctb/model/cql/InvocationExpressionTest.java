package de.medizininformatikinitiative.cctb.model.cql;

import de.medizininformatikinitiative.cctb.PrintContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvocationExpressionTest {

    @Test
    void print_IdentifierBase() {
        var cql = InvocationExpression.of(StandardIdentifierExpression.of("O"), "effective").print(PrintContext.ZERO);

        assertEquals("O.effective", cql);
    }

    /**
     * A regression test for a real bug found via external review of generated CQL: accessing a member on a
     * type-cast expression (e.g. reading {@code .start} off a value cast to {@code Period}) must parenthesize the
     * cast, since {@code as} binds more loosely than accessor - printing it bare (as it did before this fix)
     * produces {@code x as Period.start}, which a CQL parser would try to read as casting to a (nonsensical)
     * qualified type named {@code Period.start} rather than the intended {@code (x as Period).start}.
     */
    @Test
    void print_LowerPrecedenceBase() {
        var cast = TypeExpression.of(InvocationExpression.of(StandardIdentifierExpression.of("P"), "performed"), "Period");
        var cql = InvocationExpression.of(cast, "start").print(PrintContext.ZERO);

        assertEquals("(P.performed as Period).start", cql);
    }
}
