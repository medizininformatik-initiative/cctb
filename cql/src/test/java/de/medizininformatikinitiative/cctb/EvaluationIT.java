package de.medizininformatikinitiative.cctb;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
import de.medizininformatikinitiative.cctb.model.Mapping;
import de.medizininformatikinitiative.cctb.model.MappingContext;
import tools.jackson.databind.ObjectMapper;
import de.medizininformatikinitiative.cctb.model.common.TermCode;
import de.medizininformatikinitiative.cctb.model.structured_query.ConceptCriterion;
import de.medizininformatikinitiative.cctb.model.structured_query.ContextualConcept;
import de.medizininformatikinitiative.cctb.model.structured_query.ContextualTermCode;
import de.medizininformatikinitiative.cctb.model.structured_query.Group;
import de.medizininformatikinitiative.cctb.model.structured_query.NumericCriterion;
import de.medizininformatikinitiative.cctb.model.structured_query.RelativeTimeRestriction;
import de.medizininformatikinitiative.cctb.model.structured_query.StructuredQuery;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static de.medizininformatikinitiative.cctb.Util.createTreeWithoutChildren;
import static de.medizininformatikinitiative.cctb.model.Mapping.TimeRestrictionMapping.Type.DATE_TIME;
import static de.medizininformatikinitiative.cctb.model.Mapping.TimeRestrictionMapping.Type.PERIOD;
import static de.medizininformatikinitiative.cctb.model.common.Comparator.LESS_THAN;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION;
import static org.hl7.fhir.r4.model.Bundle.HTTPVerb.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
public class EvaluationIT {

    static final TermCode CONTEXT = TermCode.of("context", "context", "context");
    static final String BLOOD_PRESSURE_CODE = "85354-9";
    static final String BLOOD_PRESSURE_SYSTEM = "http://loinc.org";
    static final ContextualTermCode BLOOD_PRESSURE = ContextualTermCode.of(CONTEXT, TermCode.of(BLOOD_PRESSURE_SYSTEM, BLOOD_PRESSURE_CODE,
            "Blood pressure panel with all children optional"));
    static final TermCode DIASTOLIC_BLOOD_PRESSURE = TermCode.of("http://loinc.org", "8462-4",
            "Diastolic blood pressure");
    static final ContextualTermCode DEMENTIA_DIAGNOSIS = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://fhir.de/CodeSystem/dimdi/icd-10-gm", "F00", "Demenz bei Alzheimer-Krankheit"));
    static final ContextualTermCode CRP = ContextualTermCode.of(CONTEXT,
            TermCode.of("http://loinc.org", "1988-5", "C-reaktives Protein"));
    static final Map<String, String> CODE_SYSTEM_ALIASES = Map.of("http://loinc.org", "loinc");

    @Container
    private final GenericContainer<?> blaze = new GenericContainer<>(DockerImageName.parse("samply/blaze:0.34"))
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupAttempts(3);

    private final FhirContext fhirContext = FhirContext.forR4();
    private IGenericClient fhirClient;

