package de.medizininformatikinitiative.cctb;

import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.cql.CodeSystemDefinition;
import de.medizininformatikinitiative.cctb.model.cql.Container;
import de.medizininformatikinitiative.cctb.model.cql.DefaultExpression;
import de.medizininformatikinitiative.cctb.model.structured_query.Group;
import de.medizininformatikinitiative.cctb.model.structured_query.RelativeTimeRestriction;
import de.medizininformatikinitiative.cctb.model.structured_query.StructuredQuery;
import de.medizininformatikinitiative.cctb.model.structured_query.TranslationException;

import java.util.HashMap;
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
     * {@link Container#empty() nothing} rather than its own {@link Group#toCql translated criteria}: whichever
     * other member references it already ANDs in {@code AnchorDate}'s null-guard (one {@code is not null} check
     * per clause, covering every one of this group's clauses - see {@code Group.AnchorDates}), and that guard
     * provably implies this group's own criteria (a resolved date requires a matching resource to exist, though
     * not vice versa - a match can exist without a usable date). So {@code thisGroupsCriteria AND guard} always
     * equals {@code guard} alone; keeping both only duplicates the same retrieve under two different names for no
     * behavioural difference. Skipped when the group itself carries {@code relativeTimeRestrictions} (a chained
     * anchor) - its own guard there interacts with the incoming window in ways this simpler argument doesn't
     * cover, so it is left translated normally rather than risk a wrong simplification.
     */
    private Container<DefaultExpression> bundleExpr(List<Group> bundle, Map<String, Group> allGroupsById,
                                                     boolean isInclusionSide) {
        var anchorIdsGuardedWithinThisBundle = bundle.stream()
                .filter(group -> group.relativeTimeRestrictions() != null)
                .flatMap(group -> group.relativeTimeRestrictions().stream())
                .map(RelativeTimeRestriction::anchorRef)
                .collect(Collectors.toSet());
        return bundle.stream()
                .map(group -> subsumedByItsOwnGuardWithinThisBundle(group, anchorIdsGuardedWithinThisBundle)
                        ? Container.<DefaultExpression>empty()
                        : group.toCql(mappingContext, allGroupsById, isInclusionSide))
                .reduce(Container.empty(), AND);
    }

    private static boolean subsumedByItsOwnGuardWithinThisBundle(Group group, Set<String> anchorIdsGuardedWithinThisBundle) {
        return group.id() != null && group.relativeTimeRestrictions() == null
                && anchorIdsGuardedWithinThisBundle.contains(group.id());
    }
}
