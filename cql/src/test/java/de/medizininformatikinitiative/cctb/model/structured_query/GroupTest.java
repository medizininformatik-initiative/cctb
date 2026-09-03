package de.medizininformatikinitiative.cctb.model.structured_query;

import tools.jackson.databind.ObjectMapper;
import de.medizininformatikinitiative.cctb.model.common.TermCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroupTest {

    static final TermCode CONTEXT = TermCode.of("context", "context", "context");
    static final ContextualTermCode TC_1 = ContextualTermCode.of(CONTEXT, TermCode.of("tc", "1", ""));

    @Nested
    class Construction {

        @Test
        void emptyCriteriaFails() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Group.of(List.<List<Criterion>>of()));
        }

        @Test
        void allEmptyClausesFails() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Group.of(List.of(List.<Criterion>of())));
        }

        @Test
        void ofCriterionVarargs() {
            var group = Group.of(ConceptCriterion.of(ContextualConcept.of(TC_1)));

            assertThat(group.id()).isNull();
            assertThat(group.criteria()).hasSize(1);
            assertThat(group.criteria().get(0)).hasSize(1);
        }

        @Test
        void ofWithIdAndAnchorOccurrence() {
            var group = Group.of("anchor", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    Group.AnchorOccurrence.FIRST);

            assertThat(group.id()).isEqualTo("anchor");
            assertThat(group.anchorOccurrence()).isEqualTo(Group.AnchorOccurrence.FIRST);
            assertThat(group.relativeTimeRestrictions()).isNull();
        }

        @Test
        void ofWithRelativeTimeRestriction() {
            var relativeTimeRestriction = RelativeTimeRestriction.of("anchor", Duration.ofHours(-72), Duration.ZERO);
            var group = Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    relativeTimeRestriction);

            assertThat(group.relativeTimeRestrictions()).containsExactly(relativeTimeRestriction);
        }

        @Test
        void ofWithMultipleRelativeTimeRestrictions() {
            var restriction1 = RelativeTimeRestriction.of("anchor-a", Duration.ofHours(-72), Duration.ZERO);
            var restriction2 = RelativeTimeRestriction.of("anchor-b", null, Duration.ofHours(48));
            var group = Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    List.of(restriction1, restriction2));

            assertThat(group.relativeTimeRestrictions()).containsExactly(restriction1, restriction2);
        }

        @Test
        void isNowGroup() {
            assertThat(Group.of("now-anchor", NowCriterion.INSTANCE).isNowGroup()).isTrue();
            assertThat(Group.of(ConceptCriterion.of(ContextualConcept.of(TC_1))).isNowGroup()).isFalse();
        }
    }

    @Nested
    class Deserialization {

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @Test
        void emptyCriteriaFails() {
            assertThatThrownBy(() -> MAPPER.readValue("""
                    {"criteria": []}
                    """, Group.class))
                    .hasRootCauseMessage("empty criteria in group");
        }

        @Test
        void emptyCriteriaFailsWithGroupIdInMessage() {
            assertThatThrownBy(() -> MAPPER.readValue("""
                    {"id": "g1", "criteria": [[]]}
                    """, Group.class))
                    .hasRootCauseMessage("empty criteria in group `g1`");
        }

        @Test
        void minimalGroup() {
            var group = MAPPER.readValue("""
                    {
                      "criteria": [[{
                        "context": {
                          "system": "context",
                          "code": "context",
                          "display": "context"
                        },
                        "termCodes": [{
                          "system": "tc",
                          "code": "1",
                          "display": ""
                        }]
                      }]]
                    }
                    """, Group.class);

            assertThat(group.id()).isNull();
            assertThat(group.criteria()).hasSize(1);
            assertThat(group.relativeTimeRestrictions()).isNull();
            assertThat(group.anchorOccurrence()).isNull();
            assertThat(group.anchorPoint()).isNull();
        }

        @Test
        void anchorGroupWithOccurrenceAndPoint() {
            var group = MAPPER.readValue("""
                    {
                      "id": "anchor-dementia-diagnosis",
                      "anchorOccurrence": "first",
                      "anchorPoint": "start",
                      "criteria": [[{
                        "context": {
                          "system": "context",
                          "code": "context",
                          "display": "context"
                        },
                        "termCodes": [{
                          "system": "tc",
                          "code": "1",
                          "display": ""
                        }]
                      }]]
                    }
                    """, Group.class);

            assertThat(group.id()).isEqualTo("anchor-dementia-diagnosis");
            assertThat(group.anchorOccurrence()).isEqualTo(Group.AnchorOccurrence.FIRST);
            assertThat(group.anchorPoint()).isEqualTo(Group.AnchorPoint.START);
        }

        @Test
        void dependentGroupWithRelativeTimeRestriction() {
            var group = MAPPER.readValue("""
                    {
                      "id": "group-infection-signs-before-diagnosis",
                      "relativeTimeRestrictions": [{
                        "anchorRef": "anchor-dementia-diagnosis",
                        "minOffset": "-P3D",
                        "maxOffset": "P0D"
                      }],
                      "criteria": [[{
                        "context": {
                          "system": "context",
                          "code": "context",
                          "display": "context"
                        },
                        "termCodes": [{
                          "system": "tc",
                          "code": "1",
                          "display": ""
                        }]
                      }]]
                    }
                    """, Group.class);

            assertThat(group.relativeTimeRestrictions()).hasSize(1);
            assertThat(group.relativeTimeRestrictions().get(0).anchorRef()).isEqualTo("anchor-dementia-diagnosis");
            assertThat(group.relativeTimeRestrictions().get(0).minOffset()).isEqualTo(Duration.ofDays(-3));
            assertThat(group.relativeTimeRestrictions().get(0).maxOffset()).isEqualTo(Duration.ZERO);
        }

        @Test
        void dependentGroupWithMultipleRelativeTimeRestrictions() {
            var group = MAPPER.readValue("""
                    {
                      "id": "group-between-a-and-b",
                      "relativeTimeRestrictions": [
                        {"anchorRef": "anchor-a", "minOffset": "P0D"},
                        {"anchorRef": "anchor-b", "maxOffset": "P0D"}
                      ],
                      "criteria": [[{
                        "context": {
                          "system": "context",
                          "code": "context",
                          "display": "context"
                        },
                        "termCodes": [{
                          "system": "tc",
                          "code": "1",
                          "display": ""
                        }]
                      }]]
                    }
                    """, Group.class);

            assertThat(group.relativeTimeRestrictions()).hasSize(2);
            assertThat(group.relativeTimeRestrictions().get(0).anchorRef()).isEqualTo("anchor-a");
            assertThat(group.relativeTimeRestrictions().get(1).anchorRef()).isEqualTo("anchor-b");
        }

        @Test
        void nowCriterionGroup() {
            var group = MAPPER.readValue("""
                    {
                      "id": "anchor-now",
                      "criteria": [[{"type": "now"}]]
                    }
                    """, Group.class);

            assertThat(group.isNowGroup()).isTrue();
        }
    }
}
