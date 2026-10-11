package com.embabel.publisher.where;

import com.embabel.publisher.value.ValueType;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The comparison operators of the protocol's expression grammar (§4.1). */
public enum Op {
    EQ("eq"), NE("ne"),
    IN("in"), NOT_IN("notIn"),
    GT("gt"), GTE("gte"), LT("lt"), LTE("lte"),
    BETWEEN("between"),
    IS_NULL("isNull"), IS_NOT_NULL("isNotNull"),
    PREFIX("prefix"), CONTAINS("contains"),
    IEQ("ieq"), IPREFIX("iprefix"), ICONTAINS("icontains"),
    HAS("has"), HAS_ANY("hasAny");

    private static final Map<String, Op> BY_WIRE = Arrays.stream(values()).collect(Collectors.toMap(Op::wire, Function.identity()));

    private final String wire;

    Op(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** The operator with this wire name, or {@code null}. */
    public static Op fromWire(String wire) {
        return BY_WIRE.get(wire);
    }

    public boolean takesValue() {
        return this != IS_NULL && this != IS_NOT_NULL;
    }

    /** {@code in}, {@code notIn}, {@code hasAny} and {@code between} take an array. */
    public boolean takesArray() {
        return this == IN || this == NOT_IN || this == HAS_ANY || this == BETWEEN;
    }

    /** Whether the protocol defines this operator on a property of this type (§4.1's table). */
    public boolean appliesTo(ValueType type) {
        return switch (this) {
            case EQ, NE, IN, NOT_IN -> type.isScalar();
            case GT, GTE, LT, LTE, BETWEEN -> type.isOrdered();
            case IS_NULL, IS_NOT_NULL -> true;
            case PREFIX, CONTAINS, IEQ, IPREFIX, ICONTAINS -> type == ValueType.STRING;
            case HAS, HAS_ANY -> type == ValueType.LIST;
        };
    }
}
