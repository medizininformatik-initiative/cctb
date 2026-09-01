package de.medizininformatikinitiative.cctb.model.structured_query;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import de.medizininformatikinitiative.cctb.model.MappingContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Alexander Kiel
 */
class CriterionTest {

    @Nested
    class FromJson {

        ObjectMapper mapper = new ObjectMapper();

        @Test
        void emptyObject() {
            assertThatThrownBy(() -> mapper.readValue("{}", Criterion.class))
                    .isInstanceOf(JacksonException.class)
                    .hasRootCauseMessage("missing JSON property: context");
        }

        @Test
        void nowCriterion() throws JacksonException {
            var criterion = mapper.readValue("""
                    {"type": "now"}
                    """, Criterion.class);

            assertThat(criterion).isSameAs(NowCriterion.INSTANCE);
        }

        @Test
        void nowCriterionIgnoresOtherProperties() throws JacksonException {
            // a "now" criterion is recognized purely by its "type" discriminator, before context/termCodes are
            // required - this must not throw even though context/termCodes are absent
            var criterion = mapper.readValue("""
                    {"type": "now", "timeRestriction": {"afterDate": "2020-01-01"}}
                    """, Criterion.class);

            assertThat(criterion).isSameAs(NowCriterion.INSTANCE);
        }

        @Test
        void unknownValueFilterType() {
            assertThatThrownBy(() -> mapper.readValue("""
                    {
                      "context": {
                        "system": "context",
                        "code": "context",
                        "display": "context"
                      },
                      "termCodes": [],
                      "valueFilter": {
                        "type": "foo"
                      }
                    }
                    """, Criterion.class))
                    .isInstanceOf(JacksonException.class)
                    .hasRootCauseMessage("unknown valueFilter type: foo");
        }

        @Test
        void emptyTimeRestrictionIsIgnored() throws JacksonException {
            assertThat(mapper.readValue("""
                    {
                      "context": {
                        "system": "context",
                        "code": "context",
                        "display": "context"
                      },
                      "termCodes": [],
                      "timeRestriction": {}
                    }
                    """, Criterion.class).timeRestriction()).isNull();
        }

        @Test
        void invalidTimeRestrictionAfterDate() {
            assertThatThrownBy(() -> mapper.readValue("""
                    {
                      "context": {
                        "system": "context",
                        "code": "context",
                        "display": "context"
                      },
                      "termCodes": [],
                      "timeRestriction": {
                        "afterDate": "2023-02-29"
                      }
                    }
                    """, Criterion.class))
                    .isInstanceOf(JacksonException.class)
                    .hasRootCauseMessage("Invalid date 'February 29' as '2023' is not a leap year");
        }

        @Test
        void invalidTimeRestrictionBeforeDate() {
            assertThatThrownBy(() -> mapper.readValue("""
                    {
                      "context": {
                        "system": "context",
                        "code": "context",
                        "display": "context"
                      },
                      "termCodes": [],
                      "timeRestriction": {
                        "afterDate": "2023-02-28",
                        "beforeDate": "2023-02-29"
                      }
                    }
                    """, Criterion.class))
                    .isInstanceOf(JacksonException.class)
                    .hasRootCauseMessage("Invalid date 'February 29' as '2023' is not a leap year");
        }

        @Test
        void invalidTimeRestriction() {
            assertThatThrownBy(() -> mapper.readValue("""
                    {
                      "context": {
                        "system": "context",
                        "code": "context",
                        "display": "context"
                      },
                      "termCodes": [],
                      "timeRestriction": {
                        "afterDate": "2024-11-20",
                        "beforeDate": "2024-11-19"
                      }
                    }
                    """, Criterion.class))
                    .isInstanceOf(JacksonException.class)
                    .hasRootCauseMessage("Invalid time restriction: beforeDate `2024-11-19` is before afterDate `2024-11-20` but should not be.");
        }

        @Test
        void timeRestrictionSameDay() throws JacksonException {
            assertThat(mapper.readValue("""
                    {
                      "context": {
                        "system": "context",
                        "code": "context",
                        "display": "context"
                      },
                      "termCodes": [],
                      "timeRestriction": {
                        "afterDate": "2024-11-20",
                        "beforeDate": "2024-11-20"
                      }
                    }
                    """, Criterion.class).timeRestriction())
                    .isEqualTo(TimeRestriction.of(LocalDate.of(2024, 11, 20), LocalDate.of(2024, 11, 20)));
        }

        @Nested
        class WithReferenceAttributeFilter {

