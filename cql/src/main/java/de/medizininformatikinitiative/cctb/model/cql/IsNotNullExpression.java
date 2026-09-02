package de.medizininformatikinitiative.cctb.model.cql;

import de.medizininformatikinitiative.cctb.PrintContext;

import java.util.Map;

import static java.util.Objects.requireNonNull;

public record IsNotNullExpression(Expression<?> expression) implements DefaultExpression {

    public static final int PRECEDENCE = 6;

    public IsNotNullExpression {
        requireNonNull(expression);
    }

    public static DefaultExpression of(Expression<?> expression) {
        return new IsNotNullExpression(expression);
    }

    @Override
    public String print(PrintContext printContext) {
        var operatorPrintContext = printContext.withPrecedence(PRECEDENCE);
        return printContext.parenthesize(PRECEDENCE, "%s is not null".formatted(expression.print(operatorPrintContext)));
    }

    @Override
    public DefaultExpression withIncrementedSuffixes(Map<String, Integer> increments) {
        return new IsNotNullExpression(expression.withIncrementedSuffixes(increments));
    }
}
