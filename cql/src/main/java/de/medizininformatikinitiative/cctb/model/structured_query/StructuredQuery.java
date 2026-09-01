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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StructuredQuery(List<Group> inclusionCriteria, List<Group> exclusionCriteria) {

    public StructuredQuery {
        inclusionCriteria = List.copyOf(inclusionCriteria);
        exclusionCriteria = List.copyOf(exclusionCriteria);
    }

    public static StructuredQuery of(List<Group> inclusionCriteria) {
        return of(inclusionCriteria, List.of());
    }

    @JsonCreator
    public static StructuredQuery of(@JsonProperty("inclusionCriteria") List<Group> inclusionCriteria,
                                     @JsonProperty("exclusionCriteria") List<Group> exclusionCriteria) {
        if (inclusionCriteria == null || inclusionCriteria.isEmpty()) {
            throw new IllegalArgumentException("empty inclusion criteria");
        }
        var resolvedExclusionCriteria = exclusionCriteria == null ? List.<Group>of() : exclusionCriteria;
        validate(inclusionCriteria, resolvedExclusionCriteria);
        return new StructuredQuery(inclusionCriteria, resolvedExclusionCriteria);
    }

    /**
     * Validates cross-group invariants that a single {@link Group} cannot check on its own: unique ids across the
     * whole document, every {@code anchorRef} resolving to a known id, the {@code anchorRef} graph being acyclic,
     * and every group used as an anchor having {@code anchorOccurrence} set (unless its sole criterion is
     * {@code now}, which has exactly one occurrence by definition).
     * <p>
     * An anchor group may have multiple (AND'd) clauses - see {@link Group}'s {@code AnchorDates} design note for
     * how a dependent's window is computed asymmetrically across them (earliest clause bounds {@code maxOffset},
     * latest bounds {@code minOffset}) rather than requiring a single unambiguous witness resource.
     * <p>
     * A {@link RelativeTimeRestriction} always targets exactly one {@code anchorRef} (it is a single
     * {@code String}), so "multiple anchors per dependent group" is unsupported by construction and needs no
     * explicit check here.
     */
    private static void validate(List<Group> inclusionCriteria, List<Group> exclusionCriteria) {
        var allGroups = Stream.concat(inclusionCriteria.stream(), exclusionCriteria.stream()).toList();

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
            var relativeTimeRestriction = group.relativeTimeRestriction();
            if (relativeTimeRestriction != null) {
                var anchorRef = relativeTimeRestriction.anchorRef();
                var anchor = groupsById.get(anchorRef);
                if (anchor == null) {
                    throw new IllegalArgumentException("Unknown anchorRef `%s`%s.".formatted(anchorRef,
                            group.id() == null ? "" : " in group `%s`".formatted(group.id())));
                }
                referencedAsAnchor.add(anchorRef);
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
        var relativeTimeRestriction = group == null ? null : group.relativeTimeRestriction();
        if (relativeTimeRestriction != null) {
            detectCycle(relativeTimeRestriction.anchorRef(), groupsById, path);
        }
        path.remove(groupId);
    }
}
