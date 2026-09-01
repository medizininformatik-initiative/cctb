package de.medizininformatikinitiative.cctb.model.structured_query;

import de.medizininformatikinitiative.cctb.model.AttributeMapping;
import de.medizininformatikinitiative.cctb.model.Mapping;
import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.common.TermCode;
import de.medizininformatikinitiative.cctb.model.cql.*;
import de.medizininformatikinitiative.cctb.util.FhirModelInfo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Abstract criterion holding the concept, every non-static criterion has.
 */
abstract class AbstractCriterion<T extends AbstractCriterion<T>> implements Criterion {

    private static final IdentifierExpression PATIENT = StandardIdentifierExpression.of("Patient");

    final ContextualConcept concept;
    final List<AttributeFilter> attributeFilters;
    final TimeRestriction timeRestriction;

    AbstractCriterion(ContextualConcept concept, List<AttributeFilter> attributeFilters,
                      TimeRestriction timeRestriction) {
        this.concept = requireNonNull(concept);
        this.attributeFilters = List.copyOf(attributeFilters);
        this.timeRestriction = timeRestriction;
    }

    /**
     * Returns the code selector expression according to the given term code.
     *
     * @param mappingContext the mapping context to determine the code system definition of the
     *                       concept
     * @param termCode       the term code to use
     * @return a {@link Container} of the code selector expression together with its used {@link
     * CodeSystemDefinition}
     */
    static Container<CodeSelector> codeSelector(MappingContext mappingContext, TermCode termCode) {
        var codeSystemDefinition = mappingContext.getCodeSystemDefinition(termCode.system());
        return Container.of(CodeSelector.of(termCode.code(), codeSystemDefinition.name()),
                codeSystemDefinition);
    }

    /**
     * Returns the retrieve expression according to the given term code.
     * <p>
     * Uses the mapping context to determine the resource type of the retrieve expression and the code
     * system definition of the concept.
     *
     * @param mappingContext the mapping context
     * @param termCode       the term code to use
     * @return a {@link Container} of the retrieve expression together with its used {@link
     * CodeSystemDefinition}
     * @throws TranslationException if the {@link RetrieveExpression} can't be build
     */
    static Container<RetrieveExpression> retrieveExpr(MappingContext mappingContext,
                                                      ContextualTermCode termCode) {
        var mapping = mappingContext.findMapping(termCode)
                .orElseThrow(() -> new MappingNotFoundException(termCode));
        // Use retrieve filter iff term code mapping exists and the path within the type is retrievable
        return mapping.termCodeMapping().map(tcm ->
                FhirModelInfo.isRetrievable(mapping.resourceType(), tcm.path()) ?
                    codeSelector(mappingContext, termCode.termCode())
                            .map(terminology -> RetrieveExpression.of(mapping.resourceType(), terminology, tcm.path()))
                    :
                    Container.of(RetrieveExpression.of(mapping.resourceType()))
        ).orElseGet(() ->
                codeSelector(mappingContext, mapping.primaryCode()).map(terminology -> RetrieveExpression.of(mapping.resourceType(), terminology))
        );
    }

    private static String referenceName(TermCode termCode) {
        return termCode.code() + "Ref";
    }

    public abstract T appendAttributeFilter(AttributeFilter attributeFilter);

    @Override
    public List<AttributeFilter> attributeFilters() {
        return attributeFilters;
    }

    @Override
    public ContextualConcept getConcept() {
        return concept;
    }

    @Override
    public Container<DefaultExpression> toCql(MappingContext mappingContext) {
        return toCql(mappingContext, null);
    }

    @Override
    public Container<DefaultExpression> toCql(MappingContext mappingContext, IntervalSelector relativeWindow) {
        var expr = fullExpr(mappingContext, relativeWindow);
        if (expr.isEmpty()) {
            throw new TranslationException("Failed to expand the concept %s.".formatted(concept));
        }
        return expr.moveToPatientContext("Criterion");
    }

