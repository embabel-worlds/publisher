package com.embabel.publisher.where;

import com.embabel.publisher.protocol.ErrorCode;
import com.embabel.publisher.protocol.RealmException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code where} from its JSON tree, enforcing the limits of §4.5 a schema cannot count:
 * depth 8, 256 nodes, 1000 values in one {@code in}, {@code notIn} or {@code hasAny}.
 */
public final class WhereParser {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_NODES = 256;
    public static final int MAX_VALUES = 1000;

    private int nodes;

    private WhereParser() {
    }

    /** Parses a {@code where}, or returns {@code null} for none. */
    public static Where parse(Object tree) {
        return tree == null ? null : new WhereParser().node(tree, 1);
    }

    private Where node(Object tree, int depth) {
        if (depth > MAX_DEPTH) {
            throw new RealmException(ErrorCode.EXPRESSION_BOUND, "deeper than " + MAX_DEPTH);
        }
        if (++nodes > MAX_NODES) {
            throw new RealmException(ErrorCode.EXPRESSION_BOUND, "more than " + MAX_NODES + " nodes");
        }
        if (!(tree instanceof Map<?, ?> map)) {
            throw RealmException.invalid("an expression is an object");
        }
        if (map.containsKey("and")) {
            return new Where.And(operands(map.get("and"), depth));
        }
        if (map.containsKey("or")) {
            return new Where.Or(operands(map.get("or"), depth));
        }
        if (map.containsKey("not")) {
            return new Where.Not(node(map.get("not"), depth + 1));
        }
        if (map.containsKey("exists")) {
            if (!(map.get("exists") instanceof Map<?, ?> exists)) {
                throw RealmException.invalid("exists is an object");
            }
            Object inner = exists.get("where");
            return new Where.Exists((String) exists.get("type"), (String) exists.get("via"), (String) exists.get("part"),
                    inner == null ? null : node(inner, depth + 1));
        }
        return comparison(map);
    }

    private List<Where> operands(Object list, int depth) {
        if (!(list instanceof List<?> items) || items.isEmpty()) {
            throw RealmException.invalid("a connective takes a non-empty array");
        }
        List<Where> operands = new ArrayList<>(items.size());
        for (Object item : items) {
            operands.add(node(item, depth + 1));
        }
        return operands;
    }

    private Where comparison(Map<?, ?> map) {
        if (!(map.get("property") instanceof String property) || property.isEmpty()) {
            throw RealmException.invalid("a comparison names a property");
        }
        if (!(map.get("op") instanceof String opName)) {
            throw RealmException.invalid("a comparison names an op");
        }
        Op op = Op.fromWire(opName);
        if (op == null) {
            throw RealmException.unsupported("unknown operator " + opName);
        }
        Object value = map.get("value");
        if (op.takesValue() != map.containsKey("value")) {
            throw RealmException.invalid(op.takesValue() ? opName + " takes a value" : opName + " takes no value");
        }
        if (op.takesArray()) {
            if (!(value instanceof List<?> values)) {
                throw RealmException.invalid(opName + " takes an array");
            }
            if (values.size() > MAX_VALUES) {
                throw new RealmException(ErrorCode.EXPRESSION_BOUND, "more than " + MAX_VALUES + " values in " + opName);
            }
            if (op == Op.BETWEEN && values.size() != 2) {
                throw RealmException.invalid("between takes [low, high]");
            }
        }
        return new Where.Comparison(property, op, value);
    }
}
