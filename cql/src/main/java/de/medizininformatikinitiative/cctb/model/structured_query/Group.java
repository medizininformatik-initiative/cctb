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
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

/**
 * A named group of criteria, optionally anchored in time relative to one or more other groups (see
 * {@link RelativeTimeRestriction}).
 * <p>
 * {@code criteria} keeps today's existing two-level CNF/DNF shape (AND-of-OR for inclusion, OR-of-AND for
 * exclusion, see {@link #toCql}). A {@link Group} is itself an element of a <em>bundle</em> - an AND-array
 * directly inside {@code inclusionCriteria}/{@code exclusionCriteria}, which is in turn an OR-array of bundles
 * (see {@link StructuredQuery}) - not a direct member of {@code inclusionCriteria}/{@code exclusionCriteria} on
 * its own.
 *
 * @param id                       the group's id, required only if this group is referenced via
 *                                 {@code anchorRef} by another group's {@link RelativeTimeRestriction}
 * @param criteria                 the group's own criteria, in the existing two-level CNF/DNF shape
 * @param relativeTimeRestrictions the time window(s) this group's criteria are restricted to, relative to one or
 *                                 more other groups' resolved anchor dates, or {@code null} if this group has no
 *                                 relative time restriction. When more than one entry is present, the group's
 *                                 actual window is the <em>intersection</em> of every entry's own window - one
 *                                 shared witness resource satisfying all of them at once (the "between event A
 *                                 and event B" pattern) - not independent constraints; see {@link #computeWindow}
 * @param anchorOccurrence         which occurrence of this group's own matching resources supplies the anchor date
 *                                 when this group is used as an anchor by another group (required in that case,
 *                                 unless this group's sole criterion is {@code now})
 * @param anchorPoint              which point of a {@code Period}-typed matching resource supplies the anchor date
 *                                 when this group is used as an anchor by another group; meaningful only then, and
 *                                 only for matches that are actually {@code Period}-typed (defaults to
 *                                 {@link AnchorPoint#START})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Group(String id, List<List<Criterion>> criteria, List<RelativeTimeRestriction> relativeTimeRestrictions,
                    AnchorOccurrence anchorOccurrence, AnchorPoint anchorPoint) {

    public Group {
        criteria = criteria.stream().map(List::copyOf).toList();
        if (relativeTimeRestrictions != null) {
            relativeTimeRestrictions = List.copyOf(relativeTimeRestrictions);
            if (relativeTimeRestrictions.isEmpty()) {
                throw new IllegalArgumentException("empty relativeTimeRestrictions in group%s"
                        .formatted(id == null ? "" : " `%s`".formatted(id)));
            }
        }
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

    /**
     * Convenience overload for the common single-entry case - wraps {@code relativeTimeRestriction} as a
     * one-element list. See {@link #of(String, List, List)} for genuine multi-entry (intersected-window) groups.
     */
    public static Group of(String id, List<List<Criterion>> criteria, RelativeTimeRestriction relativeTimeRestriction) {
        return create(id, criteria, List.of(requireNonNull(relativeTimeRestriction)), null, null);
    }

    public static Group of(String id, List<List<Criterion>> criteria, List<RelativeTimeRestriction> relativeTimeRestrictions) {
        return create(id, criteria, relativeTimeRestrictions, null, null);
    }

    /**
     * Convenience overload for the common single-entry case - see {@link #of(String, List, RelativeTimeRestriction)}.
     */
    public static Group of(String id, List<List<Criterion>> criteria, RelativeTimeRestriction relativeTimeRestriction,
                           AnchorOccurrence anchorOccurrence) {
        return create(id, criteria, List.of(requireNonNull(relativeTimeRestriction)), anchorOccurrence, null);
    }

    @JsonCreator
    public static Group create(@JsonProperty("id") String id,
                               @JsonProperty("criteria") List<List<Criterion>> criteria,
                               @JsonProperty("relativeTimeRestrictions") List<RelativeTimeRestriction> relativeTimeRestrictions,
                               @JsonProperty("anchorOccurrence") AnchorOccurrence anchorOccurrence,
                               @JsonProperty("anchorPoint") AnchorPoint anchorPoint) {
        if (criteria == null || criteria.isEmpty() || criteria.stream().allMatch(List::isEmpty)) {
            throw new IllegalArgumentException("empty criteria in group%s"
                    .formatted(id == null ? "" : " `%s`".formatted(id)));
        }
        return new Group(id, criteria, relativeTimeRestrictions, anchorOccurrence, anchorPoint);
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
     * Translates this group into a CQL expression. Requiredness within a bundle follows plain AND membership -
     * this method's own result is unconditionally required by whichever bundle contains it (see
     * {@link StructuredQuery}); a group merely *referenced* by {@code anchorRef} from elsewhere, without being a
     * member of a given bundle, never has this method called for that bundle at all.
     *
     * @param mappingContext  contains the mappings needed to create the CQL expression
     * @param allGroupsById   every group of the whole {@link StructuredQuery} (every bundle, both inclusion and
     *                        exclusion side), keyed by {@link #id}, used to resolve {@code anchorRef}s
     * @param isInclusionSide {@code true} if this group sits in an {@code inclusionCriteria} bundle (AND-of-OR),
     *                        {@code false} if it sits in an {@code exclusionCriteria} bundle (OR-of-AND)
     * @return a {@link Container} of the CQL expression together with its used {@link CodeSystemDefinition
     * CodeSystemDefinitions}
     */
    public Container<DefaultExpression> toCql(MappingContext mappingContext, Map<String, Group> allGroupsById,
                                              boolean isInclusionSide) {
        if (relativeTimeRestrictions == null) {
            return combineCriteria(mappingContext, isInclusionSide, null, null);
        }
        var window = computeWindow(mappingContext, allGroupsById);
        return window.interval().flatMap(intervalExpr ->
                combineCriteria(mappingContext, isInclusionSide, (IntervalSelector) intervalExpr, window.guard()));
    }

    /**
     * Combines this group's own criteria (level 3/4 AND/OR). When {@code window} is non-null, every leaf
     * criterion's window-filtered result is AND'd with {@code guard} first - see {@link Window} for why an
     * explicit guard, rather than the target language's own null-propagation, is required.
     */
    private Container<DefaultExpression> combineCriteria(MappingContext mappingContext, boolean isInclusionSide,
                                                          IntervalSelector window, Container<DefaultExpression> guard) {
        var level2Combiner = isInclusionSide ? Container.AND : Container.OR;
        var level3Combiner = isInclusionSide ? Container.OR : Container.AND;
        var combined = criteria.stream()
                .map(clause -> clause.stream()
                        .map(criterion -> window == null
                                ? criterion.toCql(mappingContext)
                                : criterion.toCql(mappingContext, window))
                        .reduce(Container.empty(), level3Combiner))
                .reduce(Container.empty(), level2Combiner);
        // AND the guard in once, after this group's own criteria are fully combined, rather than once per leaf
        // criterion inside the fold above - logically equivalent (AND distributes over both AND and OR: (g∧c1)∨
        // (g∧c2) = g∧(c1∨c2)), but avoids re-merging the same named "AnchorDate_..." definition through
        // Container's collision-avoidance combiner once per leaf, which - since that combiner only compares
        // names, not content - renamed it apart on every fold step even though every copy was identical.
        return window == null ? combined : Container.AND.apply(guard, combined);
    }

    /**
     * This group's own resolved anchor date(s), as used by a dependent group's {@link RelativeTimeRestriction}:
     * {@code earliest} is the earliest date across this group's AND'd clauses, {@code latest} the latest. For a
     * single-clause group (today's only previously-supported case) both are the identical value.
     * <p>
     * The two are deliberately kept separate rather than collapsed to one anchor point, and consumed
     * asymmetrically by {@link #computeEntryWindow}: a dependent's {@code maxOffset} bound is computed from
     * {@code earliest}, its {@code minOffset} bound from {@code latest}. Why: this group (all clauses required)
     * is only true once its <em>last</em> clause is satisfied, so nothing can be "at/after" it before that moment
     * - the latest clause is the binding constraint for the lower bound. But something is "within {@code
     * maxOffset} after" this group only if it is within {@code maxOffset} of <em>every</em> clause, and the
     * earliest clause produces the tightest (most binding) ceiling for that. Collapsing to a single point (either
     * the earliest or the latest) always gets one of the two bounds wrong.
     * <p>
     * {@code guard} is this anchor's own "did it actually resolve" check, consumed directly by
     * {@link #computeEntryWindow} as the entry's {@link Window#guard()}. For a single clause this is just
     * {@code earliest is not null} (earliest and latest are the same value there). For multiple (AND'd) clauses
     * it is deliberately NOT {@code Min(...) is not null and Max(...) is not null}: {@code Min}/{@code Max} over
     * a CQL list ignore null elements rather than propagating (confirmed empirically against real Blaze), so that
     * check only proves at least one clause resolved, not all of them - silently weakening this anchor's "all
     * clauses required" semantics to effectively-OR right at the guard. Instead {@code guard} checks the shared
     * clause-dates list directly for the presence of any null element, which is exactly "did every clause resolve".
     */
    private record AnchorDates(Container<DefaultExpression> earliest, Container<DefaultExpression> latest,
                               Container<DefaultExpression> guard) {}

    /**
     * Resolves {@link AnchorDates} for this group. If this group is itself a dependent of one or more anchors
     * (chaining), its own {@link #relativeTimeRestrictions} are resolved and applied first, so its candidate
     * resources are already window-filtered before aggregation - the {@code anchorRef} graph is validated
     * acyclic by {@link StructuredQuery}, so this recursion terminates.
     */
    private AnchorDates resolveAnchorDates(MappingContext mappingContext, Map<String, Group> allGroupsById) {
        if (relativeTimeRestrictions == null) {
            return aggregateClauseDates(mappingContext, null);
        }
        // Known gap, not covered by this fix: chaining doesn't yet apply `window.guard()` to candidate
        // filtering here - only the final criterion-matching path in combineCriteria does. If an upstream
        // anchor doesn't resolve, this group's own clause-date resolution can still incorrectly include
        // out-of-window candidates when it's itself an anchor for something further downstream.
        var window = computeWindow(mappingContext, allGroupsById);
        var earliest = window.interval().flatMap(w -> aggregateClauseDates(mappingContext, (IntervalSelector) w).earliest());
        var latest = window.interval().flatMap(w -> aggregateClauseDates(mappingContext, (IntervalSelector) w).latest());
        var guard = window.interval().flatMap(w -> aggregateClauseDates(mappingContext, (IntervalSelector) w).guard());
        return new AnchorDates(earliest, latest, guard);
    }

    /**
     * Builds {@link AnchorDates} from this group's own clauses. A single clause resolves directly (via
     * {@link #resolveClauseDate}) and is named once, becoming both {@code earliest} and {@code latest} - exactly
     * today's behaviour for the previously-only-supported single-clause case. Multiple (AND'd) clauses each
     * resolve independently, get combined into one named CQL list (see {@link #clauseDatesListExpr}), and
     * {@code earliest}/{@code latest} become {@code Min}/{@code Max} over that shared list.
     * <p>
     * Named via {@link Container#moveToPatientContextWithUniqueName(String)} rather than the suffix-collision-
     * avoiding {@link Container#moveToPatientContext(String)}: this anchor's {@code id} is validated document-wide
     * unique by {@link StructuredQuery}, and resolution is a pure function of this group's own (already
     * window-resolved) clauses, so every dependent or bundle that resolves the same anchor id independently
     * produces byte-identical content under that name. That lets {@code Container}'s name-based merge in
     * {@link Container#combiner} collapse every copy into a single shared {@code define}, instead of the
     * suffix machinery renaming each occurrence apart as if it were distinct content.
     */
    private AnchorDates aggregateClauseDates(MappingContext mappingContext, IntervalSelector window) {
        if (criteria.size() == 1) {
            var named = resolveClauseDate(mappingContext, criteria.get(0), window)
                    .moveToPatientContextWithUniqueName("AnchorDate_" + id);
            return new AnchorDates(named, named, named.map(IsNotNullExpression::of));
        }
        var namedList = clauseDatesListExpr(mappingContext, window).moveToPatientContextWithUniqueName("AnchorDate_" + id);
        var earliest = namedList.map(listExpr -> (DefaultExpression) new WrapperExpression(
                FunctionInvocation.of("Min", List.of(listExpr))));
        var latest = namedList.map(listExpr -> (DefaultExpression) new WrapperExpression(
                FunctionInvocation.of("Max", List.of(listExpr))));
        // Deliberately per-index IsNotNullExpression checks on the shared named list, not `not exists (from
        // namedList D where D is null)`: confirmed empirically against real Blaze that the nested from/where query
        // fails to detect a null list element when that element came from a Min/Max(retrieve) aggregate rather
        // than a literal - it silently reports no null found even though one is present. Direct indexer access
        // (`namedList[i] is not null`) doesn't go through that nested-query path and was confirmed to work
        // correctly against the same data. Each check maps the SAME named container, so they all keep referencing
        // one shared "AnchorDate_..." define rather than duplicating it (see the design note above).
        var guard = IntStream.range(0, criteria.size())
                .mapToObj(i -> namedList.map(listExpr -> (DefaultExpression) IsNotNullExpression.of(IndexerExpression.of(listExpr, i))))
                .reduce(Container.empty(), Container.AND);
        return new AnchorDates(earliest, latest, guard);
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
     * The computed time window ({@code interval}) together with an explicit validity {@code guard} - "did every
     * referenced anchor actually resolve for this patient" - that {@link #combineCriteria} AND's into every
     * criterion this window is applied to.
     * <p>
     * The guard is required, not optional defensiveness: naive three-valued-logic reasoning suggests a criterion
     * measured against a window built from an unresolved (null) anchor date should naturally evaluate to false
     * via ordinary null propagation ({@code Min({})} over an empty candidate list &rarr; null &rarr;
     * {@code null + Duration} &rarr; null-bounded {@code Interval} &rarr; a {@code where} clause dropping the
     * row). Confirmed empirically against a real engine (Blaze 0.34) that this does NOT happen - the row is
     * kept and the criterion incorrectly matches as if the window were unbounded, the worse of the two possible
     * failure modes. So the guard is built explicitly here from each entry's own resolved anchor dates,
     * independent of whatever the target engine's arithmetic/interval-membership null handling actually does.
     */
    private record Window(Container<DefaultExpression> interval, Container<DefaultExpression> guard) {}

    /**
     * Computes this group's {@link Window} across every entry in {@link #relativeTimeRestrictions}.
     * <p>
     * The single-entry case (still the common one) resolves directly via {@link #computeEntryWindow}, with no
     * change from previous behaviour - same generated CQL as before this field became a list. Multiple entries
     * intersect: {@link #intersect} combines every entry's own window into one {@code Max}-of-starts/
     * {@code Min}-of-ends interval and ANDs every entry's own guard - the single-shared-witness semantics for
     * the "between event A and event B" pattern (see this record's class-level doc).
     */
    private Window computeWindow(MappingContext mappingContext, Map<String, Group> allGroupsById) {
        var entryWindows = relativeTimeRestrictions.stream()
                .map(restriction -> computeEntryWindow(mappingContext, allGroupsById, restriction))
                .toList();
        return entryWindows.size() == 1 ? entryWindows.get(0) : intersect(entryWindows);
    }

    /**
     * Computes the {@link Window} for a single {@link RelativeTimeRestriction} entry, against the entry's own
     * {@code anchorRef}.
     * <p>
     * {@code windowEnd} (the entry's {@code maxOffset} bound) is built from the anchor's {@link
     * AnchorDates#earliest}, {@code windowStart} (the entry's {@code minOffset} bound) from its {@link
     * AnchorDates#latest} - see the design note on {@link AnchorDates} for why. For a single-clause anchor these
     * are the same, already-named value, so this reduces to exactly the original, tested behaviour: the anchor
     * date resolved once and referenced by identifier from both bounds instead of embedding a full copy of the
     * (potentially large, concept-expanded) anchor subquery inline. The guard is {@link AnchorDates#guard},
     * the anchor's own "did it actually resolve" check - see the design note there for why it is not simply
     * derived from {@code earliest}/{@code latest} being non-null.
     */
    private Window computeEntryWindow(MappingContext mappingContext, Map<String, Group> allGroupsById,
                                      RelativeTimeRestriction restriction) {
        var anchor = allGroupsById.get(restriction.anchorRef());
        var anchorDates = anchor.resolveAnchorDates(mappingContext, allGroupsById);
        var interval = anchorDates.latest().flatMap(latestExpr -> anchorDates.earliest().map(earliestExpr -> {
            Expression<?> windowStart = restriction.minOffset() == null
                    ? DateTimeExpression.of(TimeRestriction.MIN_AFTER_DATE)
                    : AdditionExpressionTerm.of(latestExpr, offsetQuantity(restriction.minOffset()));
            Expression<?> windowEnd = restriction.maxOffset() == null
                    ? DateTimeExpression.of(TimeRestriction.MAX_BEFORE_DATE)
                    : AdditionExpressionTerm.of(earliestExpr, offsetQuantity(restriction.maxOffset()));
            return (DefaultExpression) IntervalSelector.of(windowStart, windowEnd);
        }));
        return new Window(interval, anchorDates.guard());
    }

    /**
     * Intersects two or more entries' own {@link Window}s into one: {@code windowStart = Max} of every entry's
     * own start bound, {@code windowEnd = Min} of every entry's own end bound (one shared witness resource must
     * satisfy every entry's window simultaneously - see this record's class-level doc), and {@code guard} is the
     * conjunction of every entry's own guard, so a single unresolved anchor among several correctly makes the
     * whole group no-match. Built via sequential {@link Container#flatMap}, mirroring {@link #clauseDatesListExpr}'s
     * accumulation pattern, to collect every entry's own start/end expressions into two CQL list literals before
     * wrapping each in {@code Max}/{@code Min}.
     */
    private Window intersect(List<Window> entryWindows) {
        var starts = new ArrayList<DefaultExpression>();
        var ends = new ArrayList<DefaultExpression>();
        Container<DefaultExpression> intervalAcc = entryWindows.get(0).interval().map(expr -> {
            var interval = (IntervalSelector) expr;
            starts.add((DefaultExpression) interval.intervalStart());
            ends.add((DefaultExpression) interval.intervalEnd());
            return expr;
        });
        for (var i = 1; i < entryWindows.size(); i++) {
            var next = entryWindows.get(i).interval();
            intervalAcc = intervalAcc.flatMap(ignored -> next.map(expr -> {
                var interval = (IntervalSelector) expr;
                starts.add((DefaultExpression) interval.intervalStart());
                ends.add((DefaultExpression) interval.intervalEnd());
                return expr;
            }));
        }
        var interval = intervalAcc.map(ignored -> {
            Expression<?> windowStart = new WrapperExpression(FunctionInvocation.of("Max",
                    List.of(new WrapperExpression(ListSelector.of(List.copyOf(starts))))));
            Expression<?> windowEnd = new WrapperExpression(FunctionInvocation.of("Min",
                    List.of(new WrapperExpression(ListSelector.of(List.copyOf(ends))))));
            return (DefaultExpression) IntervalSelector.of(windowStart, windowEnd);
        });

        var guard = entryWindows.stream().map(Window::guard).reduce(Container.empty(), Container.AND);

        return new Window(interval, guard);
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
