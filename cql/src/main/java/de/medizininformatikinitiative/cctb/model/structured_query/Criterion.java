package de.medizininformatikinitiative.cctb.model.structured_query;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import de.medizininformatikinitiative.cctb.model.MappingContext;
import de.medizininformatikinitiative.cctb.model.common.Comparator;
import de.medizininformatikinitiative.cctb.model.common.TermCode;
import de.medizininformatikinitiative.cctb.model.cql.CodeSystemDefinition;
import de.medizininformatikinitiative.cctb.model.cql.Container;
import de.medizininformatikinitiative.cctb.model.cql.DefaultExpression;
import de.medizininformatikinitiative.cctb.model.cql.Expression;
import de.medizininformatikinitiative.cctb.model.cql.IntervalSelector;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import static java.util.Objects.requireNonNull;

/**
 * A single, atomic criterion in Structured Query.
 *
 * @author Alexander Kiel
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public interface Criterion {

    /**
     * A criterion that always evaluates to {@code true}.
     */
    Criterion TRUE = new Criterion() {

        @Override
        public ContextualConcept getConcept() {
            return null;
        }

        @Override
        public Container<DefaultExpression> toCql(MappingContext mappingContext) {
            return Container.of(Expression.TRUE).moveToPatientContext("Criterion");
        }

        @Override
        public Container<DefaultExpression> toReferencesCql(MappingContext mappingContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AttributeFilter> attributeFilters() {
            return List.of();
        }

        @Override
        public TimeRestriction timeRestriction() {
            return null;
        }

        @Override
        public Container<DefaultExpression> dateValuesExpr(MappingContext mappingContext, Group.AnchorPoint anchorPoint) {
            throw new UnsupportedOperationException();
        }
    };

    /**
     * A criterion that always evaluates to {@code false}.
     */
    Criterion FALSE = new Criterion() {

        @Override
        public ContextualConcept getConcept() {
            return null;
        }

        @Override
        public Container<DefaultExpression> toCql(MappingContext mappingContext) {
            return Container.of(Expression.FALSE).moveToPatientContext("Criterion");
        }

        @Override
        public Container<DefaultExpression> toReferencesCql(MappingContext mappingContext) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AttributeFilter> attributeFilters() {
            return List.of();
        }

        @Override
        public TimeRestriction timeRestriction() {
            return null;
        }

        @Override
        public Container<DefaultExpression> dateValuesExpr(MappingContext mappingContext, Group.AnchorPoint anchorPoint) {
            throw new UnsupportedOperationException();
        }
    };

    @JsonCreator
    static Criterion create(@JsonProperty("type") String type,
                            @JsonProperty("context") TermCode context,
                            @JsonProperty("termCodes") List<TermCode> termCodes,
                            @JsonProperty("valueFilter") ObjectNode valueFilter,
                            @JsonProperty("timeRestriction") TimeRestriction timeRestriction,
                            @JsonProperty("attributeFilters") List<ObjectNode> attributeFilters) {
        if ("now".equals(type)) {
            return NowCriterion.INSTANCE;
        }

        var concept = ContextualConcept.of(requireNonNull(context, "missing JSON property: context"),
                Concept.of(requireNonNull(termCodes, "missing JSON property: termCodes")));

        AbstractCriterion<?> criterion;

        if (valueFilter == null) {
            criterion = ConceptCriterion.of(concept, timeRestriction);
        } else {
            var valueFilterType = valueFilter.get("type").asString();
            switch (valueFilterType) {
                case "quantity-comparator" -> {
                    var comparator = Comparator.fromJson(valueFilter.get("comparator").asString());
                    var value = valueFilter.get("value").decimalValue();
                    var unit = valueFilter.get("unit");
                    criterion = unit == null
                            ? NumericCriterion.of(concept, comparator, value, timeRestriction)
                            : NumericCriterion.of(concept, comparator, value, unit.get("code").asString(),
                            timeRestriction);
                }
                case "quantity-range" -> {
                    var lowerBound = valueFilter.get("minValue").decimalValue();
                    var upperBound = valueFilter.get("maxValue").decimalValue();
                    var unit = valueFilter.get("unit");
                    criterion = unit == null
                            ? RangeCriterion.of(concept, lowerBound, upperBound, timeRestriction)
                            : RangeCriterion.of(concept, lowerBound, upperBound,
                            unit.get("code").asString(),
                            timeRestriction);
                }
                case "concept" -> {
                    var selectedConcepts = valueFilter.get("selectedConcepts");
                    if (selectedConcepts == null || selectedConcepts.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Missing or empty `selectedConcepts` key in concept criterion.");
                    }
                    criterion = ValueSetCriterion.of(concept,
                            StreamSupport.stream(selectedConcepts.spliterator(), false)
                                    .map(TermCode::fromJsonNode).toList(), timeRestriction);
                }
                default -> throw new IllegalArgumentException("unknown valueFilter type: " + valueFilterType);
            }
        }

        var attributes = (attributeFilters == null ? List.<ObjectNode>of() : attributeFilters).stream()
                .map(AttributeFilter::fromJsonNode)
                .flatMap(Optional::stream)
                .toList();
        for (var filter : attributes) {
            criterion = criterion.appendAttributeFilter(filter);
        }
        return criterion;
    }

    static Criterion fromJsonNode(JsonNode node) {
        var typeNode = node.get("type");
        return Criterion.create(typeNode == null ? null : typeNode.asString(),
                TermCode.fromJsonNode(node.get("context")),
                getAndMap(node, "termCodes", termCodesNode -> StreamSupport.stream(termCodesNode.spliterator(), false)
                        .map(TermCode::fromJsonNode).toList()),
                asObjectNode(node.get("valueFilter")),
                getAndMap(node, "timeRestriction", TimeRestriction::fromJsonNode),
                getAndMap(node, "attributeFilters", filtersNode ->
                        StreamSupport.stream(filtersNode.spliterator(), false)
                                .map(filterNode -> filterNode.isObject() ? (ObjectNode) filterNode : null).toList()));
    }

    private static ObjectNode asObjectNode(JsonNode node) {
        return node == null ? null : node.isObject() ? (ObjectNode) node : null;
    }

    private static <T> T getAndMap(JsonNode node, String name, Function<JsonNode, T> mapper) {
        var child = node.get(name);
        return child == null ? null : mapper.apply(child);
    }

    ContextualConcept getConcept();

    /**
     * Translates this criterion into a CQL expression.
     *
     * @param mappingContext contains the mappings needed to create the CQL expression
     * @return a {@link Container} of the CQL expression together with its used {@link CodeSystemDefinition
     * CodeSystemDefinitions}
     */
    Container<DefaultExpression> toCql(MappingContext mappingContext);

    /**
     * Translates this criterion into a CQL expression, additionally restricting it to {@code relativeWindow} -
     * used when this criterion sits in a {@link Group} with a {@link RelativeTimeRestriction}.
     * <p>
     * The default implementation ignores {@code relativeWindow}, appropriate for criteria without an
     * instance-level date to restrict (e.g. {@link #TRUE}, {@link #FALSE}, {@link NowCriterion}).
     *
     * @param mappingContext  contains the mappings needed to create the CQL expression
     * @param relativeWindow  the computed time window this criterion's matching resources must fall into
     * @return a {@link Container} of the CQL expression together with its used {@link CodeSystemDefinition
     * CodeSystemDefinitions}
     */
    default Container<DefaultExpression> toCql(MappingContext mappingContext, IntervalSelector relativeWindow) {
        return toCql(mappingContext);
    }

    Container<DefaultExpression> toReferencesCql(MappingContext mappingContext);

    /**
     * Returns a CQL expression evaluating to the list of dates of every resource matching this criterion, reduced
     * to a single point per resource via {@code anchorPoint} - used to resolve the anchor date(s) of a
     * {@link Group} this criterion sits in.
     *
     * @param mappingContext contains the mappings needed to create the CQL expression
     * @param anchorPoint    which point of a {@code Period}-typed match supplies its date
     * @return a {@link Container} of the CQL list expression together with its used {@link CodeSystemDefinition
     * CodeSystemDefinitions}
     */
    Container<DefaultExpression> dateValuesExpr(MappingContext mappingContext, Group.AnchorPoint anchorPoint);

    /**
     * Same as {@link #dateValuesExpr(MappingContext, Group.AnchorPoint)}, additionally restricting the matching
     * resources to {@code relativeWindow} - used when this criterion sits in a chained anchor group that is
     * itself a dependent of another anchor.
     * <p>
     * The default implementation ignores {@code relativeWindow}, appropriate for criteria without an
     * instance-level date to restrict.
     */
    default Container<DefaultExpression> dateValuesExpr(MappingContext mappingContext, Group.AnchorPoint anchorPoint,
                                                         IntervalSelector relativeWindow) {
        return dateValuesExpr(mappingContext, anchorPoint);
    }

    List<AttributeFilter> attributeFilters();

    TimeRestriction timeRestriction();
}
