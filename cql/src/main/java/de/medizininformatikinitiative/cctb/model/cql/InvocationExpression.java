package de.medizininformatikinitiative.cctb.model.cql;

import de.medizininformatikinitiative.cctb.PrintContext;

import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * @author Alexander Kiel
 */
public record InvocationExpression(Expression<?> expression, String invocation) implements DefaultExpression {

    /**
     * Higher than every other operator's precedence in this package (the previous highest, {@code
     * AdditionExpressionTerm}, is 16) - accessor ({@code .member}) binds tighter than any of them, so a lower-
     * precedence base (e.g. a {@link TypeExpression} cast, as in {@code (x as Period).start}) must be forced to
     * parenthesize itself rather than printing bare.
     */
    public static final int PRECEDENCE = 20;

    public InvocationExpression {
        requireNonNull(expression);
        requireNonNull(invocation);
    }

    public static InvocationExpression of(Expression<?> expression, String invocation) {
        return new InvocationExpression(expression, invocation);
    }

    @Override
    public String print(PrintContext printContext) {
        return printContext.parenthesize(PRECEDENCE, "%s.%s".formatted(
                expression.print(printContext.withPrecedence(PRECEDENCE)), invocation));
    }

    @Override
    public DefaultExpression withIncrementedSuffixes(Map<String, Integer> increments) {
        return new InvocationExpression(expression.withIncrementedSuffixes(suffixes()), invocation);
    }
}
