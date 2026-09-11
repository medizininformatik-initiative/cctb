package de.medizininformatikinitiative.cctb;

import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.cql.AliasedQuerySource;
import de.medizininformatikinitiative.cctb.model.cql.CodeSystemDefinition;
import de.medizininformatikinitiative.cctb.model.cql.Container;
import de.medizininformatikinitiative.cctb.model.cql.DefaultExpression;
import de.medizininformatikinitiative.cctb.model.cql.ExistsExpression;
import de.medizininformatikinitiative.cctb.model.cql.IdentifierExpression;
import de.medizininformatikinitiative.cctb.model.cql.ParenthesizedExpression;
import de.medizininformatikinitiative.cctb.model.cql.QueryExpression;
import de.medizininformatikinitiative.cctb.model.cql.SourceClause;
import de.medizininformatikinitiative.cctb.model.cql.StandardIdentifierExpression;
import de.medizininformatikinitiative.cctb.model.cql.WhereClause;
import de.medizininformatikinitiative.cctb.model.structured_query.Group;
import de.medizininformatikinitiative.cctb.model.structured_query.RelativeTimeRestriction;
import de.medizininformatikinitiative.cctb.model.structured_query.StructuredQuery;
import de.medizininformatikinitiative.cctb.model.structured_query.TranslationException;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static de.medizininformatikinitiative.cctb.model.cql.Container.AND;
import static de.medizininformatikinitiative.cctb.model.cql.Container.AND_NOT;
import static de.medizininformatikinitiative.cctb.model.cql.Container.OR;
import static java.util.Objects.requireNonNull;

/**
 * The translator from Structured Query to CQL.
 * <p>
 * It needs {@code mappings} and will produce a CQL {@link Container} by calling {@link #toCql(StructuredQuery) toCql}.
 * <p>
 * Instances are immutable and thread-safe.
 *
 * @author Alexander Kiel
 */
public class Translator {

    private final MappingContext mappingContext;

    private Translator(MappingContext mappingContext) {
        this.mappingContext = requireNonNull(mappingContext);
    }

    /**
     * Returns a translator without any mappings.
     *
     * @return a translator without any mappings
     */
    public static Translator of() {
        return new Translator(MappingContext.of());
    }

    /**
     * Returns a translator with mappings defined in {@code mappingContext}.
     *
     * @return a translator with mappings defined in {@code mappingContext}
     */
    public static Translator of(MappingContext mappingContext) {
        return new Translator(mappingContext);
    }

    /**
     * Translates the given {@code structuredQuery} into a CQL {@link Container}.
     *
     * @param structuredQuery the Structured Query to translate
     * @return the translated CQL {@link Container}
     * @throws TranslationException if the given {@code structuredQuery} can't be translated into a
     *                              CQL {@link Container}
     */
    public Container<DefaultExpression> toCql(StructuredQuery structuredQuery) {
        var allGroupsById = collectGroupsById(structuredQuery);
        var inclusionExpr = bundlesExpr(structuredQuery.inclusionCriteria(), allGroupsById, true);
        var exclusionExpr = bundlesExpr(structuredQuery.exclusionCriteria(), allGroupsById, false);

        return exclusionExpr.isEmpty()
                ? inclusionExpr.moveToPatientContext("InInitialPopulation")
                : AND_NOT.apply(inclusionExpr.moveToPatientContext("Inclusion"),
                        exclusionExpr.moveToPatientContext("Exclusion"))
                .moveToPatientContext("InInitialPopulation");
    }

    /**
     * Collects every {@link Group} of {@code structuredQuery}, every bundle, both inclusion and exclusion side,
     * keyed by {@link Group#id}, so a group's {@code relativeTimeRestrictions} can resolve each entry's {@code
     * anchorRef} regardless of which bundle (or side) the referenced anchor group lives in - {@code anchorRef}
     * resolution is global, independent of bundle membership (see {@link StructuredQuery}).
     */
    private static Map<String, Group> collectGroupsById(StructuredQuery structuredQuery) {
        var groupsById = new HashMap<String, Group>();
        Stream.concat(structuredQuery.inclusionCriteria().stream(), structuredQuery.exclusionCriteria().stream())
                .flatMap(List::stream)
                .filter(group -> group.id() != null)
                .forEach(group -> groupsById.put(group.id(), group));
        return groupsById;
    }

