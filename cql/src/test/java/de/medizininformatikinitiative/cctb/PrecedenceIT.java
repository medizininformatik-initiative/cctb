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

@Testcontainers
public class PrecedenceIT {

    @Container
    private final GenericContainer<?> blaze = new GenericContainer<>(DockerImageName.parse("samply/blaze:0.34"))
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupAttempts(3);

    private final FhirContext fhirContext = FhirContext.forR4();
    private IGenericClient fhirClient;

    private static String slurp(String name) throws Exception {
        try (InputStream in = PrecedenceIT.class.getResourceAsStream(name)) {
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
    public void andBindsTighterThanOr() throws Exception {
        // true and true or true and false
        // AND-tighter (correct CQL grammar): (true and true) or (true and false) = true or false = TRUE
        // naive left-to-right, no precedence: ((true and true) or true) and false = (true or true) and false = FALSE
        var cql = """
                library Retrieve version '1.0.0'
                using FHIR version '4.0.0'
                include FHIRHelpers version '4.0.0'

                context Patient

                define InInitialPopulation:
                  true and true or true and false
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

        System.out.println("=== precedence test population count: " + report.getGroupFirstRep().getPopulationFirstRep().getCount()
                + " (1 = AND binds tighter [correct], 0 = naive left-to-right) ===");
        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }
}
