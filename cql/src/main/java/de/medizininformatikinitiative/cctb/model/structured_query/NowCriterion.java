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

    /**
     * The evaluation timestamp, reduced to a date.
     * <p>
     * {@code ToDate} is not cosmetic here. Every other criterion projects its date through
     * {@code ToDate(<path> as dateTime)} (see {@code AbstractCriterion.dateProjectionExpr}), so a criterion being
     * window-filtered is always compared at day precision. Emitting a bare {@code Now()} made this one anchor
     * produce full {@code DateTime} precision instead, and CQL compares a {@code Date} against {@code DateTime}
     * bounds as <em>uncertain</em> rather than as a plain ordering - so the membership test returned null and
     * every {@code now}-anchored group silently matched nobody, whatever the data. Confirmed against a real
     * engine both ways round; see {@code WorkedExampleIT.hemoglobinLast24h}, which is the published quickstart
     * example and selected zero patients before this.
     * <p>
     * The consequence for authors is that a {@code now}-relative window is only as fine-grained as the dates it
     * is compared against, which are days. {@code -PT24H} therefore means "since yesterday's date", not "within
     * the last 24 clock hours" - the same day-granularity every other anchor already has.
     */
    @Override
    public Container<DefaultExpression> dateValuesExpr(MappingContext mappingContext, Group.AnchorPoint anchorPoint) {
        var now = new WrapperExpression(FunctionInvocation.of("ToDate",
                List.of(new WrapperExpression(FunctionInvocation.of("Now", List.of())))));
        return Container.of((DefaultExpression) new WrapperExpression(ListSelector.of(List.of(now))));
    }
}