    /**
     * Builds the boolean expression of one side (inclusion or exclusion) of a {@link StructuredQuery} as an OR of
     * its bundles, each bundle itself an AND of its {@link Group Groups} - uniformly OR-of-AND on both sides (see
     * the CCDL relative-time constraint draft, section 1 and 3, for why the OR level is uniform and why a bundle
     * stays AND-only).
     *
     * @param bundles         the bundles of one side of a {@link StructuredQuery}
     * @param allGroupsById   every group of the whole {@link StructuredQuery}, used to resolve {@code anchorRef}s
     * @param isInclusionSide {@code true} for {@code inclusionCriteria}, {@code false} for {@code exclusionCriteria}
     * @return a {@link Container} of the boolean expression together with the used {@link CodeSystemDefinition
     * CodeSystemDefinitions}
     */
    private Container<DefaultExpression> bundlesExpr(List<List<Group>> bundles, Map<String, Group> allGroupsById,
                                                      boolean isInclusionSide) {
        return bundles.stream()
                .map(bundle -> bundleExpr(bundle, allGroupsById, isInclusionSide))
                .reduce(Container.empty(), OR);
    }

    /**
     * Builds one bundle's own boolean expression as the AND of its {@link Group Groups} - every group listed in a
     * bundle is unconditionally required for that bundle to be satisfied; a group merely referenced via {@code
     * anchorRef} from a group in this bundle, without itself being a member of this bundle, contributes only its
     * resolved date, never its own truth (see {@link StructuredQuery}).
     * <p>
     * A member that is itself {@code anchorRef}'d by another member <em>of this same bundle</em> contributes
     * {@link Container#empty() nothing} rather than its own {@link Group#toCql translated criteria}, because
     * whichever other member references it already implies its truth. For a {@code "first"}/{@code "last"} anchor
     * that is the {@code AnchorDate} null-guard (one {@code is not null} check per clause), which provably
     * implies the anchor's own criteria: a resolved date requires a matching resource, though not the reverse.
     * That argument is skipped when the anchor itself carries {@code relativeTimeRestrictions}, where its guard
     * interacts with the incoming window in ways the simple argument does not cover. For an {@code "any"} anchor
     * it is the enclosing {@code exists} over its candidate dates, which is a stronger implication still - a
     * witness existing means the anchor matched inside its own window - and therefore holds whether or not the
     * anchor is itself chained.
     */
    private Container<DefaultExpression> bundleExpr(List<Group> bundle, Map<String, Group> allGroupsById,
                                                     boolean isInclusionSide) {
        var referencedWithinThisBundle = bundle.stream()
                .filter(group -> group.relativeTimeRestrictions() != null)
                .flatMap(group -> group.relativeTimeRestrictions().stream())
                .map(RelativeTimeRestriction::anchorRef)
                .collect(Collectors.toSet());
        var members = bundle.stream()
                .filter(group -> !subsumedWithinThisBundle(group, referencedWithinThisBundle))
                .toList();
        return anyAwareExpr(members, allGroupsById, isInclusionSide);
    }

    private static boolean subsumedWithinThisBundle(Group group, Set<String> referencedWithinThisBundle) {
        if (group.id() == null || !referencedWithinThisBundle.contains(group.id())) {
            return false;
        }
        return group.anchorOccurrence() == Group.AnchorOccurrence.ANY || group.relativeTimeRestrictions() == null;
    }

