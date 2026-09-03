package de.medizininformatikinitiative.cctb;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static java.lang.String.format;
import static org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION;
import static org.hl7.fhir.r4.model.Bundle.HTTPVerb.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Confirms, against real Blaze, the two facts {@link de.medizininformatikinitiative.cctb.model.structured_query.Group
 * Group}'s AND-anchor null-guard design rests on: {@code Min}/{@code Max} ignore null list elements rather than
 * propagating (so a guard built from them alone only proves at least one clause resolved, not all), and a nested
 * {@code from list D where D is null} query fails to detect a null element sourced from a
 * {@code Min}/{@code Max(retrieve)} aggregate (ruling out that fix, which is why the shipped guard uses an indexer
 * instead).
 */
@Testcontainers
public class MinNullIT {

    @Container
    private final GenericContainer<?> blaze = new GenericContainer<>(DockerImageName.parse("samply/blaze:0.34"))
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupAttempts(3);

    private final FhirContext fhirContext = FhirContext.forR4();
    private IGenericClient fhirClient;

    private static String slurp(String name) throws Exception {
        try (InputStream in = MinNullIT.class.getResourceAsStream(name)) {
            if (in == null) throw new Exception("Can't find `%s`".formatted(name));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Exception("error reading " + name);
        }
    }

    @BeforeEach
    public void setUp() {
        fhirClient = fhirContext.newRestfulGenericClient(format("http://localhost:%d/fhir", blaze.getFirstMappedPort()));
    }

