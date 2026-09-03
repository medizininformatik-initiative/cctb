package de.medizininformatikinitiative.cctb.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @author Alexander Kiel
 * <p>
 * {@code inclusionCriteria}/{@code exclusionCriteria} are each an OR-array of <em>bundles</em> - an AND-array of
 * {@link Group Groups}, every one of which is unconditionally required for that bundle to be satisfied. A group
 * referenced via {@code anchorRef} from a group in a *different* bundle contributes only its resolved date there,
 * never its own truth - requiredness follows bundle membership, not referenceability. Both sides are uniformly
 * OR-of-AND; see the CCDL relative-time-constraint draft, section 1 and 3, for the full reasoning.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredQuery(List<List<Group>> inclusionCriteria, List<List<Group>> exclusionCriteria) {

    public StructuredQuery {
        inclusionCriteria = inclusionCriteria.stream().map(List::copyOf).toList();
        exclusionCriteria = exclusionCriteria.stream().map(List::copyOf).toList();
    }

    public static StructuredQuery of(List<List<Group>> inclusionCriteria) {
        return of(inclusionCriteria, List.of());
    }

    @JsonCreator
    public static StructuredQuery of(@JsonProperty("inclusionCriteria") List<List<Group>> inclusionCriteria,
                                     @JsonProperty("exclusionCriteria") List<List<Group>> exclusionCriteria) {
        if (inclusionCriteria == null || inclusionCriteria.isEmpty()) {
            throw new IllegalArgumentException("empty inclusion criteria");
        }
        if (inclusionCriteria.stream().anyMatch(bundle -> bundle == null || bundle.isEmpty())) {
            throw new IllegalArgumentException("empty bundle in inclusion criteria");
        }
        var resolvedExclusionCriteria = exclusionCriteria == null ? List.<List<Group>>of() : exclusionCriteria;
        if (resolvedExclusionCriteria.stream().anyMatch(bundle -> bundle == null || bundle.isEmpty())) {
            throw new IllegalArgumentException("empty bundle in exclusion criteria");
        }
        validate(inclusionCriteria, resolvedExclusionCriteria);
        return new StructuredQuery(inclusionCriteria, resolvedExclusionCriteria);
    }

    /**
     * Validates cross-group invariants that a single {@link Group} cannot check on its own: unique ids across the
     * whole document, every {@code anchorRef} (of every entry of every group's {@code relativeTimeRestrictions})
     * resolving to a known id, the {@code anchorRef} graph being acyclic, and every group used as an anchor
     * having {@code anchorOccurrence} set (unless its sole criterion is {@code now}, which has exactly one
     * occurrence by definition).
     * <p>
     * An anchor group may have multiple (AND'd) clauses - see {@link Group}'s {@code AnchorDates} design note for
     * how a dependent's window is computed asymmetrically across them (earliest clause bounds {@code maxOffset},
     * latest bounds {@code minOffset}) rather than requiring a single unambiguous witness resource.
     * <p>
     * A group's {@code relativeTimeRestrictions} may hold more than one entry, each with its own {@code
     * anchorRef} - so a single group can now have multiple outgoing anchor edges, not just one. Cycle detection
     * (see {@link #detectCycle}) explores all of them.
     */
    private static void validate(List<List<Group>> inclusionCriteria, List<List<Group>> exclusionCriteria) {
        var allGroups = Stream.concat(inclusionCriteria.stream(), exclusionCriteria.stream())
                .flatMap(List::stream).toList();

        var seenIds = new HashSet<String>();
        for (var group : allGroups) {
            if (group.id() != null && !seenIds.add(group.id())) {
                throw new IllegalArgumentException("Duplicate group id `%s`.".formatted(group.id()));
            }
        }

        var groupsById = new HashMap<String, Group>();
        for (var group : allGroups) {
            if (group.id() != null) {
                groupsById.put(group.id(), group);
            }
        }

        var referencedAsAnchor = new HashSet<String>();
        for (var group : allGroups) {
            var relativeTimeRestrictions = group.relativeTimeRestrictions();
            if (relativeTimeRestrictions != null) {
                for (var relativeTimeRestriction : relativeTimeRestrictions) {
                    var anchorRef = relativeTimeRestriction.anchorRef();
                    var anchor = groupsById.get(anchorRef);
                    if (anchor == null) {
                        throw new IllegalArgumentException("Unknown anchorRef `%s`%s.".formatted(anchorRef,
                                group.id() == null ? "" : " in group `%s`".formatted(group.id())));
                    }
                    referencedAsAnchor.add(anchorRef);
                }
            }
        }

        for (var anchorId : referencedAsAnchor) {
            var anchor = groupsById.get(anchorId);
            if (anchor.anchorOccurrence() == null && !anchor.isNowGroup()) {
                throw new IllegalArgumentException(
                        "Group `%s` is used as an anchor and therefore requires `anchorOccurrence` to be set."
                                .formatted(anchorId));
            }
        }

        for (var group : allGroups) {
            if (group.id() != null) {
                detectCycle(group.id(), groupsById, new LinkedHashSet<>());
            }
        }
    }

    private static void detectCycle(String groupId, Map<String, Group> groupsById, LinkedHashSet<String> path) {
        if (!path.add(groupId)) {
            throw new IllegalArgumentException(
                    "Cyclic anchorRef graph detected: %s -> %s.".formatted(String.join(" -> ", path), groupId));
        }
        var group = groupsById.get(groupId);
        var relativeTimeRestrictions = group == null ? null : group.relativeTimeRestrictions();
        if (relativeTimeRestrictions != null) {
            for (var relativeTimeRestriction : relativeTimeRestrictions) {
                detectCycle(relativeTimeRestriction.anchorRef(), groupsById, path);
            }
        }
        path.remove(groupId);
    }
}
