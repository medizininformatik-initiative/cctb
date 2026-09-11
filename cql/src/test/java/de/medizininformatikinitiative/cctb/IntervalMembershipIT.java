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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static java.lang.String.format;
import static org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION;
import static org.hl7.fhir.r4.model.Bundle.HTTPVerb.POST;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Pins the Blaze behaviour that {@link
 * de.medizininformatikinitiative.cctb.model.structured_query.TimeRestrictionModifier TimeRestrictionModifier}
 * depends on when it emits {@code X in Interval[...]}.
 * <p>
 * {@code In} is valid CQL in any position - the specification defines it as equivalent to
 * {@code Interval[...] contains X} and places no restriction on where either may appear - but Blaze's compiler
 * never implements {@code In} itself. It relies on an ELM normalization pass to rewrite {@code In(a,b)} into
 * {@code Contains(b,a)} first, and up to and including Blaze 0.34 that normalizer descended into query
 * {@code where} clauses while having no case for aggregate expressions. A {@code Min} node fell through to
 * {@code :default}, its {@code :source} was never descended into, the {@code In} survived into compilation, and
 * Blaze rejected the entire library with "Unsupported In expression. Please normalize the ELM tree before
 * compiling."
 * <p>
 * That is exactly the shape the anchor path produces for a <em>chained</em> anchor, where candidate dates come
 * from {@code Min}/{@code Max(from ... where <window> return ...)}. Chained anchors had only ever been covered by
 * printed-string assertions in {@code TranslatorTest}, so for a while the translator emitted CQL that the engine
 * then in use could not accept at all. Later Blaze adds {@code normalize-aggregate}, which updates the
 * aggregate's {@code :source}, and the shape compiles.
 * <p>
 * {@link #inWorksInsideAggregateWhere} is therefore a guard on the pinned image: it fails if the Blaze version
 * these tests run against is ever moved back below that fix, which would silently break every chained anchor.
 * The remaining tests record that the alternatives are equally acceptable, so switching the emitted operator
 * stays a free choice rather than a correctness question.
 */
@Testcontainers
public class IntervalMembershipIT {

    @Container
    private final GenericContainer<?> blaze = new GenericContainer<>(DockerImageName.parse("samply/blaze:1.11.0"))
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
            .withStartupAttempts(3);

    private final FhirContext fhirContext = FhirContext.forR4();
    private IGenericClient fhirClient;

    private static String slurp(String name) throws Exception {
        try (InputStream in = IntervalMembershipIT.class.getResourceAsStream(name)) {
            if (in == null) throw new Exception("Can't find " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @BeforeEach
    public void setUp() {
        fhirClient = fhirContext.newRestfulGenericClient(format("http://localhost:%d/fhir", blaze.getFirstMappedPort()));
        var bundle = new Bundle().setType(TRANSACTION);
        var patient = new Patient();
        patient.setId("p1");
        bundle.addEntry().setResource(patient).getRequest().setMethod(Bundle.HTTPVerb.PUT).setUrl("Patient/p1");
        fhirClient.transaction().withBundle(bundle).execute();
    }

    /** Installs and evaluates a library with {@code body} appended, throwing if Blaze rejects it. */
    private void evaluate(String body) throws Exception {
        var cql = """
                library Retrieve version '1.0.0'
                using FHIR version '4.0.0'
                include FHIRHelpers version '4.0.0'

                codesystem loinc: 'http://loinc.org'

                context Patient

                """ + body;
        var libraryUri = "urn:uuid" + UUID.randomUUID();
        var parser = fhirContext.newJsonParser();
        var library = (Library) parser.parseResource(slurp("Library.json"));
        library.setUrl(libraryUri);
        library.getContentFirstRep().setContentType("text/cql");
        library.getContentFirstRep().setData(cql.getBytes(StandardCharsets.UTF_8));
        var measureUri = "urn:uuid" + UUID.randomUUID();
        var measure = (Measure) parser.parseResource(slurp("Measure.json"));
        measure.setUrl(measureUri).addLibrary(libraryUri);

        var tx = new Bundle().setType(TRANSACTION);
        tx.addEntry().setResource(library).getRequest().setMethod(POST).setUrl("Library");
        tx.addEntry().setResource(measure).getRequest().setMethod(POST).setUrl("Measure");
        fhirClient.transaction().withBundle(tx).execute();

        fhirClient.operation().onType(Measure.class).named("evaluate-measure")
                .withSearchParameter(Parameters.class, "measure", new StringParam(measureUri))
                .andSearchParameter("periodStart", new DateParam("1900"))
                .andSearchParameter("periodEnd", new DateParam("2100"))
                .useHttpGet().returnResourceType(MeasureReport.class).execute();
    }

    /**
     * The shape a chained anchor produces. Fails on Blaze 0.34 and earlier, where the ELM normalizer has no
     * aggregate case; passes on versions carrying {@code normalize-aggregate}. If this ever goes red, the
     * pinned image has moved backwards and chained anchors are broken.
     */
    @Test
    public void inWorksInsideAggregateWhere() {
        assertDoesNotThrow(() -> evaluate("""
                define Anchor:
                  Min(from [Observation: Code '1988-5' from loinc] O
                    where ToDate(O.effective as dateTime) in Interval[@2024-01-01T, @2024-02-01T]
                    return ToDate(O.effective as dateTime))

                define InInitialPopulation:
                  Anchor is not null
                """));
    }

    /** The same membership test as {@code contains}, in the same aggregate position: accepted. */
    @Test
    public void containsWorksInsideAggregateWhere() {
        assertDoesNotThrow(() -> evaluate("""
                define Anchor:
                  Min(from [Observation: Code '1988-5' from loinc] O
                    where Interval[@2024-01-01T, @2024-02-01T] contains ToDate(O.effective as dateTime)
                    return ToDate(O.effective as dateTime))

                define InInitialPopulation:
                  Anchor is not null
                """));
    }

    /** Control: the same operator in the criterion-matching position, which always worked. */
    @Test
    public void inWorksInsideExists() {
        assertDoesNotThrow(() -> evaluate("""
                define InInitialPopulation:
                  exists (from [Observation: Code '1988-5' from loinc] O
                    where ToDate(O.effective as dateTime) in Interval[@2024-01-01T, @2024-02-01T])
                """));
    }

    /**
     * Why {@code contains} is emitted uniformly rather than only on the anchor path: it is equally valid in the
     * criterion-matching position, so there is one emitted shape to reason about instead of two.
     */
    @Test
    public void containsWorksInsideExists() {
        assertDoesNotThrow(() -> evaluate("""
                define InInitialPopulation:
                  exists (from [Observation: Code '1988-5' from loinc] O
                    where Interval[@2024-01-01T, @2024-02-01T] contains ToDate(O.effective as dateTime))
                """));
    }

    /** {@code overlaps}, which the PERIOD branch of {@code distribute} emits, is unaffected in either position. */
    @Test
    public void overlapsWorksInsideAggregateWhere() {
        assertDoesNotThrow(() -> evaluate("""
                define Anchor:
                  Min(from [Observation: Code '1988-5' from loinc] O
                    where O.effective overlaps Interval[@2024-01-01T, @2024-02-01T]
                    return ToDate(O.effective as dateTime))

                define InInitialPopulation:
                  Anchor is not null
                """));
    }
}
