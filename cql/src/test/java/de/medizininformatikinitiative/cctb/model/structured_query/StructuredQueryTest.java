package de.medizininformatikinitiative.cctb.model.structured_query;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.ValueInstantiationException;
import de.medizininformatikinitiative.cctb.model.common.TermCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Alexander Kiel
 */
class StructuredQueryTest {

    static final TermCode CONTEXT = TermCode.of("context", "context", "context");
    static final TermCode TC_1_TC = TermCode.of("tc", "1", "");
    static final TermCode TC_2_TC = TermCode.of("tc", "2", "");
    static final ContextualTermCode TC_1 = ContextualTermCode.of(CONTEXT, TC_1_TC);
    static final ContextualTermCode TC_2 = ContextualTermCode.of(CONTEXT, TC_2_TC);

    @Nested
    class FromJson {

        @Test
        void noInclusionCriteria() {
            var mapper = new ObjectMapper();

            assertThrows(ValueInstantiationException.class, () -> mapper.readValue("""
                    {"inclusionCriteria": []}
                    """, StructuredQuery.class));
        }

        @Test
        void oneInclusionCriteria() throws Exception {
            var mapper = new ObjectMapper();

            var structuredQuery = mapper.readValue("""
                    {"inclusionCriteria": [[{"criteria": [[{
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
                    }]]}]]}
                    """, StructuredQuery.class);

            assertEquals(ContextualConcept.of(TC_1),
                    structuredQuery.inclusionCriteria().get(0).get(0).criteria().get(0).get(0).getConcept());
        }

        @Test
        void additionalPropertyIsIgnored() throws Exception {
            var mapper = new ObjectMapper();

            var structuredQuery = mapper.readValue("""
                    {"foo-151633": "bar-151639",
                     "inclusionCriteria": [[{"criteria": [[{
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
                    }]]}]]}
                    """, StructuredQuery.class);

            assertEquals(ContextualConcept.of(TC_1),
                    structuredQuery.inclusionCriteria().get(0).get(0).criteria().get(0).get(0).getConcept());
        }

        @Test
        void twoInclusionCriteriaAnd() throws Exception {
            var mapper = new ObjectMapper();

            var structuredQuery = mapper.readValue("""
                    {"inclusionCriteria": [[{"criteria": [[{
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
                    }], [{
                      "context": {
                          "system": "context",
                          "code": "context",
                          "display": "context"
                      },
                      "termCodes": [{
                        "system": "tc",
                        "code": "2",
                        "display": ""
                      }]
                    }]]}]]}
                    """, StructuredQuery.class);

            assertEquals(ContextualConcept.of(TC_1),
                    structuredQuery.inclusionCriteria().get(0).get(0).criteria().get(0).get(0).getConcept());
            assertEquals(ContextualConcept.of(TC_2),
                    structuredQuery.inclusionCriteria().get(0).get(0).criteria().get(1).get(0).getConcept());
        }

        @Test
        void twoInclusionCriteriaOr() throws Exception {
            var mapper = new ObjectMapper();

            var structuredQuery = mapper.readValue("""
                    {"inclusionCriteria": [[{"criteria": [[{
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
                    }, {
                      "context": {
                          "system": "context",
                          "code": "context",
                          "display": "context"
                      },
                      "termCodes": [{
                        "system": "tc",
                        "code": "2",
                        "display": ""
                      }]
                    }]]}]]}
                    """, StructuredQuery.class);

            assertEquals(ContextualConcept.of(TC_1),
                    structuredQuery.inclusionCriteria().get(0).get(0).criteria().get(0).get(0).getConcept());
            assertEquals(ContextualConcept.of(TC_2),
                    structuredQuery.inclusionCriteria().get(0).get(0).criteria().get(0).get(1).getConcept());
        }

        @Test
        void oneInclusionCriteria_oneExclusionCriteria() throws Exception {
            var mapper = new ObjectMapper();

            var structuredQuery = mapper.readValue("""
                    {"inclusionCriteria": [[{"criteria": [[{
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
                    }]]}]], "exclusionCriteria": [[{"criteria": [[{
                      "context": {
                        "system": "context",
                        "code": "context",
                        "display": "context"
                      },
                      "termCodes": [{
                        "system": "tc",
                        "code": "2",
                        "display": ""
                      }]
                    }]]}]]}
                    """, StructuredQuery.class);

            assertEquals(ContextualConcept.of(TC_1),
                    structuredQuery.inclusionCriteria().get(0).get(0).criteria().get(0).get(0).getConcept());
            assertEquals(ContextualConcept.of(TC_2),
                    structuredQuery.exclusionCriteria().get(0).get(0).criteria().get(0).get(0).getConcept());
        }

        @Test
        void twoBundlesOr() throws Exception {
            var mapper = new ObjectMapper();

            var structuredQuery = mapper.readValue("""
                    {"inclusionCriteria": [[{"criteria": [[{
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
                    }]]}], [{"criteria": [[{
                      "context": {
                        "system": "context",
                        "code": "context",
                        "display": "context"
                      },
                      "termCodes": [{
                        "system": "tc",
                        "code": "2",
                        "display": ""
                      }]
                    }]]}]]}
                    """, StructuredQuery.class);

            assertEquals(2, structuredQuery.inclusionCriteria().size());
            assertEquals(ContextualConcept.of(TC_1),
                    structuredQuery.inclusionCriteria().get(0).get(0).criteria().get(0).get(0).getConcept());
            assertEquals(ContextualConcept.of(TC_2),
                    structuredQuery.inclusionCriteria().get(1).get(0).criteria().get(0).get(0).getConcept());
        }
    }

    @Nested
    class Validation {

