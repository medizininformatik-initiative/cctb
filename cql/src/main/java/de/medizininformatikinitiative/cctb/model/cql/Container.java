package de.medizininformatikinitiative.cctb.model.cql;

import de.medizininformatikinitiative.cctb.Maps;
import de.medizininformatikinitiative.cctb.Sets;

import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * A container holds an expression, all its referenced definitions and the code system definitions
 * used by them.
 * <p>
 * Containers can be {@link #combiner combined}, collecting all code system definitions the
 * individual contains use.
 * <p>
 * Instances are immutable.
 *
 * @author Alexander Kiel
 */
public final class Container<T extends Expression<T>> {

    private static final Container<DefaultExpression> EMPTY = new Container<>(null, Set.of(), Set.of(), List.of());
    public static final BinaryOperator<Container<DefaultExpression>> AND = combiner(AndExpression::of);
    public static final BinaryOperator<Container<DefaultExpression>> AND_NOT = combiner((a, b) -> a.and(NotExpression.of(b)));
    public static final BinaryOperator<Container<DefaultExpression>> OR = combiner(OrExpression::of);
    public static final BinaryOperator<Container<DefaultExpression>> UNION = combiner(UnionExpression::of);
    private static final String HEADER = """
            library Retrieve version '1.0.0'
            using FHIR version '4.0.0'
            include FHIRHelpers version '4.0.0'
            """;
    private final T expression;
    private final Set<CodeSystemDefinition> codeSystemDefinitions;
    private final Set<ExpressionDefinition> unfilteredDefinitions;
    private final List<ExpressionDefinition> patientDefinitions;

    private Container(T expression, Set<CodeSystemDefinition> codeSystemDefinitions,
                      Set<ExpressionDefinition> unfilteredDefinitions,
                      List<ExpressionDefinition> patientDefinitions) {
        this.expression = expression;
        this.codeSystemDefinitions = codeSystemDefinitions;
        this.unfilteredDefinitions = unfilteredDefinitions;
        this.patientDefinitions = patientDefinitions;
    }

    /**
     * Returns the empty container that contains no expression and no code system definitions.
     * <p>
     * The empty container is the identity element of the binary {@link #combiner combine} operation.
     *
     * @param <T> the type of the expression
     * @return the empty container
     */
    public static <T extends Expression<T>> Container<T> empty() {
        @SuppressWarnings("unchecked")
        Container<T> empty = (Container<T>) EMPTY;
        return empty;
    }

    /**
     * Returns a container holding {@code expression} and {@code codeSystemDefinitions}.
     *
     * @param expression            the expression being hold
     * @param codeSystemDefinitions the code system definition being hold
     * @param <T>                   the type of the expression
     * @return a container
     * @throws NullPointerException if {@code expression} is null
     */
    public static <T extends Expression<T>> Container<T> of(T expression, CodeSystemDefinition... codeSystemDefinitions) {
        return new Container<>(requireNonNull(expression), Set.of(codeSystemDefinitions), Set.of(), List.of());
    }

    /**
     * Returns a binary operator that combines expressions using {@code combiner} and the code system
     * definitions with set union.
     * <p>
     * Because the {@link #empty() empty conatainer} is the identity element of the returned operator
     * {@code op}, the following holds: {@code op.apply(empty, x) == x} and {@code op.apply(x, empty)
     * == x}.
     *
     * @param combiner the binary operator to combine the expressions of both containers
     * @return a container holding the combined expression and the union of the code system
     * definitions of both containers
     * @throws NullPointerException if {@code combiner} is null
     */
    public static <T extends Expression<T>> BinaryOperator<Container<T>> combiner(BinaryOperator<T> combiner) {
        return (a, b) -> {
            if (a == EMPTY) return b;
            if (b == EMPTY) return a;
            var aSuffixes = a.suffixes();
            var bSuffixes = b.suffixes();
            // create increments of suffixes in A that were zero but are also in B
            var aIncrements = new HashMap<String, Integer>();
            var bIncrements = new HashMap<String, Integer>();
            aSuffixes.entrySet().stream()
                    .filter(e -> e.getValue() == 0)
                    .map(Map.Entry::getKey)
                    .filter(bSuffixes::containsKey)
                    .forEach(k -> {
                        aIncrements.put(k, 1);
                        bIncrements.put(k, bSuffixes.get(k) == 0 ? 2 : 1);
                    });
            // getOrDefault, not get: a prefix carrying a non-zero suffix in A need not appear in B at all, and
            // unboxing the resulting null threw. Reachable once two containers being combined can differ in
            // which named definitions they carry - e.g. when some criteria are kept inline inside an `any`
            // anchor's correlated exists while their siblings are hoisted into `Criterion` definitions. A prefix
            // absent from B has nothing there to shift, so the increment it gets is immaterial; treating it as
            // zero keeps this consistent with the "present but unsuffixed" case on the line above.
            aSuffixes.entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .forEach(e -> bIncrements.put(e.getKey(),
                            e.getValue() + (bSuffixes.getOrDefault(e.getKey(), 0) == 0 ? 1 : 0)));

            return new Container<>(combiner.apply(a.expression.withIncrementedSuffixes(aIncrements),
                    b.expression.withIncrementedSuffixes(bIncrements)),
                    Sets.union(a.codeSystemDefinitions, b.codeSystemDefinitions),
                    Sets.union(a.unfilteredDefinitions, b.unfilteredDefinitions),
                    ExpressionDefinitions.unionByName(a.withIncrementedSuffixes(aIncrements),
                            b.withIncrementedSuffixes(bIncrements)));
        };
    }

    /**
     * Returns a map of patient definitions name identifier prefixes to their maximum numerical suffixes.
     *
     * @return a map of identifier prefixes to numerical suffixes
     */
    private Map<String, Integer> suffixes() {
        return patientDefinitions.stream()
                .map(ExpressionDefinition::suffixes)
                .reduce(Map.of(), Maps.merge(Integer::max));
    }

    private List<ExpressionDefinition> withIncrementedSuffixes(Map<String, Integer> increments) {
        return patientDefinitions.stream().map(d -> d.withIncrementedSuffixes(increments)).toList();
    }

    /**
     * Returns the expression the container holds.
     *
     * @return the expression or {@link Optional#empty()} iff this container is {@link #isEmpty()
     * empty}.
     */
    public Optional<T> getExpression() {
        return Optional.ofNullable(expression);
    }

    /**
     * Returns the code system definitions the container holds.
     *
     * @return the code system definitions the container holds
     */
    public Set<CodeSystemDefinition> getCodeSystemDefinitions() {
        return codeSystemDefinitions;
    }

    private Optional<Context> getUnfilteredContext() {
        var list = new ArrayList<>(unfilteredDefinitions);
        list.sort(Comparator.comparing(ExpressionDefinition::name));
        return unfilteredDefinitions.isEmpty()
                ? Optional.empty() :
                Optional.of(Context.of("Unfiltered", List.copyOf(list)));
    }

    private Optional<Context> getPatientContext() {
        return patientDefinitions.isEmpty()
                ? Optional.empty() :
                Optional.of(Context.of("Patient", patientDefinitions));
    }

    /**
     * Moves the expression of this container into the unfiltered context and returns a Container with an
     * {@link IdentifierExpression} holding {@code name}.
     * <p>
     * If this Container just holds an {@link IdentifierExpression} it's not moved.
     *
     * @param name the name of the expression definition in the Unfiltered context
     * @return a Container with an {@link IdentifierExpression} holding {@code name}
     */
    public Container<DefaultExpression> moveToUnfilteredContext(String name) {
        if (expression == null || expression.isIdentifier()) {
            return map(WrapperExpression::new);
        }

        var identifier = StandardIdentifierExpression.of(name);
        return new Container<>(new WrapperExpression(identifier), codeSystemDefinitions,
                Sets.append(unfilteredDefinitions, ExpressionDefinition.of(identifier, expression)),
                patientDefinitions);
    }

    /**
     * Moves the expression of this container into the patient context and returns a Container with an
     * {@link IdentifierExpression} holding {@code name}.
     * <p>
     * If this Container just holds an {@link IdentifierExpression} it's not moved.
     *
     * @param name the name of the expression definition in the Patient context
     * @return a Container with an {@link IdentifierExpression} holding {@code name}
     */
    public Container<DefaultExpression> moveToPatientContext(String name) {
        if (expression == null) {
            return map(WrapperExpression::new);
        }
        var identifier = SuffixedIdentifierExpression.of(name, suffixes().getOrDefault(name, 0));
        return new Container<>(new WrapperExpression(identifier), codeSystemDefinitions, unfilteredDefinitions,
                ExpressionDefinitions.appendByUniqueName(patientDefinitions, ExpressionDefinition.of(identifier, expression)));
    }

    /**
     * Moves the expression of this container into the patient context and returns a Container with an
     * {@link IdentifierExpression} holding {@code name}.
     * <p>
     * If this Container just holds an {@link IdentifierExpression} it's not moved.
     * <p>
     * The difference to {@link #moveToPatientContext(String)} is that the name given has to be already unique as it
     * will not get a suffix that gets incremented on collision. So the caller has to be sure that there will be no
     * expression with different content but the same name.
     *
     * @param name the name of the expression definition in the Patient context that has to be already unique
     * @return a Container with an {@link IdentifierExpression} holding {@code name}
     */
    public Container<DefaultExpression> moveToPatientContextWithUniqueName(String name) {
        if (expression == null) {
            return map(WrapperExpression::new);
        }
        var identifier = StandardIdentifierExpression.of(name);
        return new Container<>(new WrapperExpression(identifier), codeSystemDefinitions, unfilteredDefinitions,
                ExpressionDefinitions.appendByUniqueName(patientDefinitions, ExpressionDefinition.of(identifier, expression)));
    }

    /**
     * Returns {@code true} iff this container is empty.
     *
     * @return {@code true} iff this container is empty
     */
    public boolean isEmpty() {
        return expression == null;
    }

    public <U extends Expression<U>> Container<U> map(Function<? super T, ? extends U> mapper) {
        return isEmpty() ? empty() : new Container<>(requireNonNull(mapper.apply(expression)),
                codeSystemDefinitions, unfilteredDefinitions, patientDefinitions);
    }

    public <U extends Expression<U>> Container<U> flatMap(Function<? super T, Container<? extends U>> mapper) {
        if (isEmpty()) {
            return empty();
        }
        Container<? extends U> container = mapper.apply(expression);
        if (container.expression == null) {
            return empty();
        } else {
            var increments = suffixes();
            return new Container<>(container.expression.withIncrementedSuffixes(increments),
                    Sets.union(codeSystemDefinitions, container.codeSystemDefinitions),
                    Sets.union(unfilteredDefinitions, container.unfilteredDefinitions),
                    ExpressionDefinitions.unionByName(patientDefinitions, container.patientDefinitions.stream()
                            .map(d -> d.withIncrementedSuffixes(increments))
                            .toList()));
        }
    }

    public Container<T> or(Supplier<T> expressionSupplier) {
        return isEmpty() ? of(expressionSupplier.get()) : this;
    }

    private String printCodeSystemDefinitions() {
        return codeSystemDefinitions.stream()
                .sorted(Comparator.comparing(CodeSystemDefinition::name))
                .map(CodeSystemDefinition::print).collect(joining("\n")) + "\n";
    }

    private String printUnfilteredContext() {
        return getUnfilteredContext().map(Context::print).orElse("");
    }

    public String printPatientContext() {
        return getPatientContext().map(Context::print).orElse("");
    }

    public String print() {
        return Stream.of(HEADER,
                        printCodeSystemDefinitions(),
                        printUnfilteredContext(),
                        printPatientContext())
                .filter(Predicate.not(String::isBlank))
                .collect(joining("\n"));
    }
}
