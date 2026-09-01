package de.medizininformatikinitiative.cctb.model.structured_query;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelativeTimeRestrictionTest {

    @Nested
    class Construction {

        @Test
        void neitherMinOffsetNorMaxOffsetFails() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> RelativeTimeRestriction.of("anchor", null, null))
                    .withMessage("Invalid relative time restriction: at least one of minOffset/maxOffset is required.");
        }

        @Test
        void minOffsetAfterMaxOffsetFails() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> RelativeTimeRestriction.of("anchor", Duration.ofHours(2), Duration.ofHours(1)));
        }

        @Test
        void onlyMinOffset() {
            var relativeTimeRestriction = RelativeTimeRestriction.of("anchor", Duration.ofHours(-3), null);

            assertThat(relativeTimeRestriction.anchorRef()).isEqualTo("anchor");
            assertThat(relativeTimeRestriction.minOffset()).isEqualTo(Duration.ofHours(-3));
            assertThat(relativeTimeRestriction.maxOffset()).isNull();
        }

        @Test
        void onlyMaxOffset() {
            var relativeTimeRestriction = RelativeTimeRestriction.of("anchor", null, Duration.ofHours(30 * 24));

            assertThat(relativeTimeRestriction.anchorRef()).isEqualTo("anchor");
            assertThat(relativeTimeRestriction.minOffset()).isNull();
            assertThat(relativeTimeRestriction.maxOffset()).isEqualTo(Duration.ofDays(30));
        }
    }

    @Nested
    class Deserialization {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Test
        void signedIsoDurations() {
            var relativeTimeRestriction = MAPPER.readValue("""
                    {
                      "anchorRef": "anchor-dementia-diagnosis",
                      "minOffset": "-P3D",
                      "maxOffset": "P0D"
                    }
                    """, RelativeTimeRestriction.class);

            assertThat(relativeTimeRestriction.anchorRef()).isEqualTo("anchor-dementia-diagnosis");
            assertThat(relativeTimeRestriction.minOffset()).isEqualTo(Duration.ofDays(-3));
            assertThat(relativeTimeRestriction.maxOffset()).isEqualTo(Duration.ZERO);
        }

        @Test
        void subDayPrecisionOffset() {
            var relativeTimeRestriction = MAPPER.readValue("""
                    {
                      "anchorRef": "anchor",
                      "maxOffset": "PT72H"
                    }
                    """, RelativeTimeRestriction.class);

            assertThat(relativeTimeRestriction.minOffset()).isNull();
            assertThat(relativeTimeRestriction.maxOffset()).isEqualTo(Duration.ofHours(72));
        }

        @Test
        void missingAnchorRef() {
            assertThatThrownBy(() -> MAPPER.readValue("""
                    {
                      "minOffset": "P0D"
                    }
                    """, RelativeTimeRestriction.class))
                    .hasRootCauseMessage("missing JSON property: anchorRef");
        }

        @Test
        void missingBothOffsets() {
            assertThatThrownBy(() -> MAPPER.readValue("""
                    {
                      "anchorRef": "anchor"
                    }
                    """, RelativeTimeRestriction.class))
                    .hasRootCauseMessage(
                            "Invalid relative time restriction: at least one of minOffset/maxOffset is required.");
        }
    }
}