    /**
     * Emits the AND of {@code members}, wrapping every {@code anchorOccurrence: "any"} anchor they depend on in
     * one correlated {@code exists} over that anchor's candidate dates.
     * <p>
     * This is where the draft's shared-witness scope rule is realised. All members of this bundle that reference
     * the same {@code "any"} anchor end up inside a <em>single</em> existential, so one witness has to satisfy
     * every one of them - not one existential per reference site, which would let each pick a different
     * occurrence and quietly stop the shared anchor id from denoting one event.
     * <p>
     * Anchors are bound outermost-first in dependency order, so a chain of {@code "any"} anchors nests: each
     * one's candidate query is filtered by the window of the witness bound outside it. The members themselves sit
     * innermost, where every alias they need is in scope. Every anchor in the transitive closure gets a binding,
     * not only those a member names directly - an anchor reached only through another {@code "any"} anchor still
     * needs its own witness for that anchor's candidates to be computable.
     */
    private Container<DefaultExpression> anyAwareExpr(List<Group> members, Map<String, Group> allGroupsById,
                                                       boolean isInclusionSide) {
        return bindAnyAnchors(anyAnchorBindingOrder(members, allGroupsById), 0, members, allGroupsById, Map.of(),
                isInclusionSide);
    }

    private Container<DefaultExpression> bindAnyAnchors(List<String> bindingOrder, int index, List<Group> members,
                                                         Map<String, Group> allGroupsById,
                                                         Map<String, IdentifierExpression> bound,
                                                         boolean isInclusionSide) {
        if (index == bindingOrder.size()) {
            return members.stream()
                    .map(group -> group.toCql(mappingContext, allGroupsById, isInclusionSide, bound))
                    .reduce(Container.empty(), AND);
        }
        var anchorId = bindingOrder.get(index);
        var anchor = allGroupsById.get(anchorId);
        var alias = StandardIdentifierExpression.of("W" + (index + 1));

        var candidates = anchor.anyCandidateDates(mappingContext, allGroupsById, bound);
        var innerBound = new HashMap<>(bound);
        innerBound.put(anchorId, alias);
        var inner = bindAnyAnchors(bindingOrder, index + 1, members, allGroupsById, Map.copyOf(innerBound),
                isInclusionSide);

        var existsExpr = candidates.dates().flatMap(dates -> inner.map(innerExpr ->
                (DefaultExpression) ExistsExpression.of(QueryExpression.of(
                        SourceClause.of(AliasedQuerySource.of(ParenthesizedExpression.of(dates), alias)),
                        WhereClause.of(innerExpr)))));

        // The anchor's own guard sits OUTSIDE its exists: it asserts that whatever this anchor is itself chained
        // off resolved, and if it did not, the candidate query's window is unbounded rather than empty (see
        // Group.AnyCandidates), so every date this anchor matches would wrongly become a witness.
        return AND.apply(candidates.guard(), existsExpr);
    }

    /**
     * The {@code "any"} anchors {@code members} transitively depend on, dependencies first, so binding them in
     * this order nests each anchor inside the ones its own window is measured from. The {@code anchorRef} graph
     * is validated acyclic by {@link StructuredQuery}, so the walk terminates.
     */
    private static List<String> anyAnchorBindingOrder(List<Group> members, Map<String, Group> allGroupsById) {
        var order = new LinkedHashSet<String>();
        for (var member : members) {
            collectAnyAnchors(member, allGroupsById, order);
        }
        return List.copyOf(order);
    }

    private static void collectAnyAnchors(Group group, Map<String, Group> allGroupsById, LinkedHashSet<String> order) {
        for (var anchorId : anyAnchorRefs(group, allGroupsById)) {
            if (order.contains(anchorId)) {
                continue;
            }
            collectAnyAnchors(allGroupsById.get(anchorId), allGroupsById, order);
            order.add(anchorId);
        }
    }

    /** The ids of the {@code "any"} anchors {@code group} references directly, in declaration order. */
    private static Set<String> anyAnchorRefs(Group group, Map<String, Group> allGroupsById) {
        if (group.relativeTimeRestrictions() == null) {
            return Set.of();
        }
        return group.relativeTimeRestrictions().stream()
                .map(RelativeTimeRestriction::anchorRef)
                .filter(anchorRef -> {
                    var anchor = allGroupsById.get(anchorRef);
                    return anchor != null && anchor.anchorOccurrence() == Group.AnchorOccurrence.ANY;
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
