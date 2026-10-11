package com.embabel.publisher.where;

import com.embabel.publisher.protocol.ErrorCode;
import com.embabel.publisher.protocol.RealmException;
import com.embabel.publisher.value.ValueType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceEvaluatorTest {

    private static final Map<String, ValueType> TYPES = Map.of(
            "name", ValueType.STRING, "amount", ValueType.DECIMAL, "count", ValueType.INTEGER,
            "due", ValueType.DATE, "at", ValueType.DATETIME, "tags", ValueType.LIST);

    private final ReferenceEvaluator evaluator = new ReferenceEvaluator(TYPES::get, p -> ValueType.STRING);

    private static Map<String, Object> record(Object... kv) {
        Map<String, Object> r = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    private static Where cmp(String property, String op, Object value) {
        Map<String, Object> c = new HashMap<>();
        c.put("property", property);
        c.put("op", op);
        c.put("value", value);
        return WhereParser.parse(c);
    }

    private static Where nullCheck(String property, String op) {
        return WhereParser.parse(Map.of("property", property, "op", op));
    }

    @Test
    void aNullSatisfiesOnlyIsNullUnderEveryOperatorIncludingNegations() {
        Map<String, Object> absent = record();
        Map<String, Object> explicitNull = record("name", null, "amount", null);
        for (Map<String, Object> r : List.of(absent, explicitNull)) {
            assertThat(evaluator.test(nullCheck("name", "isNull"), r)).isTrue();
            assertThat(evaluator.test(nullCheck("name", "isNotNull"), r)).isFalse();
            assertThat(evaluator.test(cmp("name", "eq", "x"), r)).isFalse();
            assertThat(evaluator.test(cmp("name", "ne", "x"), r)).isFalse();
            assertThat(evaluator.test(cmp("name", "notIn", List.of("x")), r)).isFalse();
            assertThat(evaluator.test(cmp("name", "prefix", ""), r)).isFalse();
            assertThat(evaluator.test(cmp("amount", "lt", "1"), r)).isFalse();
            assertThat(evaluator.test(cmp("amount", "gte", "1"), r)).isFalse();
            assertThat(evaluator.test(WhereParser.parse(Map.of("not", Map.of("property", "name", "op", "eq", "value", "x"))), r))
                    .as("not over unknown is unknown, so not admitted").isFalse();
        }
    }

    @Test
    void aNullOperandMatchesNothing() {
        assertThat(evaluator.test(cmp("name", "eq", null), record("name", "x"))).isFalse();
        assertThat(evaluator.test(cmp("name", "in", Arrays.asList("x", null)), record("name", "x"))).isTrue();
        assertThat(evaluator.test(cmp("name", "in", Arrays.asList((Object) null)), record("name", "x"))).isFalse();
    }

    @Test
    void decimalsCompareByValueNotByString() {
        assertThat(evaluator.test(cmp("amount", "eq", "1.50"), record("amount", "1.5"))).isTrue();
        assertThat(evaluator.test(cmp("amount", "gt", "9"), record("amount", "10.00"))).isTrue();
        assertThat(evaluator.test(cmp("amount", "between", List.of("1", "2")), record("amount", "2.00"))).isTrue();
        assertThat(evaluator.test(cmp("amount", "between", List.of("1", "2")), record("amount", "2.01"))).isFalse();
    }

    @Test
    void integersInEitherJsonFormCompareAlike() {
        assertThat(evaluator.test(cmp("count", "eq", "9007199254740993"), record("count", "9007199254740993"))).isTrue();
        assertThat(evaluator.test(cmp("count", "in", List.of("6", 7L)), record("count", 7L))).isTrue();
        assertThat(evaluator.test(cmp("count", "lte", 5L), record("count", 6L))).isFalse();
    }

    @Test
    void datetimesCompareAsInstantsWhateverTheirOffset() {
        assertThat(evaluator.test(cmp("at", "eq", "2026-10-10T09:30:00Z"), record("at", "2026-10-10T11:30:00+02:00"))).isTrue();
        assertThat(evaluator.test(cmp("due", "lt", "2026-10-01"), record("due", "2026-09-30"))).isTrue();
    }

    @Test
    void textOperators() {
        Map<String, Object> r = record("name", "Müller");
        assertThat(evaluator.test(cmp("name", "prefix", "Mü"), r)).isTrue();
        assertThat(evaluator.test(cmp("name", "prefix", "mü"), r)).isFalse();
        assertThat(evaluator.test(cmp("name", "iprefix", "MÜ"), r)).isTrue();
        assertThat(evaluator.test(cmp("name", "ieq", "MÜLLER"), r)).isTrue();
        assertThat(evaluator.test(cmp("name", "contains", "ll"), r)).isTrue();
        assertThat(evaluator.test(cmp("name", "prefix", "M%"), r)).as("no pattern characters").isFalse();
    }

    @Test
    void lists() {
        Map<String, Object> r = record("tags", List.of("a", "b"));
        assertThat(evaluator.test(cmp("tags", "has", "a"), r)).isTrue();
        assertThat(evaluator.test(cmp("tags", "hasAny", List.of("x", "b")), r)).isTrue();
        assertThat(evaluator.test(cmp("tags", "has", "z"), r)).isFalse();
    }

    @Test
    void connectivesAreThreeValued() {
        Map<String, Object> r = record("name", "x");
        Where orWithUnknown = WhereParser.parse(Map.of("or", List.of(
                Map.of("property", "amount", "op", "gt", "value", "1"),
                Map.of("property", "name", "op", "eq", "value", "x"))));
        assertThat(evaluator.test(orWithUnknown, r)).isTrue();
        Where andWithUnknown = WhereParser.parse(Map.of("and", List.of(
                Map.of("property", "amount", "op", "gt", "value", "1"),
                Map.of("property", "name", "op", "eq", "value", "x"))));
        assertThat(evaluator.test(andWithUnknown, r)).isFalse();
        assertThat(evaluator.test(new Where.Not(andWithUnknown), r)).as("not unknown is unknown").isFalse();
    }

    @Test
    void theParserEnforcesTheLimitsASchemaCannotCount() {
        Object deep = Map.of("property", "name", "op", "isNull");
        for (int i = 0; i < 8; i++) {
            deep = Map.of("not", deep);
        }
        Object tooDeep = deep;
        assertThatThrownBy(() -> WhereParser.parse(tooDeep)).isInstanceOfSatisfying(RealmException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPRESSION_BOUND));
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            many.add(Map.of("property", "name", "op", "isNull"));
        }
        assertThatThrownBy(() -> WhereParser.parse(Map.of("and", many))).isInstanceOfSatisfying(RealmException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPRESSION_BOUND));
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            values.add("v" + i);
        }
        assertThatThrownBy(() -> cmp("name", "in", values)).isInstanceOfSatisfying(RealmException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.EXPRESSION_BOUND));
        assertThatThrownBy(() -> WhereParser.parse(Map.of("property", "name", "op", "isNull", "value", "x")))
                .isInstanceOfSatisfying(RealmException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> cmp("name", "matches", "x")).isInstanceOfSatisfying(RealmException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.UNSUPPORTED));
    }
}
