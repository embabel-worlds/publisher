package com.embabel.publisher.where;

import com.embabel.publisher.value.CaseFolding;
import com.embabel.publisher.value.ValueType;
import com.embabel.publisher.value.Values;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The protocol's expression semantics (§4.1), executable, over records in their wire form.
 * <p>
 * It is the definition a pushed-down translation is tested against, and the evaluator the library
 * itself uses where a method cannot take a filter. Logic is three-valued, as in SQL and Cypher: a
 * comparison with a null or absent property is <em>unknown</em>, which {@code not} leaves unknown,
 * and a record is admitted only when its expression is true. So a null satisfies {@code isNull}
 * and nothing else, under {@code not} included.
 */
public final class ReferenceEvaluator {

    private final Function<String, ValueType> types;
    private final Function<String, ValueType> itemTypes;

    /**
     * @param types     the declared type of each property path
     * @param itemTypes the element type of each {@code list} property
     */
    public ReferenceEvaluator(Function<String, ValueType> types, Function<String, ValueType> itemTypes) {
        this.types = types;
        this.itemTypes = itemTypes;
    }

    /** Whether the record satisfies the expression. A {@code null} expression admits everything. */
    public boolean test(Where where, Map<String, Object> record) {
        return where == null || eval(where, record) == Truth.TRUE;
    }

    private enum Truth {
        TRUE, FALSE, UNKNOWN;

        static Truth of(boolean b) {
            return b ? TRUE : FALSE;
        }
    }

    private Truth eval(Where where, Map<String, Object> record) {
        if (where instanceof Where.And and) {
            Truth result = Truth.TRUE;
            for (Where operand : and.operands()) {
                Truth t = eval(operand, record);
                if (t == Truth.FALSE) {
                    return Truth.FALSE;
                }
                if (t == Truth.UNKNOWN) {
                    result = Truth.UNKNOWN;
                }
            }
            return result;
        }
        if (where instanceof Where.Or or) {
            Truth result = Truth.FALSE;
            for (Where operand : or.operands()) {
                Truth t = eval(operand, record);
                if (t == Truth.TRUE) {
                    return Truth.TRUE;
                }
                if (t == Truth.UNKNOWN) {
                    result = Truth.UNKNOWN;
                }
            }
            return result;
        }
        if (where instanceof Where.Not not) {
            return switch (eval(not.operand(), record)) {
                case TRUE -> Truth.FALSE;
                case FALSE -> Truth.TRUE;
                case UNKNOWN -> Truth.UNKNOWN;
            };
        }
        if (where instanceof Where.Comparison c) {
            return compare(c, read(record, c.property()));
        }
        throw new UnsupportedOperationException("exists is evaluated against related records, which a single record does not carry");
    }

    @SuppressWarnings("unchecked")
    private static Object read(Map<String, Object> record, String path) {
        Object current = record;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(segment);
        }
        return current;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Truth compare(Where.Comparison c, Object actual) {
        if (c.op() == Op.IS_NULL) {
            return Truth.of(actual == null);
        }
        if (c.op() == Op.IS_NOT_NULL) {
            return Truth.of(actual != null);
        }
        if (actual == null) {
            return Truth.UNKNOWN;
        }
        ValueType type = types.apply(c.property());
        if (type == ValueType.LIST) {
            return list(c, (List<Object>) actual);
        }
        Comparable value = Values.comparable(actual, type);
        return switch (c.op()) {
            case EQ -> equal(value, c.value(), type);
            case NE -> not(equal(value, c.value(), type));
            case IN -> in(value, (List<Object>) c.value(), type);
            case NOT_IN -> not(in(value, (List<Object>) c.value(), type));
            case GT -> order(value, c.value(), type, cmp -> cmp > 0);
            case GTE -> order(value, c.value(), type, cmp -> cmp >= 0);
            case LT -> order(value, c.value(), type, cmp -> cmp < 0);
            case LTE -> order(value, c.value(), type, cmp -> cmp <= 0);
            case BETWEEN -> {
                List<Object> bounds = (List<Object>) c.value();
                yield and(order(value, bounds.get(0), type, cmp -> cmp >= 0), order(value, bounds.get(1), type, cmp -> cmp <= 0));
            }
            case PREFIX -> text(c.value(), operand -> ((String) value).startsWith(operand));
            case CONTAINS -> text(c.value(), operand -> ((String) value).contains(operand));
            case IEQ -> text(c.value(), operand -> CaseFolding.fold((String) value).equals(CaseFolding.fold(operand)));
            case IPREFIX -> text(c.value(), operand -> CaseFolding.fold((String) value).startsWith(CaseFolding.fold(operand)));
            case ICONTAINS -> text(c.value(), operand -> CaseFolding.fold((String) value).contains(CaseFolding.fold(operand)));
            default -> throw new IllegalArgumentException(c.op().wire() + " does not apply to " + type.wire());
        };
    }

    /** A null operand is never satisfied: {@code eq null} is unknown, as {@code = NULL} is in SQL. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Truth equal(Comparable value, Object operand, ValueType type) {
        if (operand == null) {
            return Truth.UNKNOWN;
        }
        return Truth.of(value.compareTo(Values.comparable(operand, type)) == 0);
    }

    private static Truth in(Comparable<?> value, List<Object> operands, ValueType type) {
        Truth result = Truth.FALSE;
        for (Object operand : operands) {
            Truth t = equal(value, operand, type);
            if (t == Truth.TRUE) {
                return Truth.TRUE;
            }
            if (t == Truth.UNKNOWN) {
                result = Truth.UNKNOWN;
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Truth order(Comparable value, Object operand, ValueType type, java.util.function.IntPredicate test) {
        if (operand == null) {
            return Truth.UNKNOWN;
        }
        return Truth.of(test.test(value.compareTo(Values.comparable(operand, type))));
    }

    private static Truth text(Object operand, java.util.function.Predicate<String> test) {
        if (operand == null) {
            return Truth.UNKNOWN;
        }
        return Truth.of(test.test((String) operand));
    }

    @SuppressWarnings("unchecked")
    private Truth list(Where.Comparison c, List<Object> actual) {
        ValueType item = itemTypes.apply(c.property());
        List<Object> wanted = switch (c.op()) {
            case HAS -> java.util.Collections.singletonList(c.value());
            case HAS_ANY -> (List<Object>) c.value();
            default -> throw new IllegalArgumentException(c.op().wire() + " does not apply to a list");
        };
        Truth result = Truth.FALSE;
        for (Object element : actual) {
            if (element == null) {
                continue;
            }
            Truth t = in(Values.comparable(element, item), wanted, item);
            if (t == Truth.TRUE) {
                return Truth.TRUE;
            }
            if (t == Truth.UNKNOWN) {
                result = Truth.UNKNOWN;
            }
        }
        return result;
    }

    private static Truth not(Truth t) {
        return t == Truth.UNKNOWN ? t : Truth.of(t == Truth.FALSE);
    }

    private static Truth and(Truth a, Truth b) {
        if (a == Truth.FALSE || b == Truth.FALSE) {
            return Truth.FALSE;
        }
        return a == Truth.TRUE && b == Truth.TRUE ? Truth.TRUE : Truth.UNKNOWN;
    }
}