    @Override
    public Container<DefaultExpression> toReferencesCql(MappingContext mappingContext) {
        return mappingContext.expandConcept(concept)
                .map(termCode -> refExpr(mappingContext, termCode))
                .reduce(Container.empty(), Container.UNION);
    }

    /**
     * Builds an OR-expression with an expression for each concept of the expansion of {@code
     * termCode}.
     */
    private Container<DefaultExpression> fullExpr(MappingContext mappingContext, IntervalSelector relativeWindow) {
        return mappingContext.expandConcept(concept)
                .map(termCode -> expr(mappingContext, termCode, relativeWindow))
                .reduce(Container.empty(), Container.OR);
    }

    private Container<DefaultExpression> expr(MappingContext mappingContext, ContextualTermCode termCode,
                                              IntervalSelector relativeWindow) {
        var mapping = mappingContext.findMapping(termCode)
                .orElseThrow(() -> new MappingNotFoundException(termCode));
        switch (mapping.resourceType()) {
            case "Patient" -> {
                return valueExpr(mappingContext, mapping, PATIENT);
            }
            case "MedicationAdministration", "MedicationStatement", "MedicationRequest" -> {
                var query = medicationReferencesExpr(mappingContext, termCode.termCode())
                        .moveToUnfilteredContext(referenceName(termCode.termCode()))
                        .map(medicationReferencesExpr -> {
                            var retrieveExpr = RetrieveExpression.of(mapping.resourceType());
                            var alias = retrieveExpr.alias();
                            var sourceClause = SourceClause.of(AliasedQuerySource.of(retrieveExpr, alias));
                            var referenceExpression = InvocationExpression.of(alias, "medication.reference");
                            var whereExpr = MembershipExpression.in(referenceExpression, medicationReferencesExpr);
                            return QueryExpression.of(sourceClause, WhereClause.of(whereExpr));
                        });
                return appendModifier(mappingContext, mapping, query, relativeWindow).map(ExistsExpression::of);
            }
            default -> {
                return retrieveExpr(mappingContext, termCode).flatMap(retrieveExpr -> {
                    var alias = retrieveExpr.alias();
                    var sourceClause = SourceClause.of(AliasedQuerySource.of(retrieveExpr, alias));
                    var query = valueExpr(mappingContext, mapping, alias)
                            .map(valueExpr -> QueryExpression.of(sourceClause, WhereClause.of(valueExpr)))
                            .or(() -> QueryExpression.of(sourceClause));
                    return appendModifier(mappingContext, mapping, query, relativeWindow).map(ExistsExpression::of);
                });
            }
        }
    }

    /**
     * Builds a CQL expression evaluating to the list of dates of every matching resource of the expansion of
     * {@code concept}, reduced to a single point per resource via {@code anchorPoint}.
     * <p>
     * Reuses the same retrieve+modifiers query {@link #expr} builds, but projects the date path from {@code
     * mapping.timeRestrictionMapping()} via a {@link ReturnClause} instead of wrapping in {@link ExistsExpression}.
     */
    @Override
    public Container<DefaultExpression> dateValuesExpr(MappingContext mappingContext, Group.AnchorPoint anchorPoint) {
        return dateValuesExpr(mappingContext, anchorPoint, null);
    }

    @Override
    public Container<DefaultExpression> dateValuesExpr(MappingContext mappingContext, Group.AnchorPoint anchorPoint,
                                                        IntervalSelector relativeWindow) {
        return mappingContext.expandConcept(concept)
                .map(termCode -> dateValueExpr(mappingContext, termCode, anchorPoint, relativeWindow))
                .reduce(Container.empty(), Container.UNION);
    }

