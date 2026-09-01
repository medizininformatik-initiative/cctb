package de.medizininformatikinitiative.cctb.model.structured_query;

import de.medizininformatikinitiative.cctb.model.Mapping;
import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.cql.*;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public record TimeRestrictionModifier(Mapping.TimeRestrictionMapping mapping, LocalDate afterDate,
                                      LocalDate beforeDate) implements SimpleModifier {

    public TimeRestrictionModifier {
        requireNonNull(mapping);
        requireNonNull(afterDate);
        requireNonNull(beforeDate);
    }

    public static TimeRestrictionModifier of(Mapping.TimeRestrictionMapping mapping, LocalDate afterDate, LocalDate beforeDate) {
        return new TimeRestrictionModifier(mapping, afterDate, beforeDate);
    }

    private static DefaultExpression dateExpr(InvocationExpression invocationExpr, IntervalSelector intervalSelector) {
        var castExp = TypeExpression.of(invocationExpr, "date");
        var toDateFunction = FunctionInvocation.of("ToDate", List.of(castExp));
        return MembershipExpression.in(toDateFunction, intervalSelector);
    }

    private static DefaultExpression dateTimeExpr(InvocationExpression invocationExpr, IntervalSelector intervalSelector) {
        var castExp = TypeExpression.of(invocationExpr, "dateTime");
        var toDateFunction = FunctionInvocation.of("ToDate", List.of(castExp));
        return MembershipExpression.in(toDateFunction, intervalSelector);
    }

    private static DefaultExpression instantExpr(InvocationExpression invocationExpr, IntervalSelector intervalSelector) {
        var castExp = TypeExpression.of(invocationExpr, "instant");
        var toDateFunction = FunctionInvocation.of("ToDate", List.of(castExp));
        return MembershipExpression.in(toDateFunction, intervalSelector);
    }

    /**
     * Builds the per-type (DATE/DATE_TIME/INSTANT/PERIOD) membership/overlap check, OR'd across every type
     * {@code mapping} says the target path can have, distributing a window over all of a field's possible FHIR
     * types.
     * <p>
     * Takes a function from type to {@link IntervalSelector} rather than a single shared interval because the
     * literal, absolute restriction ({@link #expression(MappingContext, IdentifierExpression)}) needs
     * differently-typed interval bounds for {@code DATE} (a date-only literal) than for
     * {@code DATE_TIME}/{@code INSTANT}/{@code PERIOD} (a dateTime literal). {@link RelativeTimeRestrictionModifier}
     * (the other caller) always distributes the same computed, dateTime-valued interval regardless of type.
     * <p>
     * This is a mechanical extraction of the switch that used to live directly in {@link #expression} - behavior
     * for the literal, absolute restriction path is unchanged.
     */
    static DefaultExpression distribute(Mapping.TimeRestrictionMapping mapping, InvocationExpression invocationExpr,
                                        Function<Mapping.TimeRestrictionMapping.Type, IntervalSelector> intervalSelectorForType) {
        //noinspection OptionalGetWithoutIsPresent
        return mapping.types().stream().sorted().map(type -> switch (type) {
            case DATE -> dateExpr(invocationExpr, intervalSelectorForType.apply(type));
            case DATE_TIME -> dateTimeExpr(invocationExpr, intervalSelectorForType.apply(type));
            case INSTANT -> instantExpr(invocationExpr, intervalSelectorForType.apply(type));
            case PERIOD -> OverlapsIntervalOperatorPhrase.of(invocationExpr, intervalSelectorForType.apply(type));
        }).reduce(OrExpression::of).get();
    }

    @Override
    public Container<DefaultExpression> expression(MappingContext mappingContext, IdentifierExpression sourceAlias) {
        var invocationExpr = InvocationExpression.of(sourceAlias, mapping.path());
        var dateInterval = IntervalSelector.of(DateExpression.of(afterDate), DateExpression.of(beforeDate));
        var dateTimeInterval = IntervalSelector.of(DateTimeExpression.of(afterDate), DateTimeExpression.of(beforeDate));

        return Container.of(distribute(mapping, invocationExpr,
                type -> type == Mapping.TimeRestrictionMapping.Type.DATE ? dateInterval : dateTimeInterval));
    }
}