    @Test
    public void minOverListWithOneNullElement() throws Exception {
        // Min({null, @2024-01-10T}) - does Blaze ignore the null (SQL-style) or propagate it?
        var cql = """
                library Retrieve version '1.0.0'
                using FHIR version '4.0.0'
                include FHIRHelpers version '4.0.0'

                context Patient

                define InInitialPopulation:
                  Min({ (null as DateTime), @2024-01-10T }) is not null
                """;

        var bundle = new Bundle().setType(TRANSACTION);
        var patient = new Patient();
        patient.setId("p1");
        bundle.addEntry().setResource(patient).getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Patient/p1");
        fhirClient.transaction().withBundle(bundle).execute();

        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var parser = fhirContext.newJsonParser();
        var libraryTemplate = (org.hl7.fhir.r4.model.Library) parser.parseResource(slurp("Library.json"));
        libraryTemplate.setUrl(libraryUri);
        libraryTemplate.getContentFirstRep().setContentType("text/cql");
        libraryTemplate.getContentFirstRep().setData(cql.getBytes(StandardCharsets.UTF_8));
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = (Measure) parser.parseResource(slurp("Measure.json"));
        measure.setUrl(measureUri).addLibrary(libraryUri);

        var txBundle = new Bundle().setType(TRANSACTION);
        txBundle.addEntry().setResource(libraryTemplate).getRequest().setMethod(POST).setUrl("Library");
        txBundle.addEntry().setResource(measure).getRequest().setMethod(POST).setUrl("Measure");
        fhirClient.transaction().withBundle(txBundle).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount(),
                "Min should ignore the null element (SQL-style) rather than propagate it");
    }

    /**
     * Documents a confirmed Blaze 0.34 bug, not correct behaviour: {@code not exists (from list D where D is
     * null)} fails to detect a null list element when that element came from a {@code Min}/{@code Max(retrieve)}
     * aggregate rather than a literal - even though {@code Min(retrieve) is null} evaluated directly (no nested
     * query) correctly returns true. This is why {@code Group}'s multi-clause AND-anchor guard uses per-index
     * checks ({@link #indexerIntoListOfTwoMinRetrieves}) instead of this pattern. If this test ever starts
     * failing (population count flips from 1 to 0), Blaze has fixed the bug and the indexer workaround may no
     * longer be necessary - worth revisiting {@code Group.aggregateClauseDates} at that point.
     */
    @Test
    public void notExistsNullInListFromTwoMinRetrieves() throws Exception {
        // Mirrors AnchorDate_anchor's real shape: a list of two Min(retrieve) results, one resolving (Condition
        // F00 present), one not (no CRP Observation for this patient) - NOT a literal cast.
        var cql = """
                library Retrieve version '1.0.0'
                using FHIR version '4.0.0'
                include FHIRHelpers version '4.0.0'

                codesystem icd10: 'http://fhir.de/CodeSystem/dimdi/icd-10-gm'
                codesystem loinc: 'http://loinc.org'

                context Patient

                define AnchorDate_anchor:
                  { Min(from [Condition: Code 'F00' from icd10] C
                    return ToDate(C.onset as dateTime)), Min(from [Observation: Code '1988-5' from loinc] O
                    return ToDate(O.effective as dateTime)) }

                define InInitialPopulation:
                  not exists (from AnchorDate_anchor D
                    where D is null)
                """;

        var bundle = new Bundle().setType(TRANSACTION);
        var patient = new Patient();
        patient.setId("p1");
        bundle.addEntry().setResource(patient).getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Patient/p1");
        var condition = new Condition();
        condition.setId("c1");
        condition.setSubject(new Reference("Patient/p1"));
        condition.getCode().addCoding().setSystem("http://fhir.de/CodeSystem/dimdi/icd-10-gm").setCode("F00");
        condition.setOnset(new DateTimeType("2024-01-10"));
        bundle.addEntry().setResource(condition).getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Condition/c1");
        fhirClient.transaction().withBundle(bundle).execute();

        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var parser = fhirContext.newJsonParser();
        var libraryTemplate = (org.hl7.fhir.r4.model.Library) parser.parseResource(slurp("Library.json"));
        libraryTemplate.setUrl(libraryUri);
        libraryTemplate.getContentFirstRep().setContentType("text/cql");
        libraryTemplate.getContentFirstRep().setData(cql.getBytes(StandardCharsets.UTF_8));
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = (Measure) parser.parseResource(slurp("Measure.json"));
        measure.setUrl(measureUri).addLibrary(libraryUri);

        var txBundle = new Bundle().setType(TRANSACTION);
        txBundle.addEntry().setResource(libraryTemplate).getRequest().setMethod(POST).setUrl("Library");
        txBundle.addEntry().setResource(measure).getRequest().setMethod(POST).setUrl("Measure");
        fhirClient.transaction().withBundle(txBundle).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount(),
                "confirmed Blaze bug: should be 0 (null correctly detected) but Blaze 0.34 reports 1 (misses it)");
    }

    /**
     * The fix that actually works: per-index {@code is not null} checks on the same named list, rather than the
     * nested {@code from ... where D is null} query {@link #notExistsNullInListFromTwoMinRetrieves} shows is
     * broken. This is what {@code Group.aggregateClauseDates} generates for a multi-clause AND-anchor's guard.
     */
    @Test
    public void indexerIntoListOfTwoMinRetrieves() throws Exception {
        var cql = """
                library Retrieve version '1.0.0'
                using FHIR version '4.0.0'
                include FHIRHelpers version '4.0.0'

                codesystem icd10: 'http://fhir.de/CodeSystem/dimdi/icd-10-gm'
                codesystem loinc: 'http://loinc.org'

                context Patient

                define AnchorDate_anchor:
                  { Min(from [Condition: Code 'F00' from icd10] C
                    return ToDate(C.onset as dateTime)), Min(from [Observation: Code '1988-5' from loinc] O
                    return ToDate(O.effective as dateTime)) }

                define InInitialPopulation:
                  AnchorDate_anchor[0] is not null and AnchorDate_anchor[1] is not null
                """;

        var bundle = new Bundle().setType(TRANSACTION);
        var patient = new Patient();
        patient.setId("p1");
        bundle.addEntry().setResource(patient).getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Patient/p1");
        var condition = new Condition();
        condition.setId("c1");
        condition.setSubject(new Reference("Patient/p1"));
        condition.getCode().addCoding().setSystem("http://fhir.de/CodeSystem/dimdi/icd-10-gm").setCode("F00");
        condition.setOnset(new DateTimeType("2024-01-10"));
        bundle.addEntry().setResource(condition).getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Condition/c1");
        fhirClient.transaction().withBundle(bundle).execute();

        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var parser = fhirContext.newJsonParser();
        var libraryTemplate = (org.hl7.fhir.r4.model.Library) parser.parseResource(slurp("Library.json"));
        libraryTemplate.setUrl(libraryUri);
        libraryTemplate.getContentFirstRep().setContentType("text/cql");
        libraryTemplate.getContentFirstRep().setData(cql.getBytes(StandardCharsets.UTF_8));
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = (Measure) parser.parseResource(slurp("Measure.json"));
        measure.setUrl(measureUri).addLibrary(libraryUri);

        var txBundle = new Bundle().setType(TRANSACTION);
        txBundle.addEntry().setResource(libraryTemplate).getRequest().setMethod(POST).setUrl("Library");
        txBundle.addEntry().setResource(measure).getRequest().setMethod(POST).setUrl("Measure");
        fhirClient.transaction().withBundle(txBundle).execute();

        var report = fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();

        assertEquals(0, report.getGroupFirstRep().getPopulationFirstRep().getCount(),
                "indexer access should correctly detect the null, unlike the nested from/where query");
    }
}
