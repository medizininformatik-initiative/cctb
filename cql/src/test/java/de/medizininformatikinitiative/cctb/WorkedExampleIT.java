package de.medizininformatikinitiative.cctb;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
import de.medizininformatikinitiative.cctb.model.structured_query.StructuredQuery;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.UUID;

import static java.lang.String.format;
import static org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION;
import static org.hl7.fhir.r4.model.Bundle.HTTPVerb.POST;
import static org.hl7.fhir.r4.model.Bundle.HTTPVerb.PUT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Evaluates the worked example CCDLs from the specification repository end to end: deserialize the published
 * JSON, translate it against the real MII ontology, install the result on a real engine, and check which
 * patients it selects out of a dataset built to sit on both sides of that example's boundary.
 * <p>
 * This is deliberately a different kind of test from {@link EvaluationIT}, which builds its queries as Java
 * objects against hand-rolled minimal mappings in order to pin one semantic rule at a time. Here the input is
 * the file a reader of the spec would copy, the mapping is the one a real deployment downloads, and a failure
 * says the example is wrong rather than that a rule is. It is also the only place the examples are
 * deserialized, translated and <em>run</em> at all - up to now they were only translated, which cannot catch a
 * query the engine rejects or one that selects the wrong people.
 * <p>
 * Each test spins up its own engine so one example's data can never satisfy another's query.
 */
@Testcontainers
public class WorkedExampleIT {

    static final String ICD = "http://fhir.de/CodeSystem/bfarm/icd-10-gm";
    static final String OPS = "http://fhir.de/CodeSystem/bfarm/ops";
    static final String LOINC = "http://loinc.org";
    static final String ATC = "http://fhir.de/CodeSystem/bfarm/atc";
    static final String SNOMED = "http://snomed.info/sct";
    static final String BIOBANK_DIAGNOSIS_EXT =
            "https://www.medizininformatik-initiative.de/fhir/ext/modul-biobank/StructureDefinition/Diagnose";

    @Container
    private final GenericContainer<?> blaze = new GenericContainer<>(DockerImageName.parse("samply/blaze:1.11.0"))
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupAttempts(3);

    private final FhirContext fhirContext = FhirContext.forR4();
    private IGenericClient fhirClient;

    @BeforeEach
    public void setUp() {
        fhirClient = fhirContext.newRestfulGenericClient(format("http://localhost:%d/fhir", blaze.getFirstMappedPort()));
    }

