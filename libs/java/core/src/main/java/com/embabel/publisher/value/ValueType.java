package com.embabel.publisher.value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;

/** The protocol's property types (§2.1), and the Java types each is written from. */
public enum ValueType {
    STRING("string"),
    BOOLEAN("boolean"),
    INTEGER("integer"),
    DECIMAL("decimal"),
    DATE("date"),
    DATETIME("datetime"),
    DURATION("duration"),
    ENUM("enum"),
    LIST("list");

    private static final Set<Class<?>> INTEGERS = Set.of(Integer.class, Long.class, Short.class, Byte.class, BigInteger.class);

    private final String wire;

    ValueType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean isScalar() {
        return this != LIST;
    }

    /** Range operators are defined on these, never on strings (§4.1: collations differ). */
    public boolean isOrdered() {
        return this == INTEGER || this == DECIMAL || this == DATE || this == DATETIME || this == DURATION;
    }

    /**
     * The scalar type a Java type is written as, or {@code null} when the protocol has none for it.
     * {@code double}, {@code float} and {@code LocalDateTime} have none on purpose: the protocol
     * has no floating point, and a datetime without an offset is not an instant.
     */
    public static ValueType ofScalar(Class<?> javaType) {
        Class<?> type = Primitives.box(javaType);
        if (type == String.class || type == Character.class || type == UUID.class) {
            return STRING;
        }
        if (type == Boolean.class) {
            return BOOLEAN;
        }
        if (INTEGERS.contains(type)) {
            return INTEGER;
        }
        if (type == BigDecimal.class) {
            return DECIMAL;
        }
        if (type == LocalDate.class) {
            return DATE;
        }
        if (type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class) {
            return DATETIME;
        }
        if (type == Duration.class) {
            return DURATION;
        }
        if (type.isEnum()) {
            return ENUM;
        }
        return null;
    }
}
