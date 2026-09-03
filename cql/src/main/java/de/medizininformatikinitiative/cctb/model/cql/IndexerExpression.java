package de.medizininformatikinitiative.cctb.model.cql;

import de.medizininformatikinitiative.cctb.PrintContext;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * {@code expression[index]} - CQL's indexer operator, binding as tight as {@link InvocationExpression}'s member
 * access (same postfix precedence tier).
 */
public record IndexerExpression(Expression<?> expression, int index) implements DefaultExpression {

    public static final int PRECEDENCE = InvocationExpression.PRECEDENCE;

    public IndexerExpression {
        requireNonNull(expression);
    }

    public static IndexerExpression of(Expression<?> expression, int index) {
        return new IndexerExpression(expression, index);
    }

    @Override
    public String print(PrintContext printContext) {
        return printContext.parenthesize(PRECEDENCE, "%s[%d]".formatted(
                expression.print(printContext.withPrecedence(PRECEDENCE)), index));
    }

    @Override
    public DefaultExpression withIncrementedSuffixes(Map<String, Integer> increments) {
        return new IndexerExpression(expression.withIncrementedSuffixes(increments), index);
    }
}
