package com.embabel.publisher.value;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The protocol's value rules (§2.1) in one place: the JSON form each Java value is written in, the
 * canonical string form of a key, and parsing both back.
 * <p>
 * Keys join only if both sides write them identically, so these rules are strict where a lenient
 * one would quietly break a join: a key not in canonical form is not a key of its type at all.
 */
public final class Values {

    /** Integers beyond ±2^53 are not exactly representable as a JSON number in most readers. */
    private static final BigInteger MAX_SAFE = BigInteger.valueOf(9007199254740992L);
    private static final BigInteger MIN_SAFE = MAX_SAFE.negate();
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");

    private Values() {
    }

    /** The JSON form of a Java value: what a record carries on the wire. */
    public static Object toWire(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger || value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte) {
            BigInteger integer = value instanceof BigInteger b ? b : BigInteger.valueOf(((Number) value).longValue());
            return integer.compareTo(MAX_SAFE) <= 0 && integer.compareTo(MIN_SAFE) >= 0 ? integer.longValue() : integer.toString();
        }
        if (value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof OffsetDateTime odt) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(odt);
        }
        if (value instanceof ZonedDateTime zdt) {
            return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt.toOffsetDateTime());
        }
        if (value instanceof LocalDate || value instanceof Instant || value instanceof Duration
                || value instanceof UUID || value instanceof Character) {
            return value.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Collection<?> items) {
            List<Object> list = new ArrayList<>(items.size());
            for (Object item : items) {
                list.add(toWire(item));
            }
            return list;
        }
        throw new IllegalArgumentException("No protocol form for " + value.getClass().getName());
    }

    /**
     * A wire value as the comparable the protocol's semantics are defined over: integers as
     * {@link BigInteger}, decimals as {@link BigDecimal} (so {@code "1.50"} equals {@code "1.5"}),
     * datetimes as the {@link Instant} they denote, whatever offset they were written with.
     *
     * @throws InvalidValueException when the value is not of the type
     */
    public static Comparable<?> comparable(Object wire, ValueType type) {
        if (wire == null) {
            throw new InvalidValueException("null is not a value of type " + type.wire());
        }
        try {
            return switch (type) {
                case STRING, ENUM -> requireString(wire, type);
                case BOOLEAN -> {
                    if (wire instanceof Boolean b) {
                        yield b;
                    }
                    throw new InvalidValueException("not a boolean: " + wire);
                }
                case INTEGER -> integer(wire);
                case DECIMAL -> decimal(wire);
                case DATE -> LocalDate.parse(requireString(wire, type));
                case DATETIME -> OffsetDateTime.parse(requireString(wire, type)).toInstant();
                case DURATION -> Duration.parse(requireString(wire, type));
                case LIST -> throw new InvalidValueException("a list is not comparable");
            };
        }
        catch (DateTimeParseException | ArithmeticException e) {
            throw new InvalidValueException("not a " + type.wire() + ": " + wire);
        }
    }

    /** The canonical string form of a key (§2.1). */
    public static String canonicalKey(Comparable<?> value, ValueType type) {
        if (type == ValueType.DECIMAL) {
            BigDecimal decimal = (BigDecimal) value;
            return decimal.signum() == 0 ? "0" : decimal.stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    /** The canonical key of a wire value, such as a record's lookup property. */
    public static String canonicalKeyOfWire(Object wire, ValueType type) {
        return canonicalKey(comparable(wire, type), type);
    }

    /**
     * Parses a requested key into the property's Java type. A key that is not the canonical form of
     * a value of its type is empty: the caller treats it as missing, not as an error (§2.1).
     */
    public static Optional<Object> parseKey(String key, ValueType type, Class<?> javaType) {
        try {
            Object wire = type == ValueType.BOOLEAN ? booleanKey(key) : key;
            Comparable<?> value = comparable(wire, type);
            if (!canonicalKey(value, type).equals(key)) {
                return Optional.empty();
            }
            return Optional.of(toJava(value, javaType));
        }
        catch (InvalidValueException e) {
            return Optional.empty();
        }
    }

    /** Parses a wire value, from a filter or a verb's input, into a Java type. */
    public static Object fromWire(Object wire, Class<?> javaType) {
        ValueType type = ValueType.ofScalar(javaType);
        if (type == null) {
            throw new IllegalArgumentException("No protocol type for " + javaType.getName());
        }
        return toJava(comparable(wire, type), javaType);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object toJava(Comparable<?> value, Class<?> javaType) {
        Class<?> type = Primitives.box(javaType);
        try {
            if (type == Integer.class) {
                return ((BigInteger) value).intValueExact();
            }
            if (type == Long.class) {
                return ((BigInteger) value).longValueExact();
            }
            if (type == Short.class) {
                return ((BigInteger) value).shortValueExact();
            }
            if (type == Byte.class) {
                return ((BigInteger) value).byteValueExact();
            }
        }
        catch (ArithmeticException e) {
            throw new InvalidValueException("out of range for " + type.getSimpleName() + ": " + value);
        }
        if (type == OffsetDateTime.class) {
            return ((Instant) value).atOffset(ZoneOffset.UTC);
        }
        if (type == ZonedDateTime.class) {
            return ((Instant) value).atZone(ZoneOffset.UTC);
        }
        if (type == UUID.class) {
            try {
                return UUID.fromString((String) value);
            }
            catch (IllegalArgumentException e) {
                throw new InvalidValueException("not a UUID: " + value);
            }
        }
        if (type == Character.class) {
            String s = (String) value;
            if (s.length() != 1) {
                throw new InvalidValueException("not a single character: " + s);
            }
            return s.charAt(0);
        }
        if (type.isEnum()) {
            try {
                return Enum.valueOf((Class) type, (String) value);
            }
            catch (IllegalArgumentException e) {
                throw new InvalidValueException("not one of the declared values: " + value);
            }
        }
        return value;
    }

    private static String requireString(Object wire, ValueType type) {
        if (wire instanceof String s) {
            return s;
        }
        throw new InvalidValueException("a " + type.wire() + " is written as a string: " + wire);
    }

    private static BigInteger integer(Object wire) {
        if (wire instanceof BigInteger b) {
            return b;
        }
        if (wire instanceof Long || wire instanceof Integer || wire instanceof Short || wire instanceof Byte) {
            return BigInteger.valueOf(((Number) wire).longValue());
        }
        if (wire instanceof BigDecimal d) {
            return d.toBigIntegerExact();
        }
        if (wire instanceof String s && INTEGER.matcher(s).matches()) {
            return new BigInteger(s);
        }
        throw new InvalidValueException("not an integer: " + wire);
    }

    /* A decimal is a string, never a JSON number (§2.1): a number has already been through somebody's floating point. */
    private static BigDecimal decimal(Object wire) {
        if (wire instanceof String s && DECIMAL.matcher(s).matches()) {
            return new BigDecimal(s);
        }
        throw new InvalidValueException("a decimal is a string in plain notation: " + wire);
    }

    private static Object booleanKey(String key) {
        return switch (key) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> key;
        };
    }
}
