package de.medizininformatikinitiative.cctb.model.cql;

import de.medizininformatikinitiative.cctb.PrintContext;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * An expression that always prints inside parentheses, regardless of the surrounding precedence.
 * <p>
 * Needed where grouping is structural rather than a matter of operator precedence. The case this exists for is a
 * {@link UnionExpression} used as an aliased query source: {@link QueryExpression#print} resets the precedence to
 * zero before printing its clauses, so {@link UnionExpression#print}'s own {@code parenthesize} call never fires
 * there, and {@code from a union b W} would bind the alias to {@code b} alone instead of to the whole union -
 * which the engine rejects outright. Wrapping the source makes the grouping explicit.
 */
public record ParenthesizedExpression(Expression<?> expression) implements DefaultExpression {

    public ParenthesizedExpression {
        requireNonNull(expression);
    }

    public static DefaultExpression of(Expression<?> expression) {
        return new ParenthesizedExpression(expression);
    }

    @Override
    public String print(PrintContext printContext) {
        return "(%s)".formatted(expression.print(printContext.resetPrecedence()));
    }

    @Override
    public DefaultExpression withIncrementedSuffixes(Map<String, Integer> increments) {
        return new ParenthesizedExpression(expression.withIncrementedSuffixes(increments));
    }
}