            @Test
            void invalidTimeRestriction() {
                assertThatThrownBy(() -> mapper.readValue("""
                        {
                           "context": {
                             "code": "Specimen",
                             "system": "fdpg.mii.cds",
                             "version": "1.0.0",
                             "display": "Bioprobe"
                           },
                           "termCodes": [
                             {
                               "code": "119364003",
                               "system": "http://snomed.info/sct",
                               "version": "http://snomed.info/sct/900000000000207008/version/20220930",
                               "display": "Serum specimen"
                             }
                           ],
                           "attributeFilters": [
                             {
                               "type": "reference",
                               "attributeCode": {
                                 "code": "festgestellteDiagnose",
                                 "display": "Festgestellte Diagnose",
                                 "system": "http://hl7.org/fhir/StructureDefinition"
                               },
                               "criteria": [
                                 {
                                   "termCodes": [
                                     {
                                       "code": "E13.9",
                                       "system": "http://fhir.de/CodeSystem/bfarm/icd-10-gm",
                                       "version": "2023",
                                       "display": "Sonstiger näher bezeichneter Diabetes mellitus : Ohne Komplikationen"
                                     }
                                   ],
                                   "context": {
                                     "code": "Diagnose",
                                     "system": "fdpg.mii.cds",
                                     "version": "1.0.0",
                                     "display": "Diagnose"
                                   },
                                   "timeRestriction": {
                                     "afterDate": "2024-11-20",
                                     "beforeDate": "2024-11-19"
                                   }
                                 }
                               ]
                             }
                           ]
                        }
                        """, Criterion.class))
                        .isInstanceOf(JacksonException.class)
                        .hasRootCauseMessage("Invalid time restriction: beforeDate `2024-11-19` is before afterDate `2024-11-20` but should not be.");
            }

            @Test
            void timeRestrictionSameDay() throws JacksonException {
                var criterion = mapper.readValue("""
                        {
                           "context": {
                             "code": "Specimen",
                             "system": "fdpg.mii.cds",
                             "version": "1.0.0",
                             "display": "Bioprobe"
                           },
                           "termCodes": [
                             {
                               "code": "119364003",
                               "system": "http://snomed.info/sct",
                               "version": "http://snomed.info/sct/900000000000207008/version/20220930",
                               "display": "Serum specimen"
                             }
                           ],
                           "attributeFilters": [
                             {
                               "type": "reference",
                               "attributeCode": {
                                 "code": "festgestellteDiagnose",
                                 "display": "Festgestellte Diagnose",
                                 "system": "http://hl7.org/fhir/StructureDefinition"
                               },
                               "criteria": [
                                 {
                                   "termCodes": [
                                     {
                                       "code": "E13.9",
                                       "system": "http://fhir.de/CodeSystem/bfarm/icd-10-gm",
                                       "version": "2023",
                                       "display": "Sonstiger näher bezeichneter Diabetes mellitus : Ohne Komplikationen"
                                     }
                                   ],
                                   "context": {
                                     "code": "Diagnose",
                                     "system": "fdpg.mii.cds",
                                     "version": "1.0.0",
                                     "display": "Diagnose"
                                   },
                                   "timeRestriction": {
                                     "afterDate": "2024-11-20",
                                     "beforeDate": "2024-11-20"
                                   }
                                 }
                               ]
                             }
                           ]
                        }
                        """, Criterion.class);

                var attributeFilter = (ReferenceAttributeFilter) criterion.attributeFilters().get(0);
                var referencedCriterion = attributeFilter.criteria().get(0);

                assertThat(referencedCriterion.timeRestriction())
                        .isEqualTo(TimeRestriction.of(LocalDate.of(2024, 11, 20), LocalDate.of(2024, 11, 20)));
            }
        }
    }

    @Nested
    class NowCriterionBehavior {

        private static final MappingContext EMPTY_MAPPING_CONTEXT = MappingContext.of();

        @Test
        void alwaysMatches() {
            de.medizininformatikinitiative.cctb.Assertions.assertThat(NowCriterion.INSTANCE.toCql(EMPTY_MAPPING_CONTEXT))
                    .printsTo("""
                            library Retrieve version '1.0.0'
                            using FHIR version '4.0.0'
                            include FHIRHelpers version '4.0.0'

                            context Patient

                            define Criterion:
                              true
                            """);
        }

        @Test
        void dateValuesExprIsNow() {
            de.medizininformatikinitiative.cctb.Assertions.assertThat(
                            NowCriterion.INSTANCE.dateValuesExpr(EMPTY_MAPPING_CONTEXT, Group.AnchorPoint.START))
                    .printsTo("""
                            library Retrieve version '1.0.0'
                            using FHIR version '4.0.0'
                            include FHIRHelpers version '4.0.0'
                            """);
        }

        @Test
        void toReferencesCqlIsUnsupported() {
            assertThatThrownBy(() -> NowCriterion.INSTANCE.toReferencesCql(EMPTY_MAPPING_CONTEXT))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