        @Test
        void emptyInclusionCriteria() {
            assertThrows(IllegalArgumentException.class, () -> StructuredQuery.of(List.of()));
        }

        @Test
        void emptyBundle() {
            assertThrows(IllegalArgumentException.class, () -> StructuredQuery.of(List.of(List.of())));
        }

        @Test
        void duplicateGroupId() {
            var bundle = List.of(
                    Group.of("g1", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1))))),
                    Group.of("g1", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_2))))));

            var message = assertThrows(IllegalArgumentException.class,
                    () -> StructuredQuery.of(List.of(bundle))).getMessage();

            assertEquals("Duplicate group id `g1`.", message);
        }

        @Test
        void duplicateGroupIdAcrossBundles() {
            var bundle1 = List.of(Group.of("g1", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1))))));
            var bundle2 = List.of(Group.of("g1", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_2))))));

            var message = assertThrows(IllegalArgumentException.class,
                    () -> StructuredQuery.of(List.of(bundle1, bundle2))).getMessage();

            assertEquals("Duplicate group id `g1`.", message);
        }

        @Test
        void danglingAnchorRef() {
            var bundle = List.of(Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    RelativeTimeRestriction.of("unknown-anchor", Duration.ofHours(1), null)));

            var message = assertThrows(IllegalArgumentException.class,
                    () -> StructuredQuery.of(List.of(bundle))).getMessage();

            assertEquals("Unknown anchorRef `unknown-anchor` in group `dependent`.", message);
        }

        @Test
        void anchorRefCycle() {
            var bundle = List.of(
                    Group.of("a", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                            RelativeTimeRestriction.of("b", Duration.ofHours(1), null), Group.AnchorOccurrence.FIRST),
                    Group.of("b", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_2)))),
                            RelativeTimeRestriction.of("a", Duration.ofHours(1), null), Group.AnchorOccurrence.FIRST));

            var message = assertThrows(IllegalArgumentException.class,
                    () -> StructuredQuery.of(List.of(bundle))).getMessage();

            assertEquals(true, message.startsWith("Cyclic anchorRef graph detected:"));
        }

        @Test
        void anchorRefSelfCycle() {
            var bundle = List.of(Group.of("a", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    RelativeTimeRestriction.of("a", Duration.ofHours(1), null), Group.AnchorOccurrence.FIRST));

            assertThrows(IllegalArgumentException.class, () -> StructuredQuery.of(List.of(bundle)));
        }

        @Test
        void multiClauseAnchorGroupIsAllowed() {
            // AND-groups as anchors are supported (see Group's AnchorDates design note for how a dependent's
            // window is computed asymmetrically across the anchor's clauses) - this is a regression guard against
            // reintroducing the single-OR-clause-only restriction this used to have.
            var anchor = Group.of("anchor", List.of(
                    List.of(ConceptCriterion.of(ContextualConcept.of(TC_1))),
                    List.of(ConceptCriterion.of(ContextualConcept.of(TC_2)))), Group.AnchorOccurrence.FIRST);
            var dependent = Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    RelativeTimeRestriction.of("anchor", Duration.ofHours(1), null));

            assertDoesNotThrow(() -> StructuredQuery.of(List.of(List.of(anchor, dependent))));
        }

        @Test
        void missingAnchorOccurrence() {
            var anchor = Group.of("anchor", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))));
            var dependent = Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_2)))),
                    RelativeTimeRestriction.of("anchor", Duration.ofHours(1), null));

            var message = assertThrows(IllegalArgumentException.class,
                    () -> StructuredQuery.of(List.of(List.of(anchor, dependent)))).getMessage();

            assertEquals("Group `anchor` is used as an anchor and therefore requires `anchorOccurrence` to be set.",
                    message);
        }

        @Test
        void nowAnchorNeedsNoAnchorOccurrence() {
            var anchor = Group.of("now-anchor", NowCriterion.INSTANCE);
            var dependent = Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    RelativeTimeRestriction.of("now-anchor", Duration.ofHours(-1), null));

            StructuredQuery.of(List.of(List.of(anchor, dependent)));
        }

        @Test
        void anchorReferencedFromOtherSideOfDocument() {
            var anchor = Group.of("anchor", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    Group.AnchorOccurrence.FIRST);
            var dependentExclusion = Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_2)))),
                    RelativeTimeRestriction.of("anchor", null, Duration.ofHours(1)));

            StructuredQuery.of(List.of(List.of(anchor)), List.of(List.of(dependentExclusion)));
        }

        @Test
        void anchorReferencedFromDifferentBundle() {
            // Requiredness follows bundle membership, not referenceability (new-constraint-draft.md §4):
            // "anchor" is required only for bundle 1's own path; bundle 2's dependent merely dates against it.
            var anchor = Group.of("anchor", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    Group.AnchorOccurrence.FIRST);
            var otherBundleGroup = Group.of("g2", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))));
            var dependent = Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_2)))),
                    RelativeTimeRestriction.of("anchor", Duration.ofHours(-1), null));

            assertDoesNotThrow(() -> StructuredQuery.of(List.of(List.of(anchor), List.of(otherBundleGroup, dependent))));
        }

        @Test
        void multipleRelativeTimeRestrictionsBothMustResolve() {
            var anchorA = Group.of("anchor-a", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    Group.AnchorOccurrence.FIRST);
            var anchorB = Group.of("anchor-b", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_2)))),
                    Group.AnchorOccurrence.LAST);
            var between = Group.of("between", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(TC_1)))),
                    List.of(RelativeTimeRestriction.of("anchor-a", Duration.ZERO, null),
                            RelativeTimeRestriction.of("anchor-b", null, Duration.ZERO)));

            assertDoesNotThrow(() -> StructuredQuery.of(List.of(List.of(anchorA, anchorB, between))));
        }
    }
}
