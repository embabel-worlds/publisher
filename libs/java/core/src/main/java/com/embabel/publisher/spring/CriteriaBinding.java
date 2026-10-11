package com.embabel.publisher.spring;

import com.embabel.publisher.annotation.RealmQuery;
import com.embabel.publisher.realm.PropertyModel;
import com.embabel.publisher.realm.Realm;
import com.embabel.publisher.realm.RealmDefinitionException;
import com.embabel.publisher.realm.TypeModel;
import com.embabel.publisher.value.InvalidValueException;
import com.embabel.publisher.value.Primitives;
import com.embabel.publisher.value.Values;
import com.embabel.publisher.where.Op;
import com.embabel.publisher.where.Where;
import org.springframework.core.ResolvableType;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A criteria record: the application's own search parameters, read as the filters a world may push
 * down. Each component is one (property, operator) pair, and the manifest declares exactly those.
 * A pushed conjunction fills the record; a component nothing was pushed for stays {@code null}.
 */
final class CriteriaBinding {

    static final String RANGE = "org.springframework.data.domain.Range";

    private record Criterion(int index, String property, Op op, Class<?> valueType) {
    }

    /** The record to pass, and whether it carries the whole {@code where}. */
    record Bound(Object criteria, boolean complete, boolean empty) {
    }

    private final Class<?> record;
    private final Constructor<?> constructor;
    private final List<Criterion> criteria = new ArrayList<>();
    private final Map<String, Set<Op>> declared = new LinkedHashMap<>();

    CriteriaBinding(Class<?> record, TypeModel type, String source) {
        this.record = record;
        RecordComponent[] components = record.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            String where = source + ": criteria " + record.getSimpleName() + "." + component.getName();
            RealmQuery.Criterion annotation = component.getAnnotation(RealmQuery.Criterion.class);
            String name = annotation == null || annotation.property().isEmpty() ? component.getName() : annotation.property();
            PropertyModel property = type.property(name);
            if (property == null) {
                throw new RealmDefinitionException(where + " names " + name + ", which " + type.name()
                        + " does not expose; name an exposed property with @RealmQuery.Criterion(property = ...)");
            }
            Class<?> javaType = component.getType();
            if (javaType.isPrimitive()) {
                throw new RealmDefinitionException(where + " is primitive; it must be able to be null, which means not pushed");
            }
            Op op;
            Class<?> valueType;
            if (Collection.class.isAssignableFrom(javaType)) {
                op = Op.IN;
                valueType = ResolvableType.forType(component.getGenericType()).asCollection().resolveGeneric(0);
            }
            else if (javaType.getName().equals(RANGE)) {
                op = Op.BETWEEN;
                valueType = ResolvableType.forType(component.getGenericType()).resolveGeneric(0);
            }
            else {
                op = Op.EQ;
                valueType = javaType;
            }
            if (annotation != null && annotation.op().length > 0) {
                if (annotation.op().length > 1) {
                    throw new RealmDefinitionException(where + ": one component carries one operator");
                }
                Op named = annotation.op()[0];
                boolean shapeFits = switch (named) {
                    case IN -> op == Op.IN;
                    case BETWEEN -> op == Op.BETWEEN;
                    case IS_NULL, IS_NOT_NULL -> Primitives.box(javaType) == Boolean.class;
                    default -> op == Op.EQ;
                };
                if (!shapeFits) {
                    throw new RealmDefinitionException(where + ": " + named.wire() + " takes "
                            + switch (named) {
                                case IN -> "a Collection";
                                case BETWEEN -> "a Range";
                                case IS_NULL, IS_NOT_NULL -> "a Boolean, true to apply it";
                                default -> "a single value";
                            });
                }
                op = named;
            }
            if (op != Op.IS_NULL && op != Op.IS_NOT_NULL && (valueType == null || Primitives.box(valueType) != Primitives.box(property.javaType()))) {
                throw new RealmDefinitionException(where + ": " + property.name() + " is a " + property.javaType().getSimpleName()
                        + ", so the component carries " + property.javaType().getSimpleName() + (op == Op.EQ || !op.takesArray() ? "" : " values"));
            }
            if (!op.appliesTo(property.type())) {
                throw new RealmDefinitionException(where + ": the protocol does not define " + op.wire() + " on a " + property.type().wire());
            }
            if (!Realm.SUPPORTED_OPS.contains(op)) {
                throw new RealmDefinitionException(where + ": " + op.wire() + " is not supported by this version of the library");
            }
            if (!declared.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(op)) {
                throw new RealmDefinitionException(where + ": " + name + " " + op.wire() + " is carried by two components");
            }
            criteria.add(new Criterion(i, name, op, valueType));
        }
        try {
            this.constructor = record.getDeclaredConstructor(
                    java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            this.constructor.setAccessible(true);
        }
        catch (NoSuchMethodException e) {
            throw new RealmDefinitionException(source + ": " + record.getName() + " has no canonical constructor");
        }
    }

