package de.medizininformatikinitiative.cctb;

import de.medizininformatikinitiative.cctb.model.AttributeMapping;
import de.medizininformatikinitiative.cctb.model.Mapping;
import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.MappingTreeBase;
import de.medizininformatikinitiative.cctb.model.structured_query.*;
import tools.jackson.databind.ObjectMapper;
import de.medizininformatikinitiative.cctb.model.common.TermCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static de.medizininformatikinitiative.cctb.Assertions.assertThat;
import static de.medizininformatikinitiative.cctb.Util.*;
import static de.medizininformatikinitiative.cctb.model.common.Comparator.LESS_THAN;
import static de.medizininformatikinitiative.cctb.model.Mapping.TimeRestrictionMapping.Type.DATE_TIME;
import static de.medizininformatikinitiative.cctb.model.Mapping.TimeRestrictionMapping.Type.PERIOD;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Alexander Kiel
 */
class TranslatorTest {

    static final TermCode CONTEXT = TermCode.of("context", "context", "context");
    static final ContextualTermCode COMBINED_CONSENT = ContextualTermCode.of(CONTEXT,
            TermCode.of("mii.abide", "combined-consent", ""));
    static final ContextualTermCode AGE = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://snomed.info/sct", "424144002", "Current chronological age"));
    static final ContextualTermCode GENDER = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://snomed.info/sct", "263495000", "Gender"));
    static final TermCode CONTEXT_CONSENT = TermCode.of("fdpg.mii.cds", "Einwilligung", "Einwilligung");
    static final ContextualTermCode CONSENT_MDAT = ContextualTermCode.of(CONTEXT_CONSENT,
            TermCode.of("urn:oid:2.16.840.1.113883.3.1937.777.24.5.3",
                    "2.16.840.1.113883.3.1937.777.24.5.3.8",
                    "MDAT wissenschaftlich nutzen EU DSGVO NIVEAU"));
    static final TermCode CONTEXT_ENCOUNTER = TermCode.of("fdpg.mii.cds", "Fall", "Fall");
    static final ContextualTermCode ENCOUNTER_CLASS = ContextualTermCode.of(CONTEXT_ENCOUNTER,
            TermCode.of("http://terminology.hl7.org/CodeSystem/v3-ActCode",
                    "VR", "virtual"));
    static final ContextualTermCode C71 = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "C71",
                    "Malignant neoplasm of brain"));
    static final ContextualTermCode C71_0 = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "C71.0",
                    "Malignant neoplasm of frontal lobe"));
    static final ContextualTermCode C71_1 = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "C71.1",
                    "Malignant neoplasm of temporal lobe"));
    static final ContextualTermCode PLATELETS = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://loinc.org", "26515-7", "Platelets"));
    static final ContextualTermCode FRAILTY_SCORE = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://snomed.info/sct",
                    "713636003", "Canadian Study of Health and Aging Clinical Frailty Scale score"));
    static final TermCode VERY_FIT = TermCode.of(
            "https://www.netzwerk-universitaetsmedizin.de/fhir/CodeSystem/frailty_score", "1",
            "Very Fit");
    static final TermCode WELL = TermCode.of(
            "https://www.netzwerk-universitaetsmedizin.de/fhir/CodeSystem/frailty_score", "2", "Well");
    static final ContextualTermCode COPD = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://snomed.info/sct", "13645005",
                    "Chronic obstructive lung disease (disorder)"));
    static final ContextualTermCode G47_31 = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "G47.31",
                    "Obstruktives Schlafapnoe-Syndrom"));
    static final ContextualTermCode TOBACCO_SMOKING_STATUS = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://loinc.org", "72166-2", "Tobacco smoking status"));
    static final TermCode CURRENT_EVERY_DAY_SMOKER = TermCode.of("http://loinc.org", "LA18976-3",
            "Current every day smoker");
    static final ContextualTermCode HYPERTENSION = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "I10",
                    "Essential (Primary) Hypertension"));
    static final ContextualTermCode SERUM = ContextualTermCode.of(CONTEXT,
            TermCode.of("https://fhir.bbmri.de/CodeSystem/SampleMaterialType", "Serum", "Serum"));
    static final ContextualTermCode TMZ = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://fhir.de/CodeSystem/dimdi/atc", "L01AX03", "Temozolomide"));
    static final ContextualTermCode LIPID = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://fhir.de/CodeSystem/dimdi/atc", "C10AA", "lipid lowering drugs"));
    static final TermCode CONTEXT_LAB = TermCode.of("fdpg.mii.cds", "Laboruntersuchung", "Laboruntersuchung");
    static final ContextualTermCode BLOOD_PRESSURE = ContextualTermCode.of(CONTEXT_LAB,
            TermCode.of("http://loinc.org", "85354-9", "Blood pressure panel with all children optional"));
    static final TermCode CONFIRMED = TermCode.of(
            "http://terminology.hl7.org/CodeSystem/condition-ver-status", "confirmed", "Confirmed");
    static final Map<String, String> CODE_SYSTEM_ALIASES = Map.of(
            "http://fhir.de/CodeSystem/bfarm/icd-10-gm", "icd10",
            "http://loinc.org", "loinc",
            "https://fhir.bbmri.de/CodeSystem/SampleMaterialType", "sample",
            "http://fhir.de/CodeSystem/dimdi/atc", "atc",
            "http://snomed.info/sct", "snomed",
            "http://hl7.org/fhir/administrative-gender", "gender",
            "http://terminology.hl7.org/CodeSystem/condition-ver-status", "ver_status",
            "https://www.netzwerk-universitaetsmedizin.de/fhir/CodeSystem/frailty_score", "frailty_score",
            "urn:oid:2.16.840.1.113883.3.1937.777.24.5.3", "consent",
            "http://terminology.hl7.org/CodeSystem/v3-ActCode", "act_code");
    static final TermCode VERIFICATION_STATUS = TermCode.of("hl7.org", "verificationStatus",
            "verificationStatus");
    static final AttributeMapping VERIFICATION_STATUS_ATTR_MAPPING = AttributeMapping.of(List.of("Coding"),
            VERIFICATION_STATUS, "verificationStatus");

    private static Mapping parseMapping(String s) throws Exception {
        return new ObjectMapper().readValue(s, Mapping.class);
    }

    private static StructuredQuery parseStructuredQuery(String s) throws Exception {
        return new ObjectMapper().readValue(s, StructuredQuery.class);
    }

    @Nested
    class ToCql {

        @Test
        void nonExpandableConcept() {
            var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(ConceptCriterion.of(ContextualConcept.of(C71))))))));

            var message = assertThrows(TranslationException.class, () -> Translator.of().toCql(structuredQuery)).getMessage();

            assertEquals(
                    "Failed to expand the concept ContextualConcept[context=TermCode[system=context, code=context, display=context], concept=Concept[termCodes=[TermCode[system=http://fhir.de/CodeSystem/bfarm/icd-10-gm, code=C71, display=Malignant neoplasm of brain]]]].",
                    message);
        }

        @Test
        void nonMappableConcept() {
            var conceptTree = createTreeWithChildren(C71, C71_0, C71_1);
            var mappingContext = MappingContext.of(Map.of(), conceptTree, CODE_SYSTEM_ALIASES);

            var group = Group.of(List.of(List.of(ConceptCriterion.of(ContextualConcept.of(C71)))));
            var message = assertThrows(TranslationException.class, () -> Translator.of(mappingContext)
                    .toCql(StructuredQuery.of(List.of(List.of(group))))).getMessage();

            assertEquals(
                    "Failed to expand the concept ContextualConcept[context=TermCode[system=context, code=context, display=context], concept=Concept[termCodes=[TermCode[system=http://fhir.de/CodeSystem/bfarm/icd-10-gm, code=C71, display=Malignant neoplasm of brain]]]].",
                    message);
        }

        @Test
        void usage_Documentation() {
            var c71_1 = ContextualTermCode.of(CONTEXT,
                    TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "C71.1",
                            "Malignant neoplasm of brain"));
            var mappings = Map.of(c71_1, Mapping.of(c71_1, "Condition"));
            var conceptTree = createTreeWithoutChildren(c71_1);
            var codeSystemAliases = Map.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "icd10");
            var mappingContext = MappingContext.of(mappings, conceptTree, codeSystemAliases);

            var library = Translator.of(mappingContext).toCql(
                    StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(ConceptCriterion.of(ContextualConcept.of(c71_1)))))))));

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                    
                    context Patient
                    
                    define Criterion:
                      exists [Condition: Code 'C71.1' from icd10]
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void timeRestriction() {
            var c71_1 = ContextualTermCode.of(CONTEXT,
                    TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "C71.1",
                            "Malignant neoplasm of brain"));
            var mappings = Map.of(c71_1,
                    Mapping.of(c71_1, "Condition", null, List.of(), List.of(), Mapping.TimeRestrictionMapping.of("onset", DATE_TIME, PERIOD)));
            var conceptTree = createTreeWithoutChildren(c71_1);
            var codeSystemAliases = Map.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "icd10");
            var mappingContext = MappingContext.of(mappings, conceptTree, codeSystemAliases);

            var library = Translator.of(mappingContext).toCql(StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(
                    ConceptCriterion.of(ContextualConcept.of(c71_1),
                            TimeRestriction.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2))))))))));

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                    
                    context Patient
                    
                    define Criterion:
                      exists (from [Condition: Code 'C71.1' from icd10] C
                        where ToDate(C.onset as dateTime) in Interval[@2020-01-01T, @2020-01-02T] or
                          C.onset overlaps Interval[@2020-01-01T, @2020-01-02T])
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void timeRestriction_missingInMapping() {
            var c71_1 = ContextualTermCode.of(CONTEXT,
                    TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "C71.1",
                            "Malignant neoplasm of brain"));
            var mappings = Map.of(c71_1,
                    Mapping.of(c71_1, "Condition", null, List.of(), List.of()));
            var conceptTree = createTreeWithoutChildren(c71_1);
            var codeSystemAliases = Map.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "icd10");
            var mappingContext = MappingContext.of(mappings, conceptTree, codeSystemAliases);
            var group = Group.of(List.of(List.of(ConceptCriterion.of(ContextualConcept.of(c71_1),
                    TimeRestriction.of(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2))))));
            var query = StructuredQuery.of(List.of(List.of(group)));
            var translator = Translator.of(mappingContext);

            assertThatIllegalStateException().isThrownBy(() -> translator.toCql(query)).withMessage(
                    "Missing time restriction in mapping with key ContextualTermCode[context=TermCode[system=context, code=context, display=context], termCode=TermCode[system=http://fhir.de/CodeSystem/bfarm/icd-10-gm, code=C71.1, display=Malignant neoplasm of brain]].");
        }

        @Test
        void test_Task1() {
            var mappings = Map.of(PLATELETS, Mapping.of(PLATELETS, "Observation", Mapping.PathMapping.of("value", Mapping.PathMapping.Type.QUANTITY)), C71_0,
                    Mapping.of(C71_0, "Condition", null, List.of(),
                            List.of(VERIFICATION_STATUS_ATTR_MAPPING)), C71_1,
                    Mapping.of(C71_1, "Condition", null, List.of(),
                            List.of(VERIFICATION_STATUS_ATTR_MAPPING)), TMZ,
                    Mapping.of(TMZ, "MedicationStatement"));
            var conceptTree = new MappingTreeBase(List.of(
                    createTreeRootWithoutChildren(TMZ),
                    createTreeRootWithChildren(C71, C71_0, C71_1)));
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);
            var group = Group.of(List.of(List.of(
                            ConceptCriterion.of(ContextualConcept.of(C71))
                                    .appendAttributeFilter(ValueSetAttributeFilter.of(VERIFICATION_STATUS, CONFIRMED))),
                    List.of(
                            NumericCriterion.of(ContextualConcept.of(PLATELETS), LESS_THAN, BigDecimal.valueOf(50),
                                    "g/dl")), List.of(ConceptCriterion.of(ContextualConcept.of(TMZ)))));
            var structuredQuery = StructuredQuery.of(List.of(List.of(group)));

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'

                    codesystem atc: 'http://fhir.de/CodeSystem/dimdi/atc'
                    codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                    codesystem loinc: 'http://loinc.org'
                    codesystem ver_status: 'http://terminology.hl7.org/CodeSystem/condition-ver-status'
                    
                    context Unfiltered
                    
                    define L01AX03Ref:
                      from [Medication: Code 'L01AX03' from atc] M
                        return 'Medication/' + M.id
                    
                    context Patient
                    
                    define "Criterion 1":
                      exists (from [Condition: Code 'C71.0' from icd10] C
                        where C.verificationStatus ~ Code 'confirmed' from ver_status) or
                      exists (from [Condition: Code 'C71.1' from icd10] C
                        where C.verificationStatus ~ Code 'confirmed' from ver_status)
                    
                    define "Criterion 2":
                      exists (from [Observation: Code '26515-7' from loinc] O
                        where O.value as Quantity < 50 'g/dl')
                    
                    define "Criterion 3":
                      exists (from [MedicationStatement] M
                        where M.medication.reference in L01AX03Ref)
                    
                    define InInitialPopulation:
                      "Criterion 1" and
                      "Criterion 2" and
                      "Criterion 3"
                    """);
        }

        @Test
        void test_Task2() {
            var mappings = Map.of(HYPERTENSION, Mapping.of(HYPERTENSION, "Condition", null, List.of(),
                            List.of(VERIFICATION_STATUS_ATTR_MAPPING)), SERUM, Mapping.of(SERUM, "Specimen"), LIPID,
                    Mapping.of(LIPID, "MedicationStatement"));
            var conceptTree = new MappingTreeBase(List.of(
                    createTreeRootWithoutChildren(HYPERTENSION),
                    createTreeRootWithoutChildren(SERUM),
                    createTreeRootWithoutChildren(LIPID)));
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);
            var inclusionGroup = Group.of(List.of(List.of(
                                    ConceptCriterion.of(ContextualConcept.of(HYPERTENSION))
                                            .appendAttributeFilter(ValueSetAttributeFilter.of(VERIFICATION_STATUS, CONFIRMED))),
                            List.of(ConceptCriterion.of(ContextualConcept.of(SERUM)))));
            var exclusionGroup = Group.of(List.of(List.of(ConceptCriterion.of(ContextualConcept.of(LIPID)))));
            var structuredQuery = StructuredQuery.of(List.of(List.of(inclusionGroup)), List.of(List.of(exclusionGroup)));

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    codesystem atc: 'http://fhir.de/CodeSystem/dimdi/atc'
                    codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                    codesystem sample: 'https://fhir.bbmri.de/CodeSystem/SampleMaterialType'
                    codesystem ver_status: 'http://terminology.hl7.org/CodeSystem/condition-ver-status'
                    
                    context Unfiltered
                    
                    define C10AARef:
                      from [Medication: Code 'C10AA' from atc] M
                        return 'Medication/' + M.id
                    
                    context Patient
                    
                    define "Criterion 1":
                      exists (from [Condition: Code 'I10' from icd10] C
                        where C.verificationStatus ~ Code 'confirmed' from ver_status)
                    
                    define "Criterion 2":
                      exists [Specimen: Code 'Serum' from sample]
                    
                    define Inclusion:
                      "Criterion 1" and
                      "Criterion 2"
                    
                    define "Criterion 3":
                      exists (from [MedicationStatement] M
                        where M.medication.reference in C10AARef)
                    
                    define Exclusion:
                      "Criterion 3"
                    
                    define InInitialPopulation:
                      Inclusion and
                      not Exclusion
                    """);
        }

        @Test
        void geccoTask2() {
            var mappings = Map.of(FRAILTY_SCORE, Mapping.of(FRAILTY_SCORE, "Observation", Mapping.PathMapping.of("value", Mapping.PathMapping.Type.CODEABLE_CONCEPT)), COPD,
                    Mapping.of(COPD, "Condition", null,
                            List.of(CodeEquivalentModifier.of("verificationStatus", CONFIRMED)), List.of()), G47_31,
                    Mapping.of(G47_31, "Condition", null,
                            List.of(CodeEquivalentModifier.of("verificationStatus", CONFIRMED)), List.of()),
                    TOBACCO_SMOKING_STATUS, Mapping.of(TOBACCO_SMOKING_STATUS, "Observation", Mapping.PathMapping.of("value", Mapping.PathMapping.Type.CODEABLE_CONCEPT)));
            var conceptTree = new MappingTreeBase(List.of(createTreeRootWithoutChildren(COPD), createTreeRootWithoutChildren(G47_31)));
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);
            var inclusionGroup = Group.of(
                    List.of(List.of(ValueSetCriterion.of(ContextualConcept.of(FRAILTY_SCORE), VERY_FIT, WELL))));
            var exclusionGroup = Group.of(
                    List.of(List.of(ConceptCriterion.of(ContextualConcept.of(COPD)),
                            ConceptCriterion.of(ContextualConcept.of(G47_31))), List.of(
                            ValueSetCriterion.of(ContextualConcept.of(TOBACCO_SMOKING_STATUS),
                                    CURRENT_EVERY_DAY_SMOKER))));
            var structuredQuery = StructuredQuery.of(List.of(List.of(inclusionGroup)), List.of(List.of(exclusionGroup)));

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'

                    codesystem frailty_score: 'https://www.netzwerk-universitaetsmedizin.de/fhir/CodeSystem/frailty_score'
                    codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                    codesystem loinc: 'http://loinc.org'
                    codesystem snomed: 'http://snomed.info/sct'
                    codesystem ver_status: 'http://terminology.hl7.org/CodeSystem/condition-ver-status'
                    
                    context Patient
                    
                    define "Criterion 1":
                      exists (from [Observation: Code '713636003' from snomed] O
                        where O.value ~ Code '1' from frailty_score or
                          O.value ~ Code '2' from frailty_score)
                    
                    define Inclusion:
                      "Criterion 1"
                    
                    define "Criterion 2":
                      exists (from [Condition: Code '13645005' from snomed] C
                        where C.verificationStatus ~ Code 'confirmed' from ver_status)
                    
                    define "Criterion 3":
                      exists (from [Condition: Code 'G47.31' from icd10] C
                        where C.verificationStatus ~ Code 'confirmed' from ver_status)
                    
                    define "Criterion 4":
                      exists (from [Observation: Code '72166-2' from loinc] O
                        where O.value ~ Code 'LA18976-3' from loinc)
                    
                    define Exclusion:
                      "Criterion 2" and
                      "Criterion 3" or
                      "Criterion 4"
                    
                    define InInitialPopulation:
                      Inclusion and
                      not Exclusion
                    """);
        }

        @Test
        void onlyFixedCriteria() throws Exception {
            var mapping = parseMapping("""
                    {
                        "resourceType": "Consent",
                        "fixedCriteria": [
                            {
                                "path": "status",
                                "types": ["code"],
                                "value": [
                                    {
                                        "code": "active",
                                        "display": "Active",
                                        "system": "http://hl7.org/fhir/consent-state-codes"
                                    }
                                ]
                            },
                            {
                                "path": "provision.provision.code",
                                "types": ["Coding"],
                                "cardinality": "many",
                                "value": [
                                    {
                                        "code": "2.16.840.1.113883.3.1937.777.24.5.3.5",
                                        "display": "IDAT bereitstellen EU DSGVO NIVEAU",
                                        "system": "urn:oid:2.16.840.1.113883.3.1937.777.24.5.3"
                                    }
                                ]
                            },
                            {
                                "path": "provision.provision.code",
                                "types": ["Coding"],
                                "cardinality": "many",
                                "value": [
                                    {
                                        "code": "2.16.840.1.113883.3.1937.777.24.5.3.2",
                                        "display": "IDAT erheben",
                                        "system": "urn:oid:2.16.840.1.113883.3.1937.777.24.5.3"
                                    }
                                ]
                            }
                        ],
                        "context": {
                            "code": "context",
                            "display": "context",
                            "system": "context"
                        },
                        "key": {
                            "code": "combined-consent",
                            "display": "Einwilligung f\\u00fcr die zentrale Datenanalyse",
                            "system": "mii.abide"
                        },
                        "primaryCode": {
                            "code": "54133-1",
                            "display": "Consent Document",
                            "system": "http://loinc.org"
                        }
                    }
                    """);

            var structuredQuery = parseStructuredQuery("""
                    {
                      "version": "https://medizininformatik-initiative.de/fdpg/StructuredQuery/v3/schema",
                      "display": "",
                      "inclusionCriteria": [[
                        {
                          "criteria": [
                            [
                              {
                                "context": {
                                    "code": "context",
                                    "display": "context",
                                    "system": "context"
                                },
                                "termCodes": [
                                  {
                                    "code": "combined-consent",
                                    "system": "mii.abide",
                                    "display": "Einwilligung für die zentrale Datenanalyse"
                                  }
                                ]
                              }
                            ]
                          ]
                        }
                      ]]
                    }
                    """);

            var conceptTree = createTreeWithoutChildren(COMBINED_CONSENT);
            var mappings = Map.of(COMBINED_CONSENT, mapping);
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    codesystem consent: 'urn:oid:2.16.840.1.113883.3.1937.777.24.5.3'
                    codesystem loinc: 'http://loinc.org'
                    
                    context Patient
                    
                    define Criterion:
                      exists (from [Consent: Code '54133-1' from loinc] C
                        where C.status = 'active' and
                          exists (from C.provision.provision.code C
                            where C ~ Code '2.16.840.1.113883.3.1937.777.24.5.3.5' from consent) and
                          exists (from C.provision.provision.code C
                            where C ~ Code '2.16.840.1.113883.3.1937.777.24.5.3.2' from consent))
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void numericAgeTranslation() throws Exception {
            var mapping = parseMapping("""
                    {
                        "resourceType": "Patient",
                        "context": {
                            "code": "context",
                            "display": "context",
                            "system": "context"
                        },
                        "key": {
                            "code": "424144002",
                            "display": "Current chronological age",
                            "system": "http://snomed.info/sct"
                        }
                    }
                    """);

            var structuredQuery = parseStructuredQuery("""
                    {
                      "version": "https://medizininformatik-initiative.de/fdpg/StructuredQuery/v3/schema",
                      "display": "",
                      "inclusionCriteria": [[
                        {
                          "criteria": [
                            [
                              {
                                "context": {
                                  "code": "context",
                                  "display": "context",
                                  "system": "context"
                                },
                                "termCodes": [
                                  {
                                    "code": "424144002",
                                    "system": "http://snomed.info/sct",
                                    "display": "Current chronological age"
                                  }
                                ],
                                "valueFilter": {
                                  "selectedConcepts": [],
                                  "type": "quantity-comparator",
                                  "unit": {
                                    "code": "a",
                                    "display": "a"
                                  },
                                  "value": 5,
                                  "comparator": "gt"
                                }
                              }
                            ]
                          ]
                        }
                      ]]
                    }
                    """);
            var conceptTree = createTreeWithoutChildren(AGE);
            var mappings = Map.of(AGE, mapping);
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    context Patient
                    
                    define Criterion:
                      AgeInYears() > 5
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void ageRangeTranslation() throws Exception {
            var mapping = parseMapping("""
                    {
                        "resourceType": "Patient",
                        "context": {
                            "code": "context",
                            "display": "context",
                            "system": "context"
                        },
                        "key": {
                            "code": "424144002",
                            "display": "Current chronological age",
                            "system": "http://snomed.info/sct"
                        },
                        "value": {
                          "path": "birthDate",
                          "types": [ "date" ]
                        }
                    }
                    """);

            var structuredQuery = parseStructuredQuery("""
                    {
                      "version": "https://medizininformatik-initiative.de/fdpg/StructuredQuery/v3/schema",
                      "display": "",
                      "inclusionCriteria": [[
                        {
                          "criteria": [
                            [
                              {
                                "context": {
                                  "code": "context",
                                  "display": "context",
                                  "system": "context"
                                },
                                "termCodes": [
                                  {
                                    "code": "424144002",
                                    "system": "http://snomed.info/sct",
                                    "display": "Current chronological age"
                                  }
                                ],
                                "valueFilter": {
                                  "selectedConcepts": [],
                                  "type": "quantity-range",
                                  "unit": {
                                    "code": "a",
                                    "display": "a"
                                  },
                                  "minValue": 5,
                                  "maxValue": 10
                                }
                              }
                            ]
                          ]
                        }
                      ]]
                    }
                    """);
            var conceptTree = createTreeWithoutChildren(AGE);
            var mappings = Map.of(AGE, mapping);
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    context Patient
                    
                    define Criterion:
                      AgeInYears() between 5 and 10
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void numericAgeTranslationInHours() throws Exception {
            var mapping = parseMapping("""
                    {
                        "resourceType": "Patient",
                        "context": {
                            "code": "context",
                            "display": "context",
                            "system": "context"
                        },
                        "key": {
                            "code": "424144002",
                            "display": "Current chronological age",
                            "system": "http://snomed.info/sct"
                        },
                        "value": {
                          "path": "birthDate",
                          "types": [ "date" ]
                        }
                    }
                    """);

            var structuredQuery = parseStructuredQuery("""
                    {
                      "version": "https://medizininformatik-initiative.de/fdpg/StructuredQuery/v3/schema",
                      "display": "",
                      "inclusionCriteria": [[
                        {
                          "criteria": [
                            [
                              {
                                "context": {
                                  "code": "context",
                                  "display": "context",
                                  "system": "context"
                                },
                                "termCodes": [
                                  {
                                    "code": "424144002",
                                    "system": "http://snomed.info/sct",
                                    "display": "Current chronological age"
                                  }
                                ],
                                "valueFilter": {
                                  "selectedConcepts": [],
                                  "type": "quantity-comparator",
                                  "unit": {
                                    "code": "h",
                                    "display": "h"
                                  },
                                  "value": 5,
                                  "comparator": "lt"
                                }
                              }
                            ]
                          ]
                        }
                      ]]
                    }
                    """);
            var conceptTree = createTreeWithoutChildren(AGE);
            var mappings = Map.of(AGE, mapping);
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    context Patient
                    
                    define Criterion:
                      AgeInHours() < 5
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void patientGender() throws Exception {
            var mapping = parseMapping("""
                    {
                      "context": {
                        "code": "Patient",
                        "display": "Patient",
                        "system": "fdpg.mii.cds",
                        "version": "1.0.0"
                      },
                      "key": {
                        "code": "263495000",
                        "display": "Geschlecht",
                        "system": "http://snomed.info/sct"
                      },
                      "resourceType": "Patient",
                      "value": {
                        "path": "gender",
                        "types": [
                          "code"
                        ]
                      }
                    }
                    """);
            var structuredQuery = parseStructuredQuery("""
                    {
                      "version": "https://medizininformatik-initiative.de/fdpg/StructuredQuery/v3/schema",
                      "display": "",
                      "inclusionCriteria": [[
                        {
                          "criteria": [
                            [
                              {
                                "context": {
                                  "code": "context",
                                  "display": "context",
                                  "system": "context"
                                },
                                "termCodes": [
                                  {
                                    "code": "263495000",
                                    "display": "Geschlecht",
                                    "system": "http://snomed.info/sct"
                                  }
                                ],
                                "valueFilter": {
                                  "type": "concept",
                                  "selectedConcepts": [
                                    {
                                      "code": "female",
                                      "system": "http://hl7.org/fhir/administrative-gender",
                                      "display": "Female"
                                    }
                                  ]
                                }
                              }
                            ]
                          ]
                        }
                      ]]
                    }
                    """);
            var conceptTree = createTreeWithoutChildren(GENDER);
            var mappings = Map.of(GENDER, mapping);
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    context Patient
                    
                    define Criterion:
                      Patient.gender = 'female'
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void consent() throws Exception {
            var mapping = parseMapping("""
                    {
                      "context": {
                        "code": "Einwilligung",
                        "display": "Einwilligung",
                        "system": "fdpg.mii.cds",
                        "version": "1.0.0"
                      },
                      "key": {
                        "code": "2.16.840.1.113883.3.1937.777.24.5.3.8",
                        "display": "MDAT wissenschaftlich nutzen EU DSGVO NIVEAU",
                        "system": "urn:oid:2.16.840.1.113883.3.1937.777.24.5.3",
                        "version": "1.0.2"
                      },
                      "resourceType": "Consent",
                      "termCode": {
                        "path": "provision.provision.code",
                        "types": [
                          "CodeableConcept"
                        ],
                        "cardinality": "many"
                      }
                    }
                    """);

            var structuredQuery = parseStructuredQuery("""
                    {
                      "version": "http://to_be_decided.com/draft-1/schema#",
                      "display": "",
                      "inclusionCriteria": [[
                        {
                          "criteria": [
                            [
                              {
                                "context": {
                                  "code": "Einwilligung",
                                  "display": "Einwilligung",
                                  "system": "fdpg.mii.cds",
                                  "version": "1.0.0"
                                },
                                "termCodes": [
                                  {
                                    "code": "2.16.840.1.113883.3.1937.777.24.5.3.8",
                                    "display": "MDAT wissenschaftlich nutzen EU DSGVO NIVEAU",
                                    "system": "urn:oid:2.16.840.1.113883.3.1937.777.24.5.3"
                                  }
                                ]
                              }
                            ]
                          ]
                        }
                      ]]
                    }
                    """);
            var conceptTree = createTreeWithoutChildren(CONSENT_MDAT);
            var mappings = Map.of(CONSENT_MDAT, mapping);
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    codesystem consent: 'urn:oid:2.16.840.1.113883.3.1937.777.24.5.3'
                    
                    context Patient
                    
                    define Criterion:
                      exists (from [Consent] C
                        where exists (from C.provision.provision.code C
                            where C ~ Code '2.16.840.1.113883.3.1937.777.24.5.3.8' from consent))
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void encounter() throws Exception {
            var mapping = parseMapping("""
                    {
                      "context": {
                        "code": "Fall",
                        "display": "Fall",
                        "system": "fdpg.mii.cds",
                        "version": "1.0.0"
                      },
                      "key": {
                        "code": "VR",
                        "display": "virtual",
                        "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                        "version": "9.0.0"
                      },
                      "resourceType": "Encounter",
                      "termCode": {
                        "path": "class",
                        "types": [
                          "Coding"
                        ]
                      }
                    }
                    """);

            var structuredQuery = parseStructuredQuery("""
                    {
                      "version": "http://to_be_decided.com/draft-1/schema#",
                      "display": "",
                      "inclusionCriteria": [[
                        {
                          "criteria": [
                            [
                              {
                                "context": {
                                  "code": "Fall",
                                  "display": "Fall",
                                  "system": "fdpg.mii.cds",
                                  "version": "1.0.0"
                                },
                                "termCodes": [
                                  {
                                    "code": "VR",
                                    "display": "virtual",
                                    "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                                    "version": "9.0.0"
                                  }
                                ]
                              }
                            ]
                          ]
                        }
                      ]]
                    }
                    """);
            var conceptTree = createTreeWithoutChildren(ENCOUNTER_CLASS);
            var mappings = Map.of(ENCOUNTER_CLASS, mapping);
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    codesystem act_code: 'http://terminology.hl7.org/CodeSystem/v3-ActCode'
                    
                    context Patient
                    
                    define Criterion:
                      exists [Encounter: class ~ Code 'VR' from act_code]
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Test
        void bloodPressure() throws Exception {
            var mapping = parseMapping("""
                    {
                        "context": {
                          "code": "Laboruntersuchung",
                          "display": "Laboruntersuchung",
                          "system": "fdpg.mii.cds",
                          "version": "1.0.0"
                        },
                        "key": {
                            "system": "http://loinc.org",
                            "code": "85354-9",
                            "display": "Blood pressure panel with all children optional"
                        },
                        "resourceType": "Observation",
                        "attributes": [
                          {
                            "types": ["Coding"],
                            "key": {
                              "system": "http://loinc.org",
                              "code": "8462-4",
                              "display": "Diastolic blood pressure"
                            },
                            "path": "component.where(code.coding.exists(system = 'http://loinc.org' and code = '8462-4')).value.first()"
                          }
                        ]
                    }
                    """);

            var structuredQuery = parseStructuredQuery("""
                    {
                      "version": "https://medizininformatik-initiative.de/fdpg/StructuredQuery/v3/schema",
                      "display": "",
                      "inclusionCriteria": [[
                        {
                          "criteria": [
                            [
                              {
                                "context": {
                                  "code": "Laboruntersuchung",
                                  "display": "Laboruntersuchung",
                                  "system": "fdpg.mii.cds",
                                  "version": "1.0.0"
                                },
                                "termCodes": [
                                  {
                                    "system": "http://loinc.org",
                                    "code": "85354-9",
                                    "display": "Blood pressure panel with all children optional"
                                  }
                                ],
                                "attributeFilters": [
                                  {
                                    "attributeCode": {
                                      "system": "http://loinc.org",
                                      "code": "8462-4",
                                      "display": "Diastolic blood pressure"
                                    },
                                    "type": "quantity-comparator",
                                    "comparator": "lt",
                                    "value": 80,
                                    "unit": {
                                      "code": "mm[Hg]"
                                    }
                                  }
                                ]
                              }
                            ]
                          ]
                        }
                      ]]
                    }
                    """);
            var conceptTree = createTreeWithoutChildren(BLOOD_PRESSURE);
            var mappings = Map.of(BLOOD_PRESSURE, mapping);
            var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

            var library = Translator.of(mappingContext).toCql(structuredQuery);

            assertThat(library).printsTo("""
                    library Retrieve version '1.0.0'
                    using FHIR version '4.0.0'
                    include FHIRHelpers version '4.0.0'
                    
                    codesystem loinc: 'http://loinc.org'
                    
                    context Patient
                    
                    define Criterion:
                      exists (from [Observation: Code '85354-9' from loinc] O
                        where O.component.where(code.coding.exists(system = 'http://loinc.org' and code = '8462-4')).value.first() as Quantity < 80 'mm[Hg]')
                    
                    define InInitialPopulation:
                      Criterion
                    """);
        }

        @Nested
        class Inclusion {

            @Test
            void oneDisjunctionWithOneCriterion() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE))))));

                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient

                        define Criterion:
                          true
                        
                        define InInitialPopulation:
                          Criterion
                        """);
            }

            @Test
            void oneDisjunctionWithTwoCriteria() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE, Criterion.FALSE))))));

                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient
                        
                        define "Criterion 1":
                          true
                        
                        define "Criterion 2":
                          false
                        
                        define InInitialPopulation:
                          "Criterion 1" or
                          "Criterion 2"
                        """);
            }

            @Test
            void twoDisjunctionsWithOneCriterionEach() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE), List.of(Criterion.FALSE))))));

                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient

                        define "Criterion 1":
                          true

                        define "Criterion 2":
                          false

                        define InInitialPopulation:
                          "Criterion 1" and
                          "Criterion 2"
                        """);
            }

            @Test
            void twoDisjunctionsWithTwoCriterionEach() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE, Criterion.TRUE),
                        List.of(Criterion.FALSE, Criterion.FALSE))))));

                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient
                        
                        define "Criterion 1":
                          true
                        
                        define "Criterion 2":
                          true
                        
                        define "Criterion 3":
                          false
                        
                        define "Criterion 4":
                          false
                        
                        define InInitialPopulation:
                          ("Criterion 1" or
                          "Criterion 2") and
                          ("Criterion 3" or
                          "Criterion 4")
                        """);
            }
        }

        @Nested
        class InclusionAndExclusion {

            @Test
            void oneConjunctionWithOneCriterion() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE))))),
                        List.of(List.of(Group.of(List.of(List.of(Criterion.FALSE))))));

                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient
                        
                        define "Criterion 1":
                          true
                        
                        define Inclusion:
                          "Criterion 1"
                        
                        define "Criterion 2":
                          false
                        
                        define Exclusion:
                          "Criterion 2"
                        
                        define InInitialPopulation:
                          Inclusion and
                          not Exclusion
                        """);
            }

            @Test
            void oneConjunctionWithTwoCriteria() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE))))),
                        List.of(List.of(Group.of(List.of(List.of(Criterion.FALSE, Criterion.FALSE))))));

                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient
                        
                        define "Criterion 1":
                          true
                        
                        define Inclusion:
                          "Criterion 1"
                        
                        define "Criterion 2":
                          false
                        
                        define "Criterion 3":
                          false
                        
                        define Exclusion:
                          "Criterion 2" and
                          "Criterion 3"
                        
                        define InInitialPopulation:
                          Inclusion and
                          not Exclusion
                        """);
            }

            @Test
            void twoConjunctionsWithOneCriterionEach() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE))))),
                        List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE), List.of(Criterion.FALSE))))));


                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient
                        
                        define "Criterion 1":
                          true
                        
                        define Inclusion:
                          "Criterion 1"
                        
                        define "Criterion 2":
                          true
                        
                        define "Criterion 3":
                          false
                        
                        define Exclusion:
                          "Criterion 2" or
                          "Criterion 3"
                        
                        define InInitialPopulation:
                          Inclusion and
                          not Exclusion
                        """);
            }

            @Test
            void twoConjunctionsWithTwoCriterionEach() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE))))),
                        List.of(List.of(Group.of(List.of(List.of(Criterion.FALSE, Criterion.FALSE),
                                List.of(Criterion.FALSE, Criterion.FALSE))))));

                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient
                        
                        define "Criterion 1":
                          true
                        
                        define Inclusion:
                          "Criterion 1"
                        
                        define "Criterion 2":
                          false
                        
                        define "Criterion 3":
                          false
                        
                        define "Criterion 4":
                          false
                        
                        define "Criterion 5":
                          false
                        
                        define Exclusion:
                          "Criterion 2" and
                          "Criterion 3" or
                          "Criterion 4" and
                          "Criterion 5"
                        
                        define InInitialPopulation:
                          Inclusion and
                          not Exclusion
                        """);
            }

            @Test
            void twoInclusionAndTwoExclusionCriteria() {
                var structuredQuery = StructuredQuery.of(List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE), List.of(Criterion.FALSE))))),
                        List.of(List.of(Group.of(List.of(List.of(Criterion.TRUE, Criterion.FALSE))))));

                var library = Translator.of().toCql(structuredQuery);

                assertThat(library).patientContextPrintsTo("""
                        context Patient
                        
                        define "Criterion 1":
                          true
                        
                        define "Criterion 2":
                          false
                        
                        define Inclusion:
                          "Criterion 1" and
                          "Criterion 2"
                        
                        define "Criterion 3":
                          true
                        
                        define "Criterion 4":
                          false
                        
                        define Exclusion:
                          "Criterion 3" and
                          "Criterion 4"
                        
                        define InInitialPopulation:
                          Inclusion and
                          not Exclusion
                        """);
            }
        }

        @Nested
        class RetrievalTypes {

            @Test
            void primaryPath() throws Exception {
                var translator = createTranslator();
                var structuredQuery = readStructuredQuery("PrimaryPathSQ.json");

                var library = translator.toCql(structuredQuery);

                assertThat(library).printsTo("""
                       library Retrieve version '1.0.0'
                       using FHIR version '4.0.0'
                       include FHIRHelpers version '4.0.0'
                       
                       codesystem snomed: 'http://snomed.info/sct'
                       
                       context Patient
                       
                       define Criterion:
                         exists [Procedure: Code '726427004' from snomed] or
                         exists [Procedure: Code '232638008' from snomed] or
                         exists [Procedure: Code '3654008' from snomed]
                       
                       define InInitialPopulation:
                         Criterion
                       """);
            }

            @Test
            void nonPrimarySearchPath() throws Exception {
                var translator = createTranslator();
                var structuredQuery = readStructuredQuery("NonPrimarySearchPathSQ.json");

                var library = translator.toCql(structuredQuery);

                assertThat(library).printsTo("""
                       library Retrieve version '1.0.0'
                       using FHIR version '4.0.0'
                       include FHIRHelpers version '4.0.0'
                       
                       codesystem snomed: 'http://snomed.info/sct'
                       
                       context Patient
                       
                       define Criterion:
                         exists [Procedure: category ~ Code '387713003' from snomed]
                       
                       define InInitialPopulation:
                         Criterion
                       """);
            }

            @Test
            void noSearchPath() throws Exception {
                var translator = createTranslator();
                var structuredQuery = readStructuredQuery("NoSearchPathSQ.json");

                var library = translator.toCql(structuredQuery);

                assertThat(library).printsTo("""
                       library Retrieve version '1.0.0'
                       using FHIR version '4.0.0'
                       include FHIRHelpers version '4.0.0'
                       
                       codesystem consent_policy: 'urn:oid:2.16.840.1.113883.3.1937.777.24.5.3'
                       
                       context Patient
                       
                       define Criterion:
                         exists (from [Consent] C
                           where exists (from C.provision.provision.code C
                               where C ~ Code '2.16.840.1.113883.3.1937.777.24.5.3.6' from consent_policy))
                       
                       define InInitialPopulation:
                         Criterion
                       """);
            }

        }

        @Nested
        class RelativeTimeRestrictions {

            private static final ContextualTermCode DIAGNOSIS = ContextualTermCode.of(CONTEXT,
                    TermCode.of("http://fhir.de/CodeSystem/bfarm/icd-10-gm", "F00", "Demenz bei Alzheimer-Krankheit"));
            private static final ContextualTermCode CRP = ContextualTermCode.of(CONTEXT,
                    TermCode.of("http://loinc.org", "1988-5", "C-reaktives Protein"));
            private static final ContextualTermCode LEUKOCYTES = ContextualTermCode.of(CONTEXT,
                    TermCode.of("http://loinc.org", "6690-2", "Leukozyten"));
            private static final ContextualTermCode HEMOGLOBIN = ContextualTermCode.of(CONTEXT,
                    TermCode.of("http://loinc.org", "718-7", "Hemoglobin [Mass/volume] in Blood"));

            private static MappingContext mappingContext(ContextualTermCode... termCodes) {
                var diagnosisMapping = Mapping.of(DIAGNOSIS, "Condition", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("onset", DATE_TIME));
                var crpMapping = Mapping.of(CRP, "Observation", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("effective", DATE_TIME));
                var leukocytesMapping = Mapping.of(LEUKOCYTES, "Observation", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("effective", DATE_TIME));
                var mappings = Map.of(DIAGNOSIS, diagnosisMapping, CRP, crpMapping, LEUKOCYTES, leukocytesMapping);
                var conceptTree = new MappingTreeBase(List.of(createTreeRootWithoutChildren(DIAGNOSIS),
                        createTreeRootWithoutChildren(CRP), createTreeRootWithoutChildren(LEUKOCYTES)));
                return MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);
            }

            /**
             * The anchor's own criteria ({@code exists [Condition: Code 'F00' ...]}) are NOT re-emitted here even
             * though {@code anchor-dementia-diagnosis} is a bundle member alongside its dependent: {@code
             * Translator.bundleExpr}'s {@code subsumedByItsOwnGuardWithinThisBundle} check recognizes that its
             * dependent already ANDs in the guard ({@code "AnchorDate_..." is not null}), which provably implies
             * (and, ANDed together, equals) the anchor's own criteria - see the design note on {@code
             * Translator.bundleExpr} for the full argument. So the anchor contributes only its resolved date here,
             * never a separate, redundant existence check.
             */
            @Test
            void simpleAnchorAndOneDependent() {
                var anchor = Group.of("anchor-dementia-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSIS)))),
                        Group.AnchorOccurrence.FIRST);
                var dependent = Group.of("group-crp-before-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                        RelativeTimeRestriction.of("anchor-dementia-diagnosis", Duration.ofHours(-72), Duration.ZERO));
                var structuredQuery = StructuredQuery.of(List.of(List.of(anchor, dependent)));

                var library = Translator.of(mappingContext(DIAGNOSIS, CRP)).toCql(structuredQuery);

                assertThat(library).printsTo("""
                        library Retrieve version '1.0.0'
                        using FHIR version '4.0.0'
                        include FHIRHelpers version '4.0.0'

                        codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                        codesystem loinc: 'http://loinc.org'

                        context Patient

                        define "AnchorDate_anchor-dementia-diagnosis":
                          Min(from [Condition: Code 'F00' from icd10] C
                            return ToDate(C.onset as dateTime))

                        define Criterion:
                          exists (from [Observation: Code '1988-5' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval["AnchorDate_anchor-dementia-diagnosis" + -72 hours, "AnchorDate_anchor-dementia-diagnosis" + 0 hours])

                        define InInitialPopulation:
                          "AnchorDate_anchor-dementia-diagnosis" is not null and
                          Criterion
                        """);
            }

            @Test
            void fanOutTwoDependentsOnOneAnchor() {
                var anchor = Group.of("anchor-dementia-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSIS)))),
                        Group.AnchorOccurrence.FIRST);
                var dependent1 = Group.of("group-crp-before-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                        RelativeTimeRestriction.of("anchor-dementia-diagnosis", Duration.ofHours(-72), Duration.ZERO));
                var dependent2 = Group.of("group-leukocytes-before-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(LEUKOCYTES)))),
                        RelativeTimeRestriction.of("anchor-dementia-diagnosis", Duration.ofHours(-72), Duration.ZERO));
                var structuredQuery = StructuredQuery.of(List.of(List.of(anchor, dependent1, dependent2)));

                var library = Translator.of(mappingContext(DIAGNOSIS, CRP, LEUKOCYTES)).toCql(structuredQuery);

                // Each dependent resolves its own anchor date exactly once (see the single AnchorDate_... define
                // below) and, since both dependents resolve the *same* anchor id to byte-identical content, the
                // two independently-built containers' copies collapse into one shared define - see the design
                // note on Group.aggregateClauseDates for why moveToPatientContextWithUniqueName makes this safe.
                assertThat(library).printsTo("""
                        library Retrieve version '1.0.0'
                        using FHIR version '4.0.0'
                        include FHIRHelpers version '4.0.0'

                        codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                        codesystem loinc: 'http://loinc.org'

                        context Patient

                        define "AnchorDate_anchor-dementia-diagnosis":
                          Min(from [Condition: Code 'F00' from icd10] C
                            return ToDate(C.onset as dateTime))

                        define "Criterion 1":
                          exists (from [Observation: Code '1988-5' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval["AnchorDate_anchor-dementia-diagnosis" + -72 hours, "AnchorDate_anchor-dementia-diagnosis" + 0 hours])

                        define "Criterion 2":
                          exists (from [Observation: Code '6690-2' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval["AnchorDate_anchor-dementia-diagnosis" + -72 hours, "AnchorDate_anchor-dementia-diagnosis" + 0 hours])

                        define InInitialPopulation:
                          "AnchorDate_anchor-dementia-diagnosis" is not null and
                          "Criterion 1" and
                          "AnchorDate_anchor-dementia-diagnosis" is not null and
                          "Criterion 2"
                        """);
            }

            @Test
            void nowAnchor() {
                var anchor = Group.of("anchor-now", NowCriterion.INSTANCE);
                var dependent = Group.of("group-crp-since-now",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                        RelativeTimeRestriction.of("anchor-now", Duration.ofHours(-168), Duration.ZERO));
                var structuredQuery = StructuredQuery.of(List.of(List.of(anchor, dependent)));

                var library = Translator.of(mappingContext(DIAGNOSIS, CRP)).toCql(structuredQuery);

                assertThat(library).printsTo("""
                        library Retrieve version '1.0.0'
                        using FHIR version '4.0.0'
                        include FHIRHelpers version '4.0.0'

                        codesystem loinc: 'http://loinc.org'

                        context Patient

                        define "AnchorDate_anchor-now":
                          Min({ Now() })

                        define Criterion:
                          exists (from [Observation: Code '1988-5' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval["AnchorDate_anchor-now" + -168 hours, "AnchorDate_anchor-now" + 0 hours])

                        define InInitialPopulation:
                          "AnchorDate_anchor-now" is not null and
                          Criterion
                        """);
            }

            @Test
            void openEndedMinOffsetOnly() {
                var anchor = Group.of("anchor-dementia-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSIS)))),
                        Group.AnchorOccurrence.FIRST);
                var dependent = Group.of("group-crp-since-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                        RelativeTimeRestriction.of("anchor-dementia-diagnosis", Duration.ofHours(-168), null));
                var structuredQuery = StructuredQuery.of(List.of(List.of(anchor, dependent)));

                var library = Translator.of(mappingContext(DIAGNOSIS, CRP)).toCql(structuredQuery);

                assertThat(library).printsTo("""
                        library Retrieve version '1.0.0'
                        using FHIR version '4.0.0'
                        include FHIRHelpers version '4.0.0'

                        codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                        codesystem loinc: 'http://loinc.org'

                        context Patient

                        define "AnchorDate_anchor-dementia-diagnosis":
                          Min(from [Condition: Code 'F00' from icd10] C
                            return ToDate(C.onset as dateTime))

                        define Criterion:
                          exists (from [Observation: Code '1988-5' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval["AnchorDate_anchor-dementia-diagnosis" + -168 hours, @9999-12-31T])

                        define InInitialPopulation:
                          "AnchorDate_anchor-dementia-diagnosis" is not null and
                          Criterion
                        """);
            }

            @Test
            void openEndedMaxOffsetOnly() {
                var anchor = Group.of("anchor-dementia-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSIS)))),
                        Group.AnchorOccurrence.FIRST);
                var dependent = Group.of("group-crp-after-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                        RelativeTimeRestriction.of("anchor-dementia-diagnosis", null, Duration.ofHours(720)));
                var structuredQuery = StructuredQuery.of(List.of(List.of(anchor, dependent)));

                var library = Translator.of(mappingContext(DIAGNOSIS, CRP)).toCql(structuredQuery);

                assertThat(library).printsTo("""
                        library Retrieve version '1.0.0'
                        using FHIR version '4.0.0'
                        include FHIRHelpers version '4.0.0'

                        codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                        codesystem loinc: 'http://loinc.org'

                        context Patient

                        define "AnchorDate_anchor-dementia-diagnosis":
                          Min(from [Condition: Code 'F00' from icd10] C
                            return ToDate(C.onset as dateTime))

                        define Criterion:
                          exists (from [Observation: Code '1988-5' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval[@0001-01-01T, "AnchorDate_anchor-dementia-diagnosis" + 720 hours])

                        define InInitialPopulation:
                          "AnchorDate_anchor-dementia-diagnosis" is not null and
                          Criterion
                        """);
            }

            @Test
            void chainedAnchors() {
                var anchorNow = Group.of("anchor-now", NowCriterion.INSTANCE);
                var middle = Group.of("group-diagnosis-since-now",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSIS)))),
                        RelativeTimeRestriction.of("anchor-now", Duration.ofHours(-720), null),
                        Group.AnchorOccurrence.LAST);
                var dependent = Group.of("group-crp-before-diagnosis",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                        RelativeTimeRestriction.of("group-diagnosis-since-now", Duration.ofHours(-24), Duration.ZERO));
                var structuredQuery = StructuredQuery.of(List.of(List.of(anchorNow, middle, dependent)));

                var library = Translator.of(mappingContext(DIAGNOSIS, CRP)).toCql(structuredQuery);

                // "anchor-now"'s own criterion ("true") is dropped: it's subsumed by its dependent "middle"'s guard
                // (subsumedByItsOwnGuardWithinThisBundle, see Translator.bundleExpr) since it has no
                // relativeTimeRestrictions of its own. "middle" ("group-diagnosis-since-now") is NOT subsumed the
                // same way even though ITS OWN dependent references it too - it DOES carry
                // relativeTimeRestrictions (it's itself chained off anchor-now), which is deliberately excluded
                // from the optimization (see the design note on Translator.bundleExpr) - so its own window-filtered
                // existence check ("Criterion 1" here) still appears normally.
                //
                // Note the guard only appears once here, on Criterion 1 - not on "AnchorDate_anchor-now"'s own
                // reference inside AnchorDate_group-diagnosis-since-now's candidate filtering via the chaining path
                // in resolveAnchorDates. That path doesn't yet apply the guard (see the comment there); only the
                // final criterion-matching path in combineCriteria does. Known, scoped-out gap for chained anchors.
                assertThat(library).printsTo("""
                        library Retrieve version '1.0.0'
                        using FHIR version '4.0.0'
                        include FHIRHelpers version '4.0.0'

                        codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                        codesystem loinc: 'http://loinc.org'

                        context Patient

                        define "AnchorDate_anchor-now":
                          Min({ Now() })

                        define "Criterion 1":
                          exists (from [Condition: Code 'F00' from icd10] C
                            where ToDate(C.onset as dateTime) in Interval["AnchorDate_anchor-now" + -720 hours, @9999-12-31T])

                        define "AnchorDate_group-diagnosis-since-now":
                          Max(from [Condition: Code 'F00' from icd10] C
                            where ToDate(C.onset as dateTime) in Interval["AnchorDate_anchor-now" + -720 hours, @9999-12-31T]
                            return ToDate(C.onset as dateTime))

                        define "Criterion 2":
                          exists (from [Observation: Code '1988-5' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval["AnchorDate_group-diagnosis-since-now" + -24 hours, "AnchorDate_group-diagnosis-since-now" + 0 hours])

                        define InInitialPopulation:
                          "AnchorDate_anchor-now" is not null and
                          "Criterion 1" and
                          "AnchorDate_group-diagnosis-since-now" is not null and
                          "Criterion 2"
                        """);
            }

            /**
             * Regression test for a real bug found via external review of the generated CQL: an anchor whose
             * mapping can be {@code PERIOD}-typed (a real, common case - e.g. {@code Procedure.performed}) needs
             * its {@code .start}/{@code .end} access parenthesized around the {@code as Period} cast. See
             * {@link de.medizininformatikinitiative.cctb.model.cql.InvocationExpressionTest} for the isolated
             * AST-level regression test; this one proves it end to end through the real translation path, which
             * no other {@code RelativeTimeRestrictions} test above exercises (they're all {@code DATE_TIME}-only
             * anchors).
             */
            @Test
            void periodTypedAnchorPoint() {
                var procedure = ContextualTermCode.of(CONTEXT,
                        TermCode.of("http://fhir.de/CodeSystem/bfarm/ops", "5-812", "Arthroskopische Operation am Kniegelenk"));
                var procedureMapping = Mapping.of(procedure, "Procedure", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("performed", DATE_TIME, PERIOD));
                var crpMapping = Mapping.of(CRP, "Observation", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("effective", DATE_TIME));
                var mappings = Map.of(procedure, procedureMapping, CRP, crpMapping);
                var conceptTree = new MappingTreeBase(List.of(createTreeRootWithoutChildren(procedure),
                        createTreeRootWithoutChildren(CRP)));
                var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

                var anchor = Group.of("anchor-procedure",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(procedure)))),
                        Group.AnchorOccurrence.FIRST, Group.AnchorPoint.START);
                var dependent = Group.of("group-crp-after-procedure",
                        List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                        RelativeTimeRestriction.of("anchor-procedure", Duration.ZERO, Duration.ofHours(24)));
                var structuredQuery = StructuredQuery.of(List.of(List.of(anchor, dependent)));

                var library = Translator.of(mappingContext).toCql(structuredQuery);

                assertThat(library).printsTo("""
                        library Retrieve version '1.0.0'
                        using FHIR version '4.0.0'
                        include FHIRHelpers version '4.0.0'

                        codesystem codeSystem1: 'http://fhir.de/CodeSystem/bfarm/ops'
                        codesystem loinc: 'http://loinc.org'

                        context Patient

                        define "AnchorDate_anchor-procedure":
                          Min(from [Procedure: Code '5-812' from codeSystem1] P
                            return Coalesce(ToDate(P.performed as dateTime), ToDate((P.performed as Period).start)))

                        define Criterion:
                          exists (from [Observation: Code '1988-5' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval["AnchorDate_anchor-procedure" + 0 hours, "AnchorDate_anchor-procedure" + 24 hours])

                        define InInitialPopulation:
                          "AnchorDate_anchor-procedure" is not null and
                          Criterion
                        """);
            }

            /**
             * Direct test of the asymmetric min/max-across-clauses rule for AND-groups as anchors: anchor =
             * DIAGNOSIS AND (CRP or LEUKOCYTES), dependent = HEMOGLOBIN within [0h, 24h] of the anchor. Expects
             * the {@code maxOffset} bound (window end) to be built from {@code Min} across the two clauses, and
             * the {@code minOffset} bound (window start) from {@code Max} - the opposite of what a naive
             * single-collapsed-anchor-point implementation would produce.
             * <p>
             * Also covers the guard: {@code AnchorDate_anchor[0] is not null and AnchorDate_anchor[1] is not null},
             * not {@code Min(...) is not null and Max(...) is not null} - see the design note on {@code
             * AnchorDates} for why the latter is insufficient ({@code Min}/{@code Max} ignore null list elements
             * rather than propagating, so it would only prove at least one of the two clauses resolved), and why
             * per-index checks rather than a {@code not exists (... where D is null)} query (confirmed empirically
             * against real Blaze: the nested query fails to detect a null list element sourced from a
             * {@code Min}/{@code Max(retrieve)} aggregate).
             * <p>
             * Also covers {@code Translator.bundleExpr}'s redundant-criteria optimization on a multi-clause
             * anchor: none of the anchor's own three leaf criteria (diagnosis, CRP, leukocytes) are re-emitted,
             * since {@code AnchorDate_anchor[0]}/{@code [1] is not null} together provably imply the anchor's own
             * "Criterion1 and (Criterion2 or Criterion3)" - see the design note on {@code Translator.bundleExpr}.
             */
            @Test
            void multiClauseAnchor() {
                var diagnosisMapping = Mapping.of(DIAGNOSIS, "Condition", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("onset", DATE_TIME));
                var crpMapping = Mapping.of(CRP, "Observation", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("effective", DATE_TIME));
                var leukocytesMapping = Mapping.of(LEUKOCYTES, "Observation", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("effective", DATE_TIME));
                var hemoglobinMapping = Mapping.of(HEMOGLOBIN, "Observation", null, List.of(), List.of(),
                        Mapping.TimeRestrictionMapping.of("effective", DATE_TIME));
                var mappings = Map.of(DIAGNOSIS, diagnosisMapping, CRP, crpMapping, LEUKOCYTES, leukocytesMapping,
                        HEMOGLOBIN, hemoglobinMapping);
                var conceptTree = new MappingTreeBase(List.of(createTreeRootWithoutChildren(DIAGNOSIS),
                        createTreeRootWithoutChildren(CRP), createTreeRootWithoutChildren(LEUKOCYTES),
                        createTreeRootWithoutChildren(HEMOGLOBIN)));
                var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);

                var anchor = Group.of("anchor", List.of(
                        List.of(ConceptCriterion.of(ContextualConcept.of(DIAGNOSIS))),
                        List.of(ConceptCriterion.of(ContextualConcept.of(CRP)), ConceptCriterion.of(ContextualConcept.of(LEUKOCYTES)))),
                        Group.AnchorOccurrence.FIRST);
                var dependent = Group.of("dependent", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(HEMOGLOBIN)))),
                        RelativeTimeRestriction.of("anchor", Duration.ZERO, Duration.ofHours(24)));
                var structuredQuery = StructuredQuery.of(List.of(List.of(anchor, dependent)));

                var library = Translator.of(mappingContext).toCql(structuredQuery);

                assertThat(library).printsTo("""
                        library Retrieve version '1.0.0'
                        using FHIR version '4.0.0'
                        include FHIRHelpers version '4.0.0'

                        codesystem icd10: 'http://fhir.de/CodeSystem/bfarm/icd-10-gm'
                        codesystem loinc: 'http://loinc.org'

                        context Patient

                        define AnchorDate_anchor:
                          { Min(from [Condition: Code 'F00' from icd10] C
                            return ToDate(C.onset as dateTime)), Min((from [Observation: Code '1988-5' from loinc] O
                            return ToDate(O.effective as dateTime)) union
                          (from [Observation: Code '6690-2' from loinc] O
                            return ToDate(O.effective as dateTime))) }

                        define Criterion:
                          exists (from [Observation: Code '718-7' from loinc] O
                            where ToDate(O.effective as dateTime) in Interval[Max(AnchorDate_anchor) + 0 hours, Min(AnchorDate_anchor) + 24 hours])

                        define InInitialPopulation:
                          AnchorDate_anchor[0] is not null and
                          AnchorDate_anchor[1] is not null and
                          Criterion
                        """);
            }
        }
    }
}
