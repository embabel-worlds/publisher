package com.embabel.publisher.spring.jpa;

import com.embabel.publisher.realm.PropertyModel;
import com.embabel.publisher.realm.TypeModel;
import com.embabel.publisher.value.CaseFolding;
import com.embabel.publisher.value.InvalidValueException;
import com.embabel.publisher.value.Values;
import com.embabel.publisher.where.Where;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates the protocol's filter tree into a JPA {@code Specification} on the type's entity, with
 * the protocol's semantics rather than the store's where they differ.
 * <ul>
 *   <li>Nulls: a comparison never admits a null (SQL's rule, which is the protocol's), and a null
 *       operand admits nothing.</li>
 *   <li>Paths through associations join <em>left</em>. An implicit inner join would silently drop
 *       the rows whose association is null, and {@code isNull} on them could never be true.</li>
 *   <li>{@code prefix} escapes {@code %}, {@code _} and the escape character, so a value is matched
 *       as text, never as a pattern.</li>
 *   <li>{@code ieq} and {@code iprefix} compare the store's {@code lower()} with the operand's
 *       simple case fold. They agree for every character whose fold is its lower case, which is all
 *       but a few (final sigma, for one).</li>
 * </ul>
 * Only conjunctions reach it: the publisher refuses anything else before a method is called.
 */
public final class SpecificationTranslator {

    private SpecificationTranslator() {
    }

    /** A {@code Specification} for the filter. With no filter, one that matches everything, never {@code null}. */
    public static <E> Specification<E> translate(Where where, TypeModel type) {
        return translate(where, type, List.of());
    }

    /**
     * As {@link #translate(Where, TypeModel)}, also ordering the query by {@code order}, nulls as
     * the greatest value (see {@link JpaOrdering#SPECIFICATION}).
     */
    public static <E> Specification<E> translate(Where where, TypeModel type, List<OrderKey> order) {
        return (root, query, cb) -> {
            Translation translation = new Translation(root, cb, type);
            if (!order.isEmpty() && query != null) {
                List<Order> orders = new ArrayList<>();
                for (OrderKey key : order) {
                    Path<?> path = translation.path(key.entityPath());
                    if (key.nullable()) {
                        Expression<Integer> isNull = cb.<Integer>selectCase().when(cb.isNull(path), 1).otherwise(0);
                        orders.add(key.descending() ? cb.desc(isNull) : cb.asc(isNull));
                    }
                    orders.add(key.descending() ? cb.desc(path) : cb.asc(path));
                }
                query.orderBy(orders);
            }
            return where == null ? cb.conjunction() : translation.predicate(where);
        };
    }

    /**
     * One key of an order, on the entity.
     *
     * @param nullable whether the attribute can be null; a key that cannot needs no null placement
     */
    public record OrderKey(String entityPath, boolean descending, boolean nullable) {
    }

    private static final class Translation {

        private final Root<?> root;
        private final CriteriaBuilder cb;
        private final TypeModel type;
        private final Map<String, From<?, ?>> joins = new HashMap<>();

        Translation(Root<?> root, CriteriaBuilder cb, TypeModel type) {
            this.root = root;
            this.cb = cb;
            this.type = type;
        }

        Predicate predicate(Where where) {
            if (where instanceof Where.And and) {
                List<Predicate> operands = new ArrayList<>();
                for (Where operand : and.operands()) {
                    operands.add(predicate(operand));
                }
                return cb.and(operands.toArray(Predicate[]::new));
            }
            if (where instanceof Where.Comparison c) {
                return comparison(c);
            }
            throw new IllegalArgumentException("Only conjunctions of comparisons are translated: " + where);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Predicate comparison(Where.Comparison c) {
            PropertyModel property = type.property(c.property());
            Path path = path(property.entityPath());
            return switch (c.op()) {
                case IS_NULL -> cb.isNull(path);
                case IS_NOT_NULL -> cb.isNotNull(path);
                case EQ -> {
                    Object value = c.value() == null ? null : representable(c.value(), property);
                    yield value == null ? cb.disjunction() : cb.equal(path, value);
                }
                case IN -> {
                    List<Object> values = new ArrayList<>();
                    for (Object v : (List<?>) c.value()) {
                        Object value = v == null ? null : representable(v, property);
                        if (value != null) {
                            values.add(value);
                        }
                    }
                    yield values.isEmpty() ? cb.disjunction() : path.in(values);
                }
                case GT -> c.value() == null ? cb.disjunction() : cb.greaterThan((Expression<Comparable>) path, (Comparable) value(c.value(), property));
                case GTE -> c.value() == null ? cb.disjunction() : cb.greaterThanOrEqualTo((Expression<Comparable>) path, (Comparable) value(c.value(), property));
                case LT -> c.value() == null ? cb.disjunction() : cb.lessThan((Expression<Comparable>) path, (Comparable) value(c.value(), property));
                case LTE -> c.value() == null ? cb.disjunction() : cb.lessThanOrEqualTo((Expression<Comparable>) path, (Comparable) value(c.value(), property));
                case BETWEEN -> {
                    List<?> bounds = (List<?>) c.value();
                    yield bounds.get(0) == null || bounds.get(1) == null ? cb.disjunction()
                            : cb.between((Expression<Comparable>) path, (Comparable) value(bounds.get(0), property), (Comparable) value(bounds.get(1), property));
                }
                case PREFIX -> c.value() == null ? cb.disjunction() : cb.like(path, escape((String) c.value()) + "%", '\\');
                case IEQ -> c.value() == null ? cb.disjunction() : cb.equal(cb.lower(path), CaseFolding.fold((String) c.value()));
                case IPREFIX -> c.value() == null ? cb.disjunction()
                        : cb.like(cb.lower(path), escape(CaseFolding.fold((String) c.value())) + "%", '\\');
                default -> throw new IllegalArgumentException(c.op().wire() + " is not translated");
            };
        }

        Path<?> path(String entityPath) {
            String[] segments = entityPath.split("\\.");
            From<?, ?> from = root;
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                prefix.append(prefix.length() == 0 ? "" : ".").append(segments[i]);
                From<?, ?> parent = from;
                String segment = segments[i];
                from = joins.computeIfAbsent(prefix.toString(), k -> parent.join(segment, JoinType.LEFT));
            }
            return from.get(segments[segments.length - 1]);
        }

        /**
         * The operand in the property's Java type, or {@code null} when no value of that type could
         * equal it (an enum constant the type does not have, an integer beyond its range): such an
         * operand matches nothing, as the reference evaluator also finds.
         */
        private static Object representable(Object wire, PropertyModel property) {
            try {
                return value(wire, property);
            }
            catch (InvalidValueException e) {
                return null;
            }
        }

        private static Object value(Object wire, PropertyModel property) {
            return Values.fromWire(wire, property.javaType());
        }

        private static String escape(String s) {
            return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        }
    }
}