    static boolean isCriteria(Class<?> type) {
        return type.isRecord();
    }

    /** The (property, operators) pairs the record carries, for the manifest. */
    Map<String, Set<Op>> declared() {
        return declared;
    }

    /**
     * Fills the record from a conjunction. A comparison the record cannot hold (a second value for
     * the same pair) leaves the call incomplete, never wrong: what the record carries is a
     * conjunct of the filter, so it only ever narrows to a superset of the answer. {@code empty}
     * means no record can match, such as {@code eq null}, so the method need not be asked.
     */
    Bound bind(Where where) {
        Object[] args = new Object[criteria.size()];
        boolean complete = true;
        boolean empty = false;
        for (Where.Comparison c : conjuncts(where)) {
            Criterion criterion = find(c.property(), c.op());
            if (criterion == null || args[criterion.index()] != null) {
                complete = false;
                continue;
            }
            Object value = value(c, criterion);
            if (value == NOTHING) {
                empty = true;
                continue;
            }
            args[criterion.index()] = value;
        }
        if (empty) {
            return new Bound(null, true, true);
        }
        try {
            return new Bound(constructor.newInstance(args), complete, false);
        }
        catch (InvocationTargetException e) {
            throw new IllegalStateException("Constructing " + record.getName() + " failed", e.getCause());
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Object NOTHING = new Object();

    /* The operand in the component's Java type, or NOTHING when no record could match it. */
    private static Object value(Where.Comparison c, Criterion criterion) {
        switch (c.op()) {
            case IS_NULL, IS_NOT_NULL -> {
                return Boolean.TRUE;
            }
            case IN -> {
                List<Object> values = new ArrayList<>();
                for (Object v : (List<?>) c.value()) {
                    Object java = representable(v, criterion.valueType());
                    if (java != null) {
                        values.add(java);
                    }
                }
                return values.isEmpty() ? NOTHING : List.copyOf(values);
            }
            case BETWEEN -> {
                List<?> bounds = (List<?>) c.value();
                Object low = representable(bounds.get(0), criterion.valueType());
                Object high = representable(bounds.get(1), criterion.valueType());
                return low == null || high == null ? NOTHING : DataSupport.closedRange(low, high);
            }
            default -> {
                Object java = representable(c.value(), criterion.valueType());
                return java == null ? NOTHING : java;
            }
        }
    }

    private static Object representable(Object wire, Class<?> type) {
        if (wire == null) {
            return null;
        }
        try {
            return Values.fromWire(wire, type);
        }
        catch (InvalidValueException e) {
            return null;
        }
    }

    private Criterion find(String property, Op op) {
        for (Criterion criterion : criteria) {
            if (criterion.property().equals(property) && criterion.op() == op) {
                return criterion;
            }
        }
        return null;
    }

    private static List<Where.Comparison> conjuncts(Where where) {
        List<Where.Comparison> out = new ArrayList<>();
        if (where instanceof Where.And and) {
            for (Where operand : and.operands()) {
                out.addAll(conjuncts(operand));
            }
        }
        else if (where instanceof Where.Comparison c) {
            out.add(c);
        }
        else if (where != null) {
            throw new IllegalArgumentException("Only conjunctions of comparisons reach a criteria record: " + where);
        }
        return out;
    }
}