    /** Loads {@code bundle}, translates the named example, and returns how many patients it selects. */
    private int evaluate(String exampleFile, Bundle bundle) throws Exception {
        fhirClient.transaction().withBundle(bundle).execute();

        StructuredQuery structuredQuery = Util.readStructuredQuery("examples/" + exampleFile);
        var cql = Util.createTranslator().toCql(structuredQuery).print();

        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var parser = fhirContext.newJsonParser();
        var library = (Library) parser.parseResource(Util.slurp("Library.json"));
        library.setUrl(libraryUri);
        library.getContentFirstRep().setContentType("text/cql");
        library.getContentFirstRep().setData(cql.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = (Measure) parser.parseResource(Util.slurp("Measure.json"));
        measure.setUrl(measureUri).addLibrary(libraryUri);

        var tx = new Bundle().setType(TRANSACTION);
        tx.addEntry().setResource(library).getRequest().setMethod(POST).setUrl("Library");
        tx.addEntry().setResource(measure).getRequest().setMethod(POST).setUrl("Measure");
        fhirClient.transaction().withBundle(tx).execute();

        var report = fhirClient.operation().onType(Measure.class).named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet().returnResourceType(MeasureReport.class).execute();
        return report.getGroupFirstRep().getPopulationFirstRep().getCount();
    }

    // ---------- test data builders ----------

    private static Bundle bundle() {
        return new Bundle().setType(TRANSACTION);
    }

    private static void put(Bundle bundle, String type, String id, Resource resource) {
        resource.setId(id);
        bundle.addEntry().setResource(resource).getRequest().setMethod(PUT).setUrl(type + "/" + id);
    }

    private static void patient(Bundle b, String id) {
        patient(b, id, "female", "1960-01-01");
    }

    private static void patient(Bundle b, String id, String gender, String birthDate) {
        var p = new Patient();
        p.setGender(Enumerations.AdministrativeGender.fromCode(gender));
        p.setBirthDateElement(new DateType(birthDate));
        put(b, "Patient", id, p);
    }

    private static void condition(Bundle b, String id, String patientId, String system, String code, String recorded) {
        var c = new Condition();
        c.setSubject(new Reference("Patient/" + patientId));
        c.getCode().addCoding().setSystem(system).setCode(code);
        c.setRecordedDateElement(new DateTimeType(recorded));
        put(b, "Condition", id, c);
    }

    private static void observation(Bundle b, String id, String patientId, String system, String code, String effective) {
        var o = new Observation();
        o.setStatus(Observation.ObservationStatus.FINAL);
        o.setSubject(new Reference("Patient/" + patientId));
        o.getCode().addCoding().setSystem(system).setCode(code);
        o.setEffective(new DateTimeType(effective));
        put(b, "Observation", id, o);
    }

    private static void procedure(Bundle b, String id, String patientId, String code, String performed) {
        var p = new Procedure();
        p.setStatus(Procedure.ProcedureStatus.COMPLETED);
        p.setSubject(new Reference("Patient/" + patientId));
        p.getCode().addCoding().setSystem(OPS).setCode(code);
        p.setPerformed(new DateTimeType(performed));
        put(b, "Procedure", id, p);
    }

    /** An ATC criterion compiles to a reference join, so the medication has to exist as its own resource. */
    private static void medicationStatement(Bundle b, String id, String patientId, String atcCode, String effective) {
        var med = new Medication();
        med.getCode().addCoding().setSystem(ATC).setCode(atcCode);
        put(b, "Medication", id + "-med", med);
        var ms = new MedicationStatement();
        ms.setStatus(MedicationStatement.MedicationStatementStatus.ACTIVE);
        ms.setSubject(new Reference("Patient/" + patientId));
        ms.setMedication(new Reference("Medication/" + id + "-med"));
        ms.setEffective(new DateTimeType(effective));
        put(b, "MedicationStatement", id, ms);
    }

    private static void specimen(Bundle b, String id, String patientId, String snomedCode, String conditionId) {
        var s = new Specimen();
        s.setSubject(new Reference("Patient/" + patientId));
        s.getType().addCoding().setSystem(SNOMED).setCode(snomedCode);
        s.addExtension(new Extension(BIOBANK_DIAGNOSIS_EXT, new Reference("Condition/" + conditionId)));
        put(b, "Specimen", id, s);
    }

    private static String daysAgo(int days) {
        return LocalDate.now().minusDays(days).toString();
    }

    // ---------- examples ----------

    /**
     * The minimal anchored query: a haemoglobin in the 24 hours up to now. Dates are relative to the clock, so
     * the window genuinely moves - "yesterday" is inside it and "three days ago" is not.
     */
    @Test
    public void hemoglobinLast24h() throws Exception {
        var b = bundle();
        patient(b, "recent");
        observation(b, "recent-hb", "recent", LOINC, "718-7", daysAgo(0));
        patient(b, "stale");
        observation(b, "stale-hb", "stale", LOINC, "718-7", daysAgo(3));
        patient(b, "none");

        assertEquals(1, evaluate("ccdl-example-hemoglobin-last-24h.json", b));
    }

    /**
     * "Between event A and event B": a haemoglobin inside the window the two anchors intersect out. The third
     * patient is the one that matters - their resection precedes their diagnosis, so the window inverts, which
     * must exclude them rather than fail the evaluation.
     */
    @Test
    public void hemoglobinBetweenTwoAnchors() throws Exception {
        var b = bundle();
        patient(b, "between");
        condition(b, "between-dx", "between", ICD, "K57.3", "2024-01-10");
        procedure(b, "between-op", "between", "5-455.3", "2024-02-10");
        observation(b, "between-hb", "between", LOINC, "718-7", "2024-01-20");

        patient(b, "after-op");
        condition(b, "after-op-dx", "after-op", ICD, "K57.3", "2024-01-10");
        procedure(b, "after-op-op", "after-op", "5-455.3", "2024-02-10");
        observation(b, "after-op-hb", "after-op", LOINC, "718-7", "2024-03-01");

        patient(b, "inverted");
        condition(b, "inverted-dx", "inverted", ICD, "K57.3", "2024-02-10");
        procedure(b, "inverted-op", "inverted", "5-455.3", "2024-01-10");
        observation(b, "inverted-hb", "inverted", LOINC, "718-7", "2024-01-20");

        assertEquals(1, evaluate("ccdl-example-hemoglobin-between-two-anchors.json", b));
    }

    /**
     * Three group arrays OR'd, so a patient qualifies through any one of them. The third array references the
     * second's anchor without listing it, which is the asymmetric requiredness case: it needs the diagnosis to
     * resolve a date from, but not as a criterion of its own.
     */
    @Test
    public void orScopedAnchors() throws Exception {
        var b = bundle();
        // array 1: female AND a CRP
        patient(b, "gender-crp", "female", "1960-01-01");
        observation(b, "gender-crp-crp", "gender-crp", LOINC, "1988-5", "2024-01-05");

        // array 2: the dementia anchor plus donepezil inside 30 days
        patient(b, "donepezil", "male", "1955-01-01");
        condition(b, "donepezil-dx", "donepezil", ICD, "F00", "2024-01-10");
        medicationStatement(b, "donepezil-med", "donepezil", "N06DA02", "2024-01-20");

        // array 3: a weight from 7 days before the diagnosis onward, anchor referenced but not listed
        patient(b, "weight", "male", "1955-01-01");
        condition(b, "weight-dx", "weight", ICD, "G30", "2024-01-10");
        observation(b, "weight-obs", "weight", LOINC, "29463-7", "2024-01-13");

        // matches nothing: male, a CRP but no diagnosis, so arrays 1-3 all fail
        patient(b, "none", "male", "1955-01-01");
        observation(b, "none-crp", "none", LOINC, "1988-5", "2024-01-05");

        assertEquals(3, evaluate("ccdl-example-or-scoped-anchors-draft.json", b));
    }

    /**
     * A multi-clause AND-anchor: the two procedure codes are separate clauses, so the anchor keeps two dates and
     * the dependent's window runs from the later one minus 24h to the earlier one plus 24h. {@code spread-out}
     * is the case that matters - its two procedures are five days apart, so that window inverts and the patient
     * must drop out rather than fail the evaluation.
     */
    @Test
    public void hemoglobinAfterProcedure() throws Exception {
        var b = bundle();
        patient(b, "first-path", "female", "1960-01-01");
        procedure(b, "first-path-p1", "first-path", "5-470.1", "2024-01-10");
        procedure(b, "first-path-p2", "first-path", "8-718.94", "2024-01-10");
        observation(b, "first-path-hb", "first-path", LOINC, "718-7", "2024-01-10");
        condition(b, "first-path-dx", "first-path", ICD, "E10.9", "2024-01-10");

        patient(b, "second-path", "male", "1955-01-01");
        procedure(b, "second-path-p1", "second-path", "5-470.1", "2024-02-10");
        procedure(b, "second-path-p2", "second-path", "8-718.94", "2024-02-10");
        observation(b, "second-path-hb", "second-path", LOINC, "718-7", "2024-02-10");
        condition(b, "second-path-dx", "second-path", ICD, "E11.9", "2024-02-10");

        patient(b, "spread-out", "female", "1960-01-01");
        procedure(b, "spread-out-p1", "spread-out", "5-470.1", "2024-03-01");
        procedure(b, "spread-out-p2", "spread-out", "8-718.94", "2024-03-06");
        observation(b, "spread-out-hb", "spread-out", LOINC, "718-7", "2024-03-03");
        condition(b, "spread-out-dx", "spread-out", ICD, "E10.9", "2024-03-03");

        patient(b, "no-procedure", "female", "1960-01-01");
        observation(b, "no-procedure-hb", "no-procedure", LOINC, "718-7", "2024-04-01");
        condition(b, "no-procedure-dx", "no-procedure", ICD, "E10.9", "2024-04-01");

        assertEquals(2, evaluate("ccdl-example-hemoglobin-after-procedure.json", b));
    }

    /**
     * The shared-witness rule on the published example: a delirium episode with {@code anchorOccurrence: "any"},
     * referenced by both a haloperidol group and a sodium group. {@code split} has everything the query asks for
     * except that no <em>single</em> episode carries both - one episode was treated, a later one was worked up -
     * and must therefore be excluded. Under the per-reference-independent reading the draft rejects, it would
     * count.
     */
    @Test
    public void anyChainedAnchors() throws Exception {
        var b = bundle();
        patient(b, "same-episode");
        condition(b, "same-episode-dx", "same-episode", ICD, "F00", "2024-01-01");
        condition(b, "same-episode-delirium", "same-episode", ICD, "F05", "2024-01-11");
        medicationStatement(b, "same-episode-halo", "same-episode", "N05AD01", "2024-01-12");
        observation(b, "same-episode-na", "same-episode", LOINC, "2951-2", "2024-01-11");

        patient(b, "split");
        condition(b, "split-dx", "split", ICD, "F00", "2024-01-01");
        condition(b, "split-delirium-a", "split", ICD, "F05", "2024-01-05");
        medicationStatement(b, "split-halo", "split", "N05AD01", "2024-01-06");
        condition(b, "split-delirium-b", "split", ICD, "F05", "2024-01-20");
        observation(b, "split-na", "split", LOINC, "2951-2", "2024-01-20");

        patient(b, "no-delirium");
        condition(b, "no-delirium-dx", "no-delirium", ICD, "F00", "2024-01-01");
        medicationStatement(b, "no-delirium-halo", "no-delirium", "N05AD01", "2024-01-12");
        observation(b, "no-delirium-na", "no-delirium", LOINC, "2951-2", "2024-01-11");

        assertEquals(1, evaluate("ccdl-example-any-chained-anchors-draft.json", b));
    }

    /**
     * Three {@code "any"} anchors stacked: sepsis, then an AKI within 7 days of <em>that</em> episode, then
     * dialysis within 14 days of <em>that</em> injury, then a haemoglobin within a day of <em>that</em> session.
     * {@code late-episode} is the discriminator - its qualifying path runs through its <em>second</em> sepsis,
     * so a {@code first}/{@code last} anchor would miss it and only an existential finds it.
     */
    @Test
    public void anyChainThreeHops() throws Exception {
        var b = bundle();
        patient(b, "chain");
        condition(b, "chain-sepsis", "chain", ICD, "A41.5", "2024-01-01");
        condition(b, "chain-aki", "chain", ICD, "N17.0", "2024-01-04");
        procedure(b, "chain-dialysis", "chain", "8-85a.0", "2024-01-11");
        observation(b, "chain-hb", "chain", LOINC, "718-7", "2024-01-11");

        patient(b, "late-episode");
        condition(b, "late-episode-sepsis-a", "late-episode", ICD, "A41.5", "2024-01-01");
        condition(b, "late-episode-sepsis-b", "late-episode", ICD, "A41.5", "2024-03-01");
        condition(b, "late-episode-aki", "late-episode", ICD, "N17.0", "2024-03-04");
        procedure(b, "late-episode-dialysis", "late-episode", "8-85a.0", "2024-03-10");
        observation(b, "late-episode-hb", "late-episode", LOINC, "718-7", "2024-03-10");

        // the AKI is months after the sepsis, so no episode links the chain
        patient(b, "broken-link");
        condition(b, "broken-link-sepsis", "broken-link", ICD, "A41.5", "2024-01-01");
        condition(b, "broken-link-aki", "broken-link", ICD, "N17.0", "2024-02-15");
        procedure(b, "broken-link-dialysis", "broken-link", "8-85a.0", "2024-02-20");
        observation(b, "broken-link-hb", "broken-link", LOINC, "718-7", "2024-02-20");

        patient(b, "no-hemoglobin");
        condition(b, "no-hemoglobin-sepsis", "no-hemoglobin", ICD, "A41.5", "2024-01-01");
        condition(b, "no-hemoglobin-aki", "no-hemoglobin", ICD, "N17.0", "2024-01-04");
        procedure(b, "no-hemoglobin-dialysis", "no-hemoglobin", "8-85a.0", "2024-01-11");

        assertEquals(2, evaluate("ccdl-example-any-chain-three-hops-draft.json", b));
    }

    /**
     * A multi-clause {@code "any"} anchor, where the witness is a tuple of one sepsis and one kidney injury and
     * the dependents' windows run from the later member to the earlier member plus the offset.
     * {@code far-apart}'s two clause dates are ten days apart, which inverts both windows; {@code two-options}
     * qualifies only through the pairing of its second sepsis with the injury, which is what makes the
     * quantification range over the product rather than over one fixed combination.
     */
    @Test
    public void anyMultiClauseAnchor() throws Exception {
        var b = bundle();
        patient(b, "tight");
        condition(b, "tight-sepsis", "tight", ICD, "A41.5", "2024-01-01");
        condition(b, "tight-aki", "tight", ICD, "N17.0", "2024-01-02");
        observation(b, "tight-hb", "tight", LOINC, "718-7", "2024-01-03");
        observation(b, "tight-crp", "tight", LOINC, "1988-5", "2024-01-02");

        patient(b, "two-options");
        condition(b, "two-options-sepsis-a", "two-options", ICD, "A41.5", "2024-01-01");
        condition(b, "two-options-sepsis-b", "two-options", ICD, "A41.5", "2024-02-20");
        condition(b, "two-options-aki", "two-options", ICD, "N17.0", "2024-02-21");
        observation(b, "two-options-hb", "two-options", LOINC, "718-7", "2024-02-22");
        observation(b, "two-options-crp", "two-options", LOINC, "1988-5", "2024-02-21");

        patient(b, "far-apart");
        condition(b, "far-apart-sepsis", "far-apart", ICD, "A41.5", "2024-01-01");
        condition(b, "far-apart-aki", "far-apart", ICD, "N17.0", "2024-01-11");
        observation(b, "far-apart-hb", "far-apart", LOINC, "718-7", "2024-01-12");
        observation(b, "far-apart-crp", "far-apart", LOINC, "1988-5", "2024-01-11");

        patient(b, "no-crp");
        condition(b, "no-crp-sepsis", "no-crp", ICD, "A41.5", "2024-01-01");
        condition(b, "no-crp-aki", "no-crp", ICD, "N17.0", "2024-01-02");
        observation(b, "no-crp-hb", "no-crp", LOINC, "718-7", "2024-01-03");

        assertEquals(2, evaluate("ccdl-example-any-multi-clause-anchor-draft.json", b));
    }

    /**
     * The realistic cohort, exercising both sides at once. The exclusion array is an AND of its two groups, so
     * {@code anticoagulant-only} is <em>not</em> excluded despite the anticoagulant - it has no organ failure -
     * while {@code excluded} has both and drops out. The respiratory rate is dated relative to the clock,
     * because its anchor is {@code now}.
     */
    @Test
    public void withNewTimeConstraint() throws Exception {
        var b = bundle();
        for (var id : new String[]{"included", "anticoagulant-only", "excluded", "no-heart-rate"}) {
            patient(b, id, "female", "1955-01-01");
            condition(b, id + "-dx", id, ICD, "F00", "2024-01-10");
            observation(b, id + "-crp", id, LOINC, "1988-5", "2024-01-09");
            medicationStatement(b, id + "-donepezil", id, "N06DA02", "2024-01-20");
            observation(b, id + "-weight", id, LOINC, "29463-7", "2024-01-07");
            observation(b, id + "-rr", id, LOINC, "9279-1", daysAgo(3));
            if (!id.equals("no-heart-rate")) {
                observation(b, id + "-hr", id, LOINC, "8867-4", "2024-01-09");
            }
        }
        medicationStatement(b, "anticoagulant-only-vka", "anticoagulant-only", "B01AA04", "2024-01-11");
        medicationStatement(b, "excluded-vka", "excluded", "B01AA04", "2024-01-11");
        condition(b, "excluded-organ", "excluded", ICD, "N18.8", "2023-06-01");

        assertEquals(2, evaluate("ccdl-with-new-time-constraint-draft.json", b));
    }

    /** Everything the first inclusion array of the all-features example asks for, for one patient. */
    private static void allFeaturesMainPath(Bundle b, String id) {
        patient(b, id, "female", "1955-01-01");
        condition(b, id + "-dx", id, ICD, "K57.3", "2024-01-10");
        procedure(b, id + "-resection", id, "5-455.3", "2024-02-10");
        procedure(b, id + "-transfusion", id, "8-805.0", "2024-02-10");
        observation(b, id + "-hb", id, LOINC, "718-7", "2024-01-20");
        condition(b, id + "-sepsis", id, ICD, "A41.5", "2024-02-15");
        condition(b, id + "-aki", id, ICD, "N17.0", "2024-02-18");
        procedure(b, id + "-dialysis", id, "8-85a.0", "2024-02-25");
        observation(b, id + "-crp", id, LOINC, "1988-5", "2024-02-17");
    }

    /**
     * The reference example, every construct at once. Four patients probe three different seams.
     * <ul>
     * <li>{@code main-path} satisfies the first inclusion array outright.</li>
     * <li>{@code specimen-path} satisfies the <em>second</em> array instead: a biopsy specimen whose
     * {@code attributeFilters} reference resolves to its diverticular disease diagnosis, plus a follow-up lab
     * between the resection and now. It never has the sepsis cascade at all.</li>
     * <li>{@code excluded} has the first array's data and both halves of the exclusion - an anticoagulant around
     * the resection <em>and</em> both organ failures - so it drops out.</li>
     * <li>{@code partial-exclusion} has the anticoagulant and only one of the two organ failures. The exclusion
     * group's criteria are a single clause, which on the exclusion side conjoins, so one is not enough and this
     * patient stays in. That asymmetry against
     * {@link #withNewTimeConstraint()}, where the two failures are separate clauses and therefore disjoin, is
     * easy to get wrong by eye.</li>
     * </ul>
     */
    @Test
    public void allFeatures() throws Exception {
        var b = bundle();
        allFeaturesMainPath(b, "main-path");

        allFeaturesMainPath(b, "excluded");
        medicationStatement(b, "excluded-vka", "excluded", "B01AA04", "2024-02-11");
        condition(b, "excluded-ckd", "excluded", ICD, "N18.8", "2023-06-01");
        condition(b, "excluded-liver", "excluded", ICD, "K72", "2023-06-01");

        allFeaturesMainPath(b, "partial-exclusion");
        medicationStatement(b, "partial-exclusion-vka", "partial-exclusion", "B01AA04", "2024-02-11");
        condition(b, "partial-exclusion-ckd", "partial-exclusion", ICD, "N18.8", "2023-06-01");

        patient(b, "specimen-path", "male", "1950-01-01");
        condition(b, "specimen-path-dx", "specimen-path", ICD, "K57.3", "2024-01-10");
        procedure(b, "specimen-path-resection", "specimen-path", "5-455.3", "2024-02-10");
        procedure(b, "specimen-path-transfusion", "specimen-path", "8-805.0", "2024-02-10");
        specimen(b, "specimen-path-sample", "specimen-path", "16213411000119100", "specimen-path-dx");
        observation(b, "specimen-path-hb", "specimen-path", LOINC, "718-7", "2024-06-01");

        assertEquals(3, evaluate("ccdl-example-all-features-draft.json", b));
    }
}
