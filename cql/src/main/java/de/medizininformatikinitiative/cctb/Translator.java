package de.medizininformatikinitiative.cctb;

import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.cql.CodeSystemDefinition;
import de.medizininformatikinitiative.cctb.model.cql.Container;
import de.medizininformatikinitiative.cctb.model.cql.DefaultExpression;
import de.medizininformatikinitiative.cctb.model.structured_query.Group;
import de.medizininformatikinitiative.cctb.model.structured_query.StructuredQuery;
import de.medizininformatikinitiative.cctb.model.structured_query.TranslationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static de.medizininformatikinitiative.cctb.model.cql.Container.AND;
import static de.medizininformatikinitiative.cctb.model.cql.Container.AND_NOT;
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
        var inclusionExpr = groupsExpr(structuredQuery.inclusionCriteria(), allGroupsById, true);
        var exclusionExpr = groupsExpr(structuredQuery.exclusionCriteria(), allGroupsById, false);

        return exclusionExpr.isEmpty()
                ? inclusionExpr.moveToPatientContext("InInitialPopulation")
                : AND_NOT.apply(inclusionExpr.moveToPatientContext("Inclusion"),
                        exclusionExpr.moveToPatientContext("Exclusion"))
                .moveToPatientContext("InInitialPopulation");
    }

    /**
     * Collects every {@link Group} of {@code structuredQuery}, both inclusion and exclusion side, keyed by
     * {@link Group#id}, so a group's {@code relativeTimeRestriction} can resolve its {@code anchorRef} regardless
     * of which side the referenced anchor group lives on.
     */
    private static Map<String, Group> collectGroupsById(StructuredQuery structuredQuery) {
        var groupsById = new HashMap<String, Group>();
        Stream.concat(structuredQuery.inclusionCriteria().stream(), structuredQuery.exclusionCriteria().stream())
                .filter(group -> group.id() != null)
                .forEach(group -> groupsById.put(group.id(), group));
        return groupsById;
    }

    /**
     * Builds the boolean expression of one side (inclusion or exclusion) of a {@link StructuredQuery} as the
     * top-level AND of its {@link Group Groups} - the top level is AND-only on both sides (see the CCDL relative
     * time constraint draft): unchanged from before on the inclusion side, and a deliberate change on the
     * exclusion side (previously OR-of-AND/DNF at the top level, now AND, matching a group's own criteria - which
     * still expresses the old DNF shape - carrying the polarity that used to live at the top level).
     *
     * @param groups          the groups of one side of a {@link StructuredQuery}
     * @param allGroupsById   every group of the whole {@link StructuredQuery}, used to resolve {@code anchorRef}s
     * @param isInclusionSide {@code true} for {@code inclusionCriteria}, {@code false} for {@code exclusionCriteria}
     * @return a {@link Container} of the boolean expression together with the used {@link CodeSystemDefinition
     * CodeSystemDefinitions}
     */
    private Container<DefaultExpression> groupsExpr(List<Group> groups, Map<String, Group> allGroupsById,
                                                     boolean isInclusionSide) {
        return groups.stream()
                .map(group -> group.toCql(mappingContext, allGroupsById, isInclusionSide))
                .reduce(Container.empty(), AND);
    }
}
