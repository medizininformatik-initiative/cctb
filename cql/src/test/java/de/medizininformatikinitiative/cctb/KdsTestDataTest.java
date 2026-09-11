package de.medizininformatikinitiative.cctb;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
import tools.jackson.databind.ObjectMapper;
import de.medizininformatikinitiative.cctb.model.structured_query.StructuredQuery;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static de.medizininformatikinitiative.cctb.Util.createTranslator;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION;
import static org.hl7.fhir.r4.model.Bundle.HTTPVerb.POST;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@TestInstance(Lifecycle.PER_CLASS)
public class KdsTestDataTest {

    private static final Logger logger = LoggerFactory.getLogger(KdsTestDataTest.class);

    private final GenericContainer<?> blaze = new GenericContainer<>(
            DockerImageName.parse("samply/blaze:1.11.0"))
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withEnv("LOG_LEVEL", "debug")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withLogConsumer(new Slf4jLogConsumer(logger));

    private final FhirContext fhirContext = FhirContext.forR4();
    private IGenericClient fhirClient;
    private Translator translator;

    private static Path resourcePath(String name) throws URISyntaxException {
        return Paths.get(Objects.requireNonNull(KdsTestDataTest.class.getResource(name), "resource `%s` is missing".formatted(name)).toURI());
    }

    private static String slurp(String name) throws Exception {
        return Files.readString(resourcePath(name));
    }

    private static Bundle createBundle(Library library, Measure measure) {
        var bundle = new Bundle();
        bundle.setType(TRANSACTION);
        bundle.addEntry().setResource(library).getRequest().setMethod(POST).setUrl("Library");
        bundle.addEntry().setResource(measure).getRequest().setMethod(POST).setUrl("Measure");
        return bundle;
    }

    public static Stream<Path> getTestQueriesReturningOnePatient() throws IOException, URISyntaxException {
        return Files.list(resourcePath("returningOnePatient"));
    }

    @BeforeAll
    public void setUp() throws Exception {
        blaze.start();
        fhirContext.getRestfulClientFactory().setSocketTimeout(200 * 1000);
        fhirClient = fhirContext.newRestfulGenericClient(
                format("http://localhost:%d/fhir", blaze.getFirstMappedPort()));
        fhirClient.transaction().withBundle(parseResource(Bundle.class, slurp("Bundle-mii-exa-test-data-bundle.json")))
                .execute();

        translator = createTranslator();
    }

    @Test
    public void encounterInpatient() throws Exception {
        var structuredQuery = new ObjectMapper().readValue(slurp("EncounterInpatient.json"), StructuredQuery.class);
        var cql = translator.toCql(structuredQuery).print();
        var measureUri = createMeasureAndLibrary(cql);
        var report = evaluateMeasure(measureUri);

        assertEquals(2, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    @Test
    public void encounterInpatientEinrichtung() throws Exception {
        var structuredQuery = new ObjectMapper().readValue(slurp("EncounterInpatientEinrichtung.json"), StructuredQuery.class);
        var cql = translator.toCql(structuredQuery).print();
        var measureUri = createMeasureAndLibrary(cql);
        var report = evaluateMeasure(measureUri);

        assertEquals(2, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    @Test
    public void encounterInpatientEinrichtungTimeRestriction() throws Exception {
        var structuredQuery = new ObjectMapper().readValue(slurp("EncounterInpatientEinrichtungTimeRestriction.json"), StructuredQuery.class);
        var cql = translator.toCql(structuredQuery).print();
        System.out.println("cql = " + cql);
        var measureUri = createMeasureAndLibrary(cql);
        var report = evaluateMeasure(measureUri);

        assertEquals(1, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    @Test
    public void encounterInpatientAbteilung() throws Exception {
        var structuredQuery = new ObjectMapper().readValue(slurp("EncounterInpatientAbteilung.json"), StructuredQuery.class);
        var cql = translator.toCql(structuredQuery).print();
        var measureUri = createMeasureAndLibrary(cql);
        var report = evaluateMeasure(measureUri);

        assertEquals(0, report.getGroupFirstRep().getPopulationFirstRep().getCount());
    }

    private String createMeasureAndLibrary(String cql) throws Exception {
        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var library = appendCql(parseResource(Library.class, slurp("Library.json")).setUrl(libraryUri), cql);
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = parseResource(Measure.class, slurp("Measure.json"))
                .setUrl(measureUri)
                .addLibrary(libraryUri);
        var bundle = createBundle(library, measure);

        fhirClient.transaction().withBundle(bundle).execute();

        return measureUri;
    }

    private MeasureReport evaluateMeasure(String measureUri) {
        return fhirClient.operation()
                .onType(Measure.class)
                .named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet()
                .returnResourceType(MeasureReport.class)
                .execute();
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
