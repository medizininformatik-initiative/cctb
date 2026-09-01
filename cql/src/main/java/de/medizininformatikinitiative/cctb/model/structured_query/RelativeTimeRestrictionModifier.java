package de.medizininformatikinitiative.cctb.model.structured_query;

import de.medizininformatikinitiative.cctb.model.Mapping;
import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.cql.*;

import static java.util.Objects.requireNonNull;

/**
 * Applies an already-computed {@link IntervalSelector} (an anchor date plus/minus an offset, see
 * {@link Group#toCql}) as a per-criterion time restriction, reusing {@link TimeRestrictionModifier#distribute} -
 * the same per-type (DATE/DATE_TIME/INSTANT/PERIOD) distribution mechanism the literal, absolute
 * {@link TimeRestrictionModifier} uses, just fed a computed interval instead of a literal one.
 */
public record RelativeTimeRestrictionModifier(Mapping.TimeRestrictionMapping mapping,
                                               IntervalSelector intervalSelector) implements SimpleModifier {

    public RelativeTimeRestrictionModifier {
        requireNonNull(mapping);
        requireNonNull(intervalSelector);
    }

    public static RelativeTimeRestrictionModifier of(Mapping.TimeRestrictionMapping mapping, IntervalSelector intervalSelector) {
        return new RelativeTimeRestrictionModifier(mapping, intervalSelector);
    }

    @Override
    public Container<DefaultExpression> expression(MappingContext mappingContext, IdentifierExpression sourceAlias) {
        var invocationExpr = InvocationExpression.of(sourceAlias, mapping.path());
        return Container.of(TimeRestrictionModifier.distribute(mapping, invocationExpr, type -> intervalSelector));
    }
}