    private Container<DefaultExpression> dateValueExpr(MappingContext mappingContext, ContextualTermCode termCode,
                                                        Group.AnchorPoint anchorPoint, IntervalSelector relativeWindow) {
        var mapping = mappingContext.findMapping(termCode)
                .orElseThrow(() -> new MappingNotFoundException(termCode));
        var timeRestrictionMapping = mapping.timeRestrictionMapping()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing time restriction in mapping with key %s, required to use it as an anchor."
                                .formatted(mapping.key())));
        return retrieveExpr(mappingContext, termCode).flatMap(retrieveExpr -> {
            var alias = retrieveExpr.alias();
            var sourceClause = SourceClause.of(AliasedQuerySource.of(retrieveExpr, alias));
            var invocationExpr = InvocationExpression.of(alias, timeRestrictionMapping.path());
            var returnClause = ReturnClause.of(dateProjectionExpr(invocationExpr, timeRestrictionMapping, anchorPoint));
            var query = QueryExpression.of(sourceClause, returnClause);
            return appendModifier(mappingContext, mapping, Container.of(query), relativeWindow)
                    .map(WrapperExpression::new);
        });
    }

    /**
     * Casts the date path to a single, comparable value, per possible type of {@code mapping} (structurally the
     * same per-type dispatch as {@link TimeRestrictionModifier}'s dateExpr/dateTimeExpr/instantExpr, kept
     * duplicated here since this projects a raw value rather than building a membership check). {@code PERIOD}
     * needs {@code anchorPoint} ({@code .start}/{@code .end}) before casting. When more than one type is possible
     * (a polymorphic FHIR choice field), the first non-null projection is used via {@code Coalesce} - every
     * branch is wrapped in {@code ToDate(...)} so all of them produce the same {@code System.Date} type;
     * {@code Coalesce} requires its arguments to share one type, and {@code Period.start}/{@code .end} is
     * {@code FHIR.dateTime}, not {@code Date}, so leaving it unwrapped fails CQL type resolution outright (a real
     * bug found via an engine-level regression test - Blaze rejects the library with "Could not resolve call to
     * operator Coalesce with signature (System.Date,FHIR.dateTime)" rather than merely behaving incorrectly).
     */
    private static Expression<?> dateProjectionExpr(InvocationExpression invocationExpr,
                                                     Mapping.TimeRestrictionMapping mapping,
                                                     Group.AnchorPoint anchorPoint) {
        var point = anchorPoint == null ? Group.AnchorPoint.START : anchorPoint;
        List<DefaultExpression> exprs = mapping.types().stream().sorted().<DefaultExpression>map(type -> switch (type) {
            case DATE -> new WrapperExpression(FunctionInvocation.of("ToDate",
                    List.of(TypeExpression.of(invocationExpr, "date"))));
            case DATE_TIME -> new WrapperExpression(FunctionInvocation.of("ToDate",
                    List.of(TypeExpression.of(invocationExpr, "dateTime"))));
            case INSTANT -> new WrapperExpression(FunctionInvocation.of("ToDate",
                    List.of(TypeExpression.of(invocationExpr, "instant"))));
            case PERIOD -> new WrapperExpression(FunctionInvocation.of("ToDate", List.of(
                    InvocationExpression.of(TypeExpression.of(invocationExpr, "Period"),
                            point == Group.AnchorPoint.END ? "end" : "start"))));
        }).toList();
        return exprs.size() == 1 ? exprs.get(0) : FunctionInvocation.of("Coalesce", exprs);
    }

    private Container<DefaultExpression> refExpr(MappingContext mappingContext, ContextualTermCode termCode) {
        var mapping = mappingContext.findMapping(termCode)
                .orElseThrow(() -> new MappingNotFoundException(termCode));
        return retrieveExpr(mappingContext, termCode).flatMap(retrieveExpr -> {
            var alias = retrieveExpr.alias();
            var sourceClause = SourceClause.of(AliasedQuerySource.of(retrieveExpr, alias));
            var query = valueExpr(mappingContext, mapping, alias)
                    .map(valueExpr -> QueryExpression.of(sourceClause, WhereClause.of(valueExpr)))
                    .or(() -> QueryExpression.of(sourceClause));
            return appendModifier(mappingContext, mapping, query, null).map(WrapperExpression::new);
        });
    }

    /*
     * Creates an expression from value criteria that will end up in the where clause.
     */
    abstract Container<DefaultExpression> valueExpr(MappingContext mappingContext, Mapping mapping,
                                                    IdentifierExpression sourceAlias);

    /*
     * Appends expressions from modifier criteria to the query, including a relative time restriction's already
     * computed window, if any (see Group#toCql), which intersects (ANDs) with any absolute per-criterion
     * timeRestriction, matching the "intersect" rule for that interaction.
     */
    private Container<QueryExpression> appendModifier(MappingContext mappingContext, Mapping mapping,
                                                      Container<QueryExpression> queryContainer,
                                                      IntervalSelector relativeWindow) {
        var termCodeModifier = termCodeModifier(mapping);
        var isRetrievable = mapping.termCodeMapping()
                .map(v -> FhirModelInfo.isRetrievable(mapping.resourceType(), v.path()))
                .orElse(false);
        if (termCodeModifier.isPresent() && !isRetrievable) {
            queryContainer = termCodeModifier.get().updateQuery(mappingContext, queryContainer);
        }
        for (var modifier : mapping.fixedCriteria()) {
            queryContainer = modifier.updateQuery(mappingContext, queryContainer);
        }
        for (var modifier : resolveAttributeModifiers(mapping.attributeMappings())) {
            queryContainer = modifier.updateQuery(mappingContext, queryContainer);
        }
        if (timeRestriction != null) {
            queryContainer = timeRestriction.toModifier(mapping).updateQuery(mappingContext, queryContainer);
        }
        if (relativeWindow != null) {
            var relativeTimeRestrictionMapping = mapping.timeRestrictionMapping()
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing time restriction in mapping with key %s, required to apply a relative time restriction."
                                    .formatted(mapping.key())));
            queryContainer = RelativeTimeRestrictionModifier.of(relativeTimeRestrictionMapping, relativeWindow)
                    .updateQuery(mappingContext, queryContainer);
        }
        return queryContainer;
    }

    private Optional<Modifier> termCodeModifier(Mapping mapping) {
        return mapping.termCodeMapping().map(m -> {
            // Mapping ensures that the termCodeMapping has exactly one type
            return switch (m.types().get(0)) {
                case CODING, CODEABLE_CONCEPT ->
                        CodeEquivalentModifier.of(m.path(), m.cardinality(), mapping.primaryCode());
                default ->
                    throw new IllegalArgumentException("Unsupported termCode mapping type `%s`.".formatted(m.types().get(0).fhirTypeName()));
            };
        });
    }

    private List<Modifier> resolveAttributeModifiers(Map<TermCode, AttributeMapping> attributeMappings) {
        return attributeFilters.stream().map(attributeFilter -> {
            var key = attributeFilter.attributeCode();
            var mapping = Optional.ofNullable(attributeMappings.get(key)).orElseThrow(() ->
                    new AttributeMappingNotFoundException(key));
            return attributeFilter.toModifier(mapping);
        }).toList();
    }

    @Override
    public TimeRestriction timeRestriction() {
        return timeRestriction;
    }

    /**
     * Returns a query expression that returns all references of Medication with {@code code}.
     * <p>
     * Has to be placed into the Unfiltered context.
     */
    private Container<QueryExpression> medicationReferencesExpr(MappingContext mappingContext, TermCode code) {
        return codeSelector(mappingContext, code)
                .map(terminology -> RetrieveExpression.of("Medication", terminology))
                .map(retrieveExpr -> {
                    var alias = retrieveExpr.alias();
                    var sourceClause = SourceClause.of(AliasedQuerySource.of(retrieveExpr, alias));
                    var returnClause = ReturnClause.of(AdditionExpressionTerm.of(
                            StringLiteralExpression.of("Medication/"), InvocationExpression.of(alias, "id")));
                    return QueryExpression.of(sourceClause, returnClause);
                });
    }
}
