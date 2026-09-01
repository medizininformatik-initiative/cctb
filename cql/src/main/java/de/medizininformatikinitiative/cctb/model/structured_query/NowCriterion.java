package de.medizininformatikinitiative.cctb.model.structured_query;

import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.cql.Container;
import de.medizininformatikinitiative.cctb.model.cql.DefaultExpression;
import de.medizininformatikinitiative.cctb.model.cql.Expression;
import de.medizininformatikinitiative.cctb.model.cql.FunctionInvocation;
import de.medizininformatikinitiative.cctb.model.cql.ListSelector;
import de.medizininformatikinitiative.cctb.model.cql.WrapperExpression;

import java.util.List;

/**
 * The reserved {@code now} criterion (JSON {@code {"type": "now"}}), used to express time constraints relative to
 * "today / the evaluation date" via the same anchor mechanism used for clinical events.
 * <p>
 * It always resolves to the evaluation timestamp and trivially matches every patient, exactly like
 * {@link Criterion#TRUE}; a group containing only a {@code now} criterion exists purely to be referenced via
 * {@code anchorRef}.
 */
public final class NowCriterion implements Criterion {

    public static final NowCriterion INSTANCE = new NowCriterion();

    private NowCriterion() {
    }

    public static NowCriterion of() {
        return INSTANCE;
    }

    @Override
    public ContextualConcept getConcept() {
        return null;
    }

    @Override
    public Container<DefaultExpression> toCql(MappingContext mappingContext) {
        return Container.of(Expression.TRUE).moveToPatientContext("Criterion");
    }

    @Override
    public Container<DefaultExpression> toReferencesCql(MappingContext mappingContext) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<AttributeFilter> attributeFilters() {
        return List.of();
    }

    @Override
    public TimeRestriction timeRestriction() {
        return null;
    }

    @Override
    public Container<DefaultExpression> dateValuesExpr(MappingContext mappingContext, Group.AnchorPoint anchorPoint) {
        var now = new WrapperExpression(FunctionInvocation.of("Now", List.of()));
        return Container.of((DefaultExpression) new WrapperExpression(ListSelector.of(List.of(now))));
    }
}
