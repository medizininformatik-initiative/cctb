package de.medizininformatikinitiative.cctb.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * A time window relative to another {@link Group group's} resolved anchor date.
 * <p>
 * {@code minOffset}/{@code maxOffset} are signed offsets from the anchor date (negative = before the anchor,
 * positive = after), mirroring the ISO 8601 signed-duration convention {@link Duration#parse} already accepts.
 * At least one of {@code minOffset}/{@code maxOffset} is required, mirroring {@link TimeRestriction}'s existing
 * {@code afterDate}/{@code beforeDate} rule.
 *
 * @param anchorRef  the {@code id} of the {@link Group} this restriction is relative to
 * @param minOffset  the offset from the anchor date defining the (inclusive) start of the window, or {@code null}
 *                   for an unbounded start
 * @param maxOffset  the offset from the anchor date defining the (inclusive) end of the window, or {@code null}
 *                   for an unbounded end
 */
public record RelativeTimeRestriction(String anchorRef, Duration minOffset, Duration maxOffset) {

    public RelativeTimeRestriction {
        requireNonNull(anchorRef);
        if (minOffset == null && maxOffset == null) {
            throw new IllegalArgumentException(
                    "Invalid relative time restriction: at least one of minOffset/maxOffset is required.");
        }
        if (minOffset != null && maxOffset != null && minOffset.compareTo(maxOffset) > 0) {
            throw new IllegalArgumentException(
                    "Invalid relative time restriction: minOffset `%s` is after maxOffset `%s` but should not be."
                            .formatted(minOffset, maxOffset));
        }
    }

    public static RelativeTimeRestriction of(String anchorRef, Duration minOffset, Duration maxOffset) {
        return new RelativeTimeRestriction(anchorRef, minOffset, maxOffset);
    }

    @JsonCreator
    public static RelativeTimeRestriction create(@JsonProperty("anchorRef") String anchorRef,
                                                 @JsonProperty("minOffset") String minOffset,
                                                 @JsonProperty("maxOffset") String maxOffset) {
        return new RelativeTimeRestriction(requireNonNull(anchorRef, "missing JSON property: anchorRef"),
                minOffset == null ? null : Duration.parse(minOffset),
                maxOffset == null ? null : Duration.parse(maxOffset));
    }
}
