package de.medizininformatikinitiative.cctb.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.cql.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * A named group of criteria, optionally anchored in time relative to another group (see
 * {@link RelativeTimeRestriction}).
 * <p>
 * {@code criteria} keeps today's existing two-level CNF/DNF shape (AND-of-OR for inclusion, OR-of-AND for
 * exclusion, see {@link #toCql}), just relocated one level deeper than in the pre-anchor {@code StructuredQuery}
 * shape - a group's top-level list of criteria-lists is exactly what {@code inclusionCriteria}/
 * {@code exclusionCriteria} used to be directly.
 *
 * @param id                      the group's id, required only if this group is referenced via
 *                                {@code anchorRef} by another group's {@link RelativeTimeRestriction}
 * @param criteria                the group's own criteria, in the existing two-level CNF/DNF shape
 * @param relativeTimeRestriction the time window this group's criteria are restricted to, relative to another
 *                                group's resolved anchor date, or {@code null} if this group has no relative time
 *                                restriction
 * @param anchorOccurrence        which occurrence of this group's own matching resources supplies the anchor date
 *                                when this group is used as an anchor by another group (required in that case,
 *                                unless this group's sole criterion is {@code now})
 * @param anchorPoint             which point of a {@code Period}-typed matching resource supplies the anchor date
 *                                when this group is used as an anchor by another group; meaningful only then, and
 *                                only for matches that are actually {@code Period}-typed (defaults to
 *                                {@link AnchorPoint#START})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Group(String id, List<List<Criterion>> criteria, RelativeTimeRestriction relativeTimeRestriction,
                    AnchorOccurrence anchorOccurrence, AnchorPoint anchorPoint) {

    public Group {
        criteria = criteria.stream().map(List::copyOf).toList();
    }

    public static Group of(List<List<Criterion>> criteria) {
        return create(null, criteria, null, null, null);
    }

    public static Group of(Criterion... criteria) {
        return create(null, List.of(List.of(criteria)), null, null, null);
    }

    public static Group of(String id, List<List<Criterion>> criteria) {
        return create(id, criteria, null, null, null);
    }

    public static Group of(String id, Criterion... criteria) {
        return create(id, List.of(List.of(criteria)), null, null, null);
    }

    public static Group of(String id, List<List<Criterion>> criteria, AnchorOccurrence anchorOccurrence) {
        return create(id, criteria, null, anchorOccurrence, null);
    }

    public static Group of(String id, List<List<Criterion>> criteria, AnchorOccurrence anchorOccurrence,
                           AnchorPoint anchorPoint) {
        return create(id, criteria, null, anchorOccurrence, anchorPoint);
    }

    public static Group of(String id, List<List<Criterion>> criteria, RelativeTimeRestriction relativeTimeRestriction) {
        return create(id, criteria, relativeTimeRestriction, null, null);
    }

    public static Group of(String id, List<List<Criterion>> criteria, RelativeTimeRestriction relativeTimeRestriction,
                           AnchorOccurrence anchorOccurrence) {
        return create(id, criteria, relativeTimeRestriction, anchorOccurrence, null);
    }

    @JsonCreator
    public static Group create(@JsonProperty("id") String id,
                               @JsonProperty("criteria") List<List<Criterion>> criteria,
                               @JsonProperty("relativeTimeRestriction") RelativeTimeRestriction relativeTimeRestriction,
                               @JsonProperty("anchorOccurrence") AnchorOccurrence anchorOccurrence,
                               @JsonProperty("anchorPoint") AnchorPoint anchorPoint) {
        if (criteria == null || criteria.isEmpty() || criteria.stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException("empty criteria in group%s"
                    .formatted(id == null ? "" : " `%s`".formatted(id)));
        }
        return new Group(id, criteria, relativeTimeRestriction, anchorOccurrence, anchorPoint);
    }

    /**
     * Returns {@code true} iff this group's sole criterion is the {@link NowCriterion now} criterion, which has
     * exactly one occurrence by definition and therefore needs no {@link #anchorOccurrence} even when used as an
     * anchor.
     */
    boolean isNowGroup() {
        return criteria.size() == 1 && criteria.get(0).size() == 1 && criteria.get(0).get(0) instanceof NowCriterion;
    }

    /**
     * Translates this group into a CQL expression.
     *
     * @param mappingContext  contains the mappings needed to create the CQL expression
     * @param allGroupsById   every group of the whole {@link StructuredQuery} (both inclusion and exclusion side),
     *                        keyed by {@link #id}, used to resolve {@code anchorRef}s
     * @param isInclusionSide {@code true} if this group sits in {@code inclusionCriteria} (AND-of-OR), {@code
     *                        false} if it sits in {@code exclusionCriteria} (OR-of-AND)
     * @return a {@link Container} of the CQL expression together with its used {@link CodeSystemDefinition
     * CodeSystemDefinitions}
     */
    public Container<DefaultExpression> toCql(MappingContext mappingContext, Map<String, Group> allGroupsById,
                                              boolean isInclusionSide) {
        if (relativeTimeRestriction == null) {
            return combineCriteria(mappingContext, isInclusionSide, null);
        }
        var anchor = allGroupsById.get(relativeTimeRestriction.anchorRef());
        return computeWindow(mappingContext, allGroupsById, anchor)
                .flatMap(window -> combineCriteria(mappingContext, isInclusionSide, (IntervalSelector) window));
    }

    private Container<DefaultExpression> combineCriteria(MappingContext mappingContext, boolean isInclusionSide,
                                                          IntervalSelector window) {
        var level2Combiner = isInclusionSide ? Container.AND : Container.OR;
        var level3Combiner = isInclusionSide ? Container.OR : Container.AND;
        return criteria.stream()
                .map(clause -> clause.stream()
                        .map(criterion -> window == null
                                ? criterion.toCql(mappingContext)
                                : criterion.toCql(mappingContext, window))
                        .reduce(Container.empty(), level3Combiner))
                .reduce(Container.empty(), level2Combiner);
    }

    /**
     * This group's own resolved anchor date(s), as used by a dependent group's {@link RelativeTimeRestriction}:
     * {@code earliest} is the earliest date across this group's AND'd clauses, {@code latest} the latest. For a
     * single-clause group (today's only previously-supported case) both are the identical value.
     * <p>
     * The two are deliberately kept separate rather than collapsed to one anchor point, and consumed
     * asymmetrically by {@link #computeWindow}: a dependent's {@code maxOffset} bound is computed from
     * {@code earliest}, its {@code minOffset} bound from {@code latest}. Why: this group (all clauses required)
     * is only true once its <em>last</em> clause is satisfied, so nothing can be "at/after" it before that moment
     * - the latest clause is the binding constraint for the lower bound. But something is "within {@code
     * maxOffset} after" this group only if it is within {@code maxOffset} of <em>every</em> clause, and the
     * earliest clause produces the tightest (most binding) ceiling for that. Collapsing to a single point (either
     * the earliest or the latest) always gets one of the two bounds wrong.
     */
    private record AnchorDates(Container<DefaultExpression> earliest, Container<DefaultExpression> latest) {}

    /**
     * Resolves {@link AnchorDates} for this group. If this group is itself a dependent of another anchor
     * (chaining), its own {@link #relativeTimeRestriction} is resolved and applied first, so its candidate
     * resources are already window-filtered before aggregation - the {@code anchorRef} graph is validated
     * acyclic by {@link StructuredQuery}, so this recursion terminates.
     */
    private AnchorDates resolveAnchorDates(MappingContext mappingContext, Map<String, Group> allGroupsById) {
        if (relativeTimeRestriction == null) {
            return aggregateClauseDates(mappingContext, null);
        }
        var anchor = allGroupsById.get(relativeTimeRestriction.anchorRef());
        var windowContainer = computeWindow(mappingContext, allGroupsById, anchor);
        var earliest = windowContainer.flatMap(w -> aggregateClauseDates(mappingContext, (IntervalSelector) w).earliest());
        var latest = windowContainer.flatMap(w -> aggregateClauseDates(mappingContext, (IntervalSelector) w).latest());
        return new AnchorDates(earliest, latest);
    }

    /**
     * Builds {@link AnchorDates} from this group's own clauses. A single clause resolves directly (via
     * {@link #resolveClauseDate}) and is named once, becoming both {@code earliest} and {@code latest} - exactly
     * today's behaviour for the previously-only-supported single-clause case. Multiple (AND'd) clauses each
     * resolve independently, get combined into one named CQL list (see {@link #clauseDatesListExpr}), and
     * {@code earliest}/{@code latest} become {@code Min}/{@code Max} over that shared list.
     */
    private AnchorDates aggregateClauseDates(MappingContext mappingContext, IntervalSelector window) {
        if (criteria.size() == 1) {
            var named = resolveClauseDate(mappingContext, criteria.get(0), window)
                    .moveToPatientContext("AnchorDate_" + id);
            return new AnchorDates(named, named);
        }
        var namedList = clauseDatesListExpr(mappingContext, window).moveToPatientContext("AnchorDate_" + id);
        var earliest = namedList.map(listExpr -> (DefaultExpression) new WrapperExpression(
                FunctionInvocation.of("Min", List.of(listExpr))));
        var latest = namedList.map(listExpr -> (DefaultExpression) new WrapperExpression(
                FunctionInvocation.of("Max", List.of(listExpr))));
        return new AnchorDates(earliest, latest);
    }

    /**
     * Resolves one clause's own date: gathers every resource matching its criteria, reduces each to a single
     * {@link AnchorPoint point}, and collapses them to one timestamp via {@code Min} ({@link
     * AnchorOccurrence#FIRST}) or {@code Max} ({@link AnchorOccurrence#LAST}).
     */
    private Container<DefaultExpression> resolveClauseDate(MappingContext mappingContext, List<Criterion> clause,
                                                            IntervalSelector window) {
        var point = anchorPoint == null ? AnchorPoint.START : anchorPoint;
        Container<DefaultExpression> datesExpr = clause.stream()
                .map(criterion -> window == null
                        ? criterion.dateValuesExpr(mappingContext, point)
                        : criterion.dateValuesExpr(mappingContext, point, window))
                .reduce(Container.empty(), Container.UNION);
        var occurrence = anchorOccurrence == null ? AnchorOccurrence.FIRST : anchorOccurrence;
        var aggregateFunction = occurrence == AnchorOccurrence.LAST ? "Max" : "Min";
        return datesExpr.map(listExpr -> (DefaultExpression) new WrapperExpression(
                FunctionInvocation.of(aggregateFunction, List.of(listExpr))));
    }

    /**
     * Combines every clause's own resolved date ({@link #resolveClauseDate}) into one CQL list literal (e.g.
     * {@code { clause1Date, clause2Date }}), so {@link #aggregateClauseDates} can derive both {@code Min} and
     * {@code Max} across clauses from the same shared, named value. Built via sequential {@link
     * Container#flatMap} rather than the static combiners ({@code Container.AND}/{@code UNION}) deliberately: at
     * this point none of the per-clause containers carry any named definitions yet, so there is no risk of the
     * name-collision-avoidance renaming those static combiners apply when merging independently-built containers.
     */
    private Container<DefaultExpression> clauseDatesListExpr(MappingContext mappingContext, IntervalSelector window) {
        var perClause = criteria.stream().map(clause -> resolveClauseDate(mappingContext, clause, window)).toList();
        var collected = new ArrayList<DefaultExpression>();
        Container<DefaultExpression> acc = perClause.get(0).map(expr -> {
            collected.add(expr);
            return expr;
        });
        for (var i = 1; i < perClause.size(); i++) {
            var next = perClause.get(i);
            acc = acc.flatMap(ignored -> next.map(expr -> {
                collected.add(expr);
                return expr;
            }));
        }
        return acc.map(ignored -> (DefaultExpression) new WrapperExpression(ListSelector.of(List.copyOf(collected))));
    }

    /**
     * Returns a {@link Container} holding the computed window as an {@link IntervalSelector}, upcast to
     * {@link DefaultExpression} because {@code IntervalSelector} (like every other concrete {@code Expression}
     * subtype here) implements {@code Expression<DefaultExpression>} rather than {@code Expression<IntervalSelector>}
     * and so cannot itself be a {@link Container}'s type parameter - callers cast back to {@code IntervalSelector}.
     * <p>
     * {@code windowEnd} (the {@code maxOffset} bound) is built from {@code anchor}'s {@link AnchorDates#earliest},
     * {@code windowStart} (the {@code minOffset} bound) from its {@link AnchorDates#latest} - see the design note
     * on {@link AnchorDates} for why. For a single-clause anchor these are the same, already-named value, so this
     * reduces to exactly the previous, tested behaviour: the anchor date resolved once and referenced by
     * identifier from both bounds instead of embedding a full copy of the (potentially large, concept-expanded)
     * anchor subquery inline.
     */
    private Container<DefaultExpression> computeWindow(MappingContext mappingContext, Map<String, Group> allGroupsById,
                                                        Group anchor) {
        var anchorDates = anchor.resolveAnchorDates(mappingContext, allGroupsById);
        return anchorDates.latest().flatMap(latestExpr -> anchorDates.earliest().map(earliestExpr -> {
            Expression<?> windowStart = relativeTimeRestriction.minOffset() == null
                    ? DateTimeExpression.of(TimeRestriction.MIN_AFTER_DATE)
                    : AdditionExpressionTerm.of(latestExpr, offsetQuantity(relativeTimeRestriction.minOffset()));
            Expression<?> windowEnd = relativeTimeRestriction.maxOffset() == null
                    ? DateTimeExpression.of(TimeRestriction.MAX_BEFORE_DATE)
                    : AdditionExpressionTerm.of(earliestExpr, offsetQuantity(relativeTimeRestriction.maxOffset()));
            return (DefaultExpression) IntervalSelector.of(windowStart, windowEnd);
        }));
    }

    /**
     * Normalizes an offset {@link Duration} to whole hours and prints it as an unquoted CQL calendar duration
     * keyword (e.g. {@code 3 hours}), sidestepping calendar-day/DST ambiguity in {@code DateTime + Quantity}
     * arithmetic. Sub-day (hour) precision is enough for the offsets this feature is meant for (see plan).
     */
    private static QuantityExpression offsetQuantity(Duration offset) {
        return QuantityExpression.ofCalendarDuration(BigDecimal.valueOf(offset.toHours()), "hours");
    }

    public enum AnchorOccurrence {
        @JsonProperty("first") FIRST,
        @JsonProperty("last") LAST
    }

    public enum AnchorPoint {
        @JsonProperty("start") START,
        @JsonProperty("end") END
    }
}