    private static String slurp(String name) throws Exception {
        try (InputStream in = EvaluationIT.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new Exception(format("Can't find `%s` in classpath.", name));
            } else {
                return new String(in.readAllBytes(), UTF_8);
            }
        } catch (IOException e) {
            throw new Exception(format("error while reading the file `%s` from classpath", name));
        }
    }

    private static Bundle createBundle(Library library, Measure measure) {
        var bundle = new Bundle();
        bundle.setType(TRANSACTION);
        bundle.addEntry().setResource(library).getRequest().setMethod(POST).setUrl("Library");
        bundle.addEntry().setResource(measure).getRequest().setMethod(POST).setUrl("Measure");
        return bundle;
    }

    private static Mapping readMapping(String s) throws Exception {
        return new ObjectMapper().readValue(s, Mapping.class);
    }

    private static StructuredQuery readStructuredQuery(String s) throws Exception {
        return new ObjectMapper().readValue(s, StructuredQuery.class);
    }

    @BeforeEach
    public void setUp() {
        fhirClient = fhirContext.newRestfulGenericClient(format("http://localhost:%d/fhir", blaze.getFirstMappedPort()));
    }

    @Test
    public void evaluateBloodPressure() throws Exception {
        fhirClient.transaction().withBundle(parseResource(Bundle.class, slurp("blood-pressure-bundle.json"))).execute();

        var valueMapping = Mapping.PathMapping.of("component.where(code.coding.exists(system = '%s' and code = '%s')).value.first()".formatted(
                DIASTOLIC_BLOOD_PRESSURE.system(), DIASTOLIC_BLOOD_PRESSURE.code()), Mapping.PathMapping.Type.QUANTITY);
        var mappings = Map.of(BLOOD_PRESSURE, Mapping.of(BLOOD_PRESSURE, "Observation", valueMapping));
        var conceptTree = createTreeWithoutChildren(BLOOD_PRESSURE);
        var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);
        var translator = Translator.of(mappingContext);
        var criterion = NumericCriterion.of(ContextualConcept.of(BLOOD_PRESSURE), LESS_THAN, BigDecimal.valueOf(80), "mm[Hg]");
        var structuredQuery = StructuredQuery.of(List.of(Group.of(List.of(List.of(criterion)))));
        var cql = translator.toCql(structuredQuery).print();
        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var library = appendCql(parseResource(Library.class, slurp("Library.json")).setUrl(libraryUri), cql);
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = parseResource(Measure.class, slurp("Measure.json")).setUrl(measureUri).addLibrary(libraryUri);
        var bundle = createBundle(library, measure);

        fhirClient.transaction().withBundle(bundle).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    @Test
    public void evaluateBloodPressureAttribute() throws Exception {
        fhirClient.transaction().withBundle(parseResource(Bundle.class, slurp("blood-pressure-bundle.json"))).execute();

        var mapping = readMapping("""
                {
                    "context": {
                          "system": "context",
                          "code": "context",
                          "display": "context"
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
        var mappings = Map.of(BLOOD_PRESSURE, mapping);
        var conceptTree = createTreeWithoutChildren(BLOOD_PRESSURE);
        var mappingContext = MappingContext.of(mappings, conceptTree, CODE_SYSTEM_ALIASES);
        var translator = Translator.of(mappingContext);
        var structuredQuery = readStructuredQuery("""
                {
                  "version": "https://medizininformatik-initiative.de/fdpg/StructuredQuery/v3/schema",
                  "display": "",
                  "inclusionCriteria": [
                    {
                      "criteria": [
                        [
                          {
                            "context": {
                              "system": "context",
                              "code": "context",
                              "display": "context"
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
                  ]
                }
                """);
        var cql = translator.toCql(structuredQuery).print();
        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var library = appendCql(parseResource(Library.class, slurp("Library.json")).setUrl(libraryUri), cql);
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = parseResource(Measure.class, slurp("Measure.json")).setUrl(measureUri).addLibrary(libraryUri);
        var bundle = createBundle(library, measure);

        fhirClient.transaction().withBundle(bundle).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    /**
     * Proves the relative-time-restriction / anchor feature against a real CQL engine, including the boundary
     * between "inside" and "outside" the computed window - something print-string unit tests alone can't confirm.
     * <p>
     * Two patients each have the same diagnosis (the anchor, dated 2024-01-10). One has a CRP measurement 2 days
     * before it (inside the "up to 3 days before, up to the diagnosis" window); the other has one 9 days before
     * (outside it). Only the first should be counted.
     */
    @Test
    public void evaluateRelativeTimeRestriction() throws Exception {
        var diagnosisMapping = Mapping.of(DEMENTIA_DIAGNOSIS, "Condition", null, List.of(), List.of(),
                Mapping.TimeRestrictionMapping.of("onset", Mapping.TimeRestrictionMapping.Type.DATE_TIME));
        var crpMapping = Mapping.of(CRP, "Observation", null, List.of(), List.of(),
                Mapping.TimeRestrictionMapping.of("effective", Mapping.TimeRestrictionMapping.Type.DATE_TIME));
        var mappings = Map.of(DEMENTIA_DIAGNOSIS, diagnosisMapping, CRP, crpMapping);
        var mappingContext = MappingContext.of(mappings, null, CODE_SYSTEM_ALIASES);
        var translator = Translator.of(mappingContext);

        var anchorGroup = Group.of("diagnosis", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DEMENTIA_DIAGNOSIS)))),
                Group.AnchorOccurrence.FIRST);
        var dependentGroup = Group.of(null, List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                RelativeTimeRestriction.of("diagnosis", Duration.parse("-P3D"), Duration.ZERO));
        var structuredQuery = StructuredQuery.of(List.of(anchorGroup, dependentGroup));
        var cql = translator.toCql(structuredQuery).print();

        var bundle = new Bundle().setType(TRANSACTION);
        addPut(bundle, "Patient", "within-window", patient("within-window"));
        addPut(bundle, "Condition", "within-window-diagnosis",
                condition("within-window-diagnosis", "within-window", DEMENTIA_DIAGNOSIS, "2024-01-10"));
        addPut(bundle, "Observation", "within-window-crp",
                observation("within-window-crp", "within-window", CRP, "2024-01-08"));
        addPut(bundle, "Patient", "outside-window", patient("outside-window"));
        addPut(bundle, "Condition", "outside-window-diagnosis",
                condition("outside-window-diagnosis", "outside-window", DEMENTIA_DIAGNOSIS, "2024-01-10"));
        addPut(bundle, "Observation", "outside-window-crp",
                observation("outside-window-crp", "outside-window", CRP, "2024-01-01"));
        fhirClient.transaction().withBundle(bundle).execute();

        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var library = appendCql(parseResource(Library.class, slurp("Library.json")).setUrl(libraryUri), cql);
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = parseResource(Measure.class, slurp("Measure.json")).setUrl(measureUri).addLibrary(libraryUri);
        fhirClient.transaction().withBundle(createBundle(library, measure)).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    /**
     * Documents a confirmed Blaze/CQL gap (not a cctb bug): naive three-valued-logic reasoning says {@code X in
     * Interval[null, null]} should be null/unknown, and a {@code where} clause with a null condition should drop
     * the row (like SQL {@code WHERE NULL}) - so {@code exists(...)} should come out {@code false}. Blaze 0.34
     * does not do this; the row is kept and {@code exists(...)} comes out {@code true}. Confirmed with a
     * hand-written library carrying a literal {@code null as DateTime} instead of a real {@code Min} over an
     * empty retrieve, to rule out {@code Min({})}-over-an-empty-retrieve as the culprit (see the real-generated-
     * CQL version of this same gap in {@link #evaluateDependentAloneWhenAnchorNeverResolves}). Relevant because a
     * possible future design (letting an anchor be referenceable without being independently required, e.g. for
     * OR between differently-anchored branches) would need a dependent whose anchor never resolves to gracefully
     * evaluate to "no match" - it currently does not, silently behaving as an unbounded window instead. Any such
     * design would need an explicit {@code AnchorDate is not null and (...)} guard rather than relying on this.
     */
    @Test
    public void evaluateNullIntervalMembershipDirectly() throws Exception {
        var bundle = new Bundle().setType(TRANSACTION);
        addPut(bundle, "Patient", "no-diagnosis", patient("no-diagnosis"));
        addPut(bundle, "Observation", "no-diagnosis-crp",
                observation("no-diagnosis-crp", "no-diagnosis", CRP, "2024-01-08"));
        fhirClient.transaction().withBundle(bundle).execute();

        var cql = """
                library Retrieve version '1.0.0'
                using FHIR version '4.0.0'
                include FHIRHelpers version '4.0.0'

                codesystem loinc: 'http://loinc.org'

                context Patient

                define InInitialPopulation:
                  exists (from [Observation: Code '1988-5' from loinc] O
                    where ToDate(O.effective as dateTime) in Interval[(null as DateTime) + -72 hours, (null as DateTime) + 0 hours])
                """;

        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var library = appendCql(parseResource(Library.class, slurp("Library.json")).setUrl(libraryUri), cql);
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = parseResource(Measure.class, slurp("Measure.json")).setUrl(measureUri).addLibrary(libraryUri);
        fhirClient.transaction().withBundle(createBundle(library, measure)).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        // Confirmed gap (see class javadoc above): should be 0 under correct null-propagation, is 1 in Blaze 0.34.
        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    /**
     * Proves the fix for the gap {@link #evaluateNullIntervalMembershipDirectly} documents at the raw-CQL level:
     * calls {@link Group#toCql} directly on a dependent group alone, bypassing {@link Translator}'s top-level AND
     * fold (see {@link Translator#groupsExpr}) so the anchor's own truth never independently gates the result -
     * only the window computation, and the explicit null-guard it now carries (see {@code Group.Window}), does.
     * This is exactly the shape a future design decoupling "referenceable as anchor" from "independently
     * required" (e.g. for OR between differently-anchored branches) would rely on.
     * <p>
     * Two patients: one has no diagnosis at all (so {@code AnchorDate_diagnosis} resolves to null for them) but
     * does have a CRP measurement that would match if the window were unbounded; the other has both the
     * diagnosis and an in-window CRP measurement as a positive control. Only the second is counted - the guard
     * correctly excludes the first despite Blaze's own null-propagation not doing so on its own.
     */
    @Test
    public void evaluateDependentAloneWhenAnchorNeverResolves() throws Exception {
        var diagnosisMapping = Mapping.of(DEMENTIA_DIAGNOSIS, "Condition", null, List.of(), List.of(),
                Mapping.TimeRestrictionMapping.of("onset", Mapping.TimeRestrictionMapping.Type.DATE_TIME));
        var crpMapping = Mapping.of(CRP, "Observation", null, List.of(), List.of(),
                Mapping.TimeRestrictionMapping.of("effective", Mapping.TimeRestrictionMapping.Type.DATE_TIME));
        var mappings = Map.of(DEMENTIA_DIAGNOSIS, diagnosisMapping, CRP, crpMapping);
        var mappingContext = MappingContext.of(mappings, null, CODE_SYSTEM_ALIASES);

        var anchorGroup = Group.of("diagnosis", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(DEMENTIA_DIAGNOSIS)))),
                Group.AnchorOccurrence.FIRST);
        var dependentGroup = Group.of(null, List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                RelativeTimeRestriction.of("diagnosis", Duration.parse("-P3D"), Duration.ZERO));
        var allGroupsById = Map.of(anchorGroup.id(), anchorGroup);

        // Isolated: only the dependent's own window-filtered criteria - no top-level AND on the anchor's own truth.
        var cql = dependentGroup.toCql(mappingContext, allGroupsById, true)
                .moveToPatientContext("InInitialPopulation")
                .print();

        var bundle = new Bundle().setType(TRANSACTION);
        addPut(bundle, "Patient", "no-diagnosis", patient("no-diagnosis"));
        addPut(bundle, "Observation", "no-diagnosis-crp",
                observation("no-diagnosis-crp", "no-diagnosis", CRP, "2024-01-08"));
        addPut(bundle, "Patient", "with-diagnosis", patient("with-diagnosis"));
        addPut(bundle, "Condition", "with-diagnosis-diagnosis",
                condition("with-diagnosis-diagnosis", "with-diagnosis", DEMENTIA_DIAGNOSIS, "2024-01-10"));
        addPut(bundle, "Observation", "with-diagnosis-crp",
                observation("with-diagnosis-crp", "with-diagnosis", CRP, "2024-01-08"));
        fhirClient.transaction().withBundle(bundle).execute();

        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var library = appendCql(parseResource(Library.class, slurp("Library.json")).setUrl(libraryUri), cql);
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = parseResource(Measure.class, slurp("Measure.json")).setUrl(measureUri).addLibrary(libraryUri);
        fhirClient.transaction().withBundle(createBundle(library, measure)).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        // Fixed: only "with-diagnosis" is counted - the explicit null-guard excludes "no-diagnosis" correctly.
        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    /**
     * Verification for a real bug suspected from a user-reported anchor whose mapping supports both {@code
     * dateTime} and {@code Period}: {@code dateProjectionExpr} (AbstractCriterion) built {@code
     * Coalesce(ToDate(x as dateTime), (x as Period).start)} - {@code ToDate} returns {@code Date}, but {@code
     * Period.start} is {@code DateTime}, so {@code Coalesce} mixed types across its arguments. Two patients each
     * have a procedure coded the same way, one recorded with {@code performedDateTime}, the other with {@code
     * performedPeriod} - if the type mismatch is a real problem, Blaze should reject the CQL library outright
     * (an exception from the client) rather than silently misbehave, since this is a compile-time type check, not
     * a runtime data issue.
     */
    @Test
    public void evaluateAnchorWithMixedDateTimeAndPeriodTypes() throws Exception {
        var procedure = ContextualTermCode.of(CONTEXT,
                TermCode.of("http://fhir.de/CodeSystem/bfarm/ops", "5-812", "Arthroskopische Operation am Kniegelenk"));
        var procedureMapping = Mapping.of(procedure, "Procedure", null, List.of(), List.of(),
                Mapping.TimeRestrictionMapping.of("performed", DATE_TIME, PERIOD));
        var crpMapping = Mapping.of(CRP, "Observation", null, List.of(), List.of(),
                Mapping.TimeRestrictionMapping.of("effective", DATE_TIME));
        var mappings = Map.of(procedure, procedureMapping, CRP, crpMapping);
        var mappingContext = MappingContext.of(mappings, null, CODE_SYSTEM_ALIASES);
        var translator = Translator.of(mappingContext);

        var anchorGroup = Group.of("procedure", List.of(List.of(ConceptCriterion.of(ContextualConcept.of(procedure)))),
                Group.AnchorOccurrence.FIRST);
        var dependentGroup = Group.of(null, List.of(List.of(ConceptCriterion.of(ContextualConcept.of(CRP)))),
                RelativeTimeRestriction.of("procedure", Duration.ZERO, Duration.ofHours(24)));
        var structuredQuery = StructuredQuery.of(List.of(anchorGroup, dependentGroup));
        var cql = translator.toCql(structuredQuery).print();

        var bundle = new Bundle().setType(TRANSACTION);
        addPut(bundle, "Patient", "dt-patient", patient("dt-patient"));
        addPut(bundle, "Procedure", "dt-procedure", procedureWithDateTime("dt-procedure", "dt-patient", procedure, "2024-01-10"));
        addPut(bundle, "Observation", "dt-crp", observation("dt-crp", "dt-patient", CRP, "2024-01-10"));
        addPut(bundle, "Patient", "period-patient", patient("period-patient"));
        addPut(bundle, "Procedure", "period-procedure",
                procedureWithPeriod("period-procedure", "period-patient", procedure, "2024-01-10", "2024-01-10"));
        addPut(bundle, "Observation", "period-crp", observation("period-crp", "period-patient", CRP, "2024-01-10"));
        fhirClient.transaction().withBundle(bundle).execute();

        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var library = appendCql(parseResource(Library.class, slurp("Library.json")).setUrl(libraryUri), cql);
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = parseResource(Measure.class, slurp("Measure.json")).setUrl(measureUri).addLibrary(libraryUri);
        fhirClient.transaction().withBundle(createBundle(library, measure)).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        assertEquals(2, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    private static Procedure procedureWithDateTime(String id, String patientId, ContextualTermCode concept, String date) {
        var procedure = new Procedure();
        procedure.setId(id);
        procedure.setStatus(Procedure.ProcedureStatus.COMPLETED);
        procedure.setSubject(new Reference("Patient/" + patientId));
        procedure.getCode().addCoding().setSystem(concept.termCode().system()).setCode(concept.termCode().code());
        procedure.setPerformed(new DateTimeType(date));
        return procedure;
    }

    private static Procedure procedureWithPeriod(String id, String patientId, ContextualTermCode concept, String startDate, String endDate) {
        var procedure = new Procedure();
        procedure.setId(id);
        procedure.setStatus(Procedure.ProcedureStatus.COMPLETED);
        procedure.setSubject(new Reference("Patient/" + patientId));
        procedure.getCode().addCoding().setSystem(concept.termCode().system()).setCode(concept.termCode().code());
        procedure.setPerformed(new Period().setStartElement(new DateTimeType(startDate)).setEndElement(new DateTimeType(endDate)));
        return procedure;
    }

    private static Patient patient(String id) {
        var patient = new Patient();
        patient.setId(id);
        return patient;
    }

    private static Condition condition(String id, String patientId, ContextualTermCode concept, String onsetDate) {
        var condition = new Condition();
        condition.setId(id);
        condition.setSubject(new Reference("Patient/" + patientId));
        condition.getCode().addCoding().setSystem(concept.termCode().system()).setCode(concept.termCode().code());
        condition.setOnset(new DateTimeType(onsetDate));
        return condition;
    }

    private static Observation observation(String id, String patientId, ContextualTermCode concept, String effectiveDate) {
        var observation = new Observation();
        observation.setId(id);
        observation.setStatus(Observation.ObservationStatus.FINAL);
        observation.setSubject(new Reference("Patient/" + patientId));
        observation.getCode().addCoding().setSystem(concept.termCode().system()).setCode(concept.termCode().code());
        observation.setEffective(new DateTimeType(effectiveDate));
        return observation;
    }

    private static void addPut(Bundle bundle, String resourceType, String id, org.hl7.fhir.r4.model.Resource resource) {
        bundle.addEntry().setResource(resource).getRequest().setMethod(Bundle.HTTPVerb.PUT)
                .setUrl(resourceType + "/" + id);
    }

    private <T extends IBaseResource> T parseResource(Class<T> type, String s) {
        var parser = fhirContext.newJsonParser();
        return type.cast(parser.parseResource(s));
    }

    private Library appendCql(Library library, String cql) {
        library.getContentFirstRep().setContentType("text/cql");
        library.getContentFirstRep().setData(cql.getBytes(UTF_8));
        return library;
    }
}
