package de.medizininformatikinitiative.cctb.model.cql;

import de.medizininformatikinitiative.cctb.PrintContext;

import java.math.BigDecimal;

import static java.util.Objects.requireNonNull;

public record QuantityExpression(BigDecimal value, String unit, boolean unquoted) implements DefaultExpression {

    public QuantityExpression {
        requireNonNull(value);
    }

    public static QuantityExpression of(BigDecimal value) {
        return new QuantityExpression(value, null, false);
    }

    public static QuantityExpression of(BigDecimal value, String unit) {
        return new QuantityExpression(value, requireNonNull(unit), false);
    }

    /**
     * Returns a {@code QuantityExpression} that prints {@code unit} as an unquoted CQL calendar duration keyword
     * (e.g. {@code 3 hours}) instead of a quoted UCUM unit (e.g. {@code 3 'h'}), as used for {@code DateTime +
     * Quantity} arithmetic.
     */
    public static QuantityExpression ofCalendarDuration(BigDecimal value, String unit) {
        return new QuantityExpression(value, requireNonNull(unit), true);
    }

    @Override
    public String print(PrintContext printContext) {
        if (unit == null) {
            return value.toString();
        }
        return unquoted ? "%s %s".formatted(value, unit) : "%s '%s'".formatted(value, unit.replace("'", "\\'"));
    }
}
