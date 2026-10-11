package com.embabel.publisher.it;

import com.embabel.publisher.it.app.TestApplication;
import com.embabel.publisher.value.ValueType;
import com.embabel.publisher.where.ReferenceEvaluator;
import com.embabel.publisher.where.WhereParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.embabel.publisher.it.ProtocolSchema.assertValid;
import static com.embabel.publisher.it.RealmClient.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exactness check of protocol §11, in miniature: for every declared property and operator, with
 * operands drawn from the data itself (nulls, pattern characters and case variants included), the
 * records the publisher returns for a pushed filter are exactly those the reference evaluator admits
 * from the unfiltered records. A translator that drops a row, or admits one, fails here.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushdownExactnessIT {

    @LocalServerPort
    int port;

    RealmClient realm;
    JsonNode manifest;

    @BeforeEach
    void client() {
        realm = new RealmClient(port, "test-token");
        manifest = realm.get("").json();
    }

    @Test
    void everyDeclaredOperatorOnAQueryIsExact() {
        JsonNode type = PublisherIT.type(manifest, "Customer");
        List<Map<String, Object>> all = queryAll(null);
        assertThat(all).hasSize(10);
        int checked = 0;
        for (Map<String, Object> where : comparisons(type, all)) {
            Set<String> expected = admitted(type, where, all);
            Set<String> pushed = new TreeSet<>();
            queryAll(where).forEach(r -> pushed.add(String.valueOf(r.get("id"))));
            assertThat(pushed).as("pushed %s", where).isEqualTo(expected);
            checked++;
        }
        assertThat(checked).isGreaterThan(100);
    }

    @Test
    void aConjunctionIsExact() {
        JsonNode type = PublisherIT.type(manifest, "Customer");
        List<Map<String, Object>> all = queryAll(null);
        Map<String, Object> where = map("and", List.of(
                map("property", "city", "op", "in", "value", Arrays.asList("Madison", "Berlin", null)),
                map("property", "balance", "op", "between", "value", List.of("0", "1200.5")),
                map("property", "region", "op", "isNotNull")));
        Set<String> pushed = new TreeSet<>();
        queryAll(where).forEach(r -> pushed.add(String.valueOf(r.get("id"))));
        assertThat(pushed).isEqualTo(admitted(type, where, all)).containsExactly("1", "10", "2", "5");
    }

    @Test
    void everyDeclaredOperatorOnALookupIsExactWhetherPushedOrEvaluatedByThePublisher() {
        JsonNode type = PublisherIT.type(manifest, "Invoice");
        List<String> customers = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        List<Map<String, Object>> all = fetch("customerId", customers, null);
        assertThat(all).hasSize(7);
        int checked = 0;
        for (Map<String, Object> where : comparisons(type, all)) {
            Set<String> expected = admitted(type, where, all);
            Set<String> pushed = new TreeSet<>();
            fetch("customerId", customers, where).forEach(r -> pushed.add((String) r.get("number")));
            assertThat(pushed).as("pushed %s", where).isEqualTo(expected);

            /* The lookup by number takes no Specification, so the publisher evaluates the same filter itself. */
            Set<String> evaluated = new TreeSet<>();
            for (List<String> batch : List.of(List.of("INV-1", "INV-2", "INV-3"), List.of("INV-4", "INV-5", "INV-6"), List.of("INV-7"))) {
                fetch("number", batch, where).forEach(r -> evaluated.add((String) r.get("number")));
            }
            assertThat(evaluated).as("evaluated %s", where).isEqualTo(expected);
            checked++;
        }
        assertThat(checked).isGreaterThan(50);
    }

    /* Every declared (property, operator), with operands from the data, a value absent from it, and null. */
    private static List<Map<String, Object>> comparisons(JsonNode type, List<Map<String, Object>> records) {
        List<Map<String, Object>> out = new ArrayList<>();
        type.get("filter").get("properties").fields().forEachRemaining(entry -> {
            String property = entry.getKey();
            String wireType = type.get("properties").get(property).get("type").asText();
            Set<Object> values = new LinkedHashSet<>();
            records.forEach(r -> {
                if (r.get(property) != null) {
                    values.add(r.get(property));
                }
            });
            List<Object> operands = new ArrayList<>(values);
            operands.add(absent(wireType));
            for (JsonNode op : entry.getValue()) {
                String name = op.asText();
                switch (name) {
                    case "isNull", "isNotNull" -> out.add(map("property", property, "op", name));
                    case "in" -> {
                        out.add(map("property", property, "op", name, "value", operands.subList(0, Math.min(2, operands.size()))));
                        out.add(map("property", property, "op", name, "value", List.of()));
                        out.add(map("property", property, "op", name, "value", Arrays.asList(operands.get(0), null)));
                    }
                    case "between" -> {
                        for (Object low : operands) {
                            for (Object high : operands) {
                                out.add(map("property", property, "op", name, "value", List.of(low, high)));
                            }
                        }
                    }
                    case "prefix", "iprefix", "ieq" -> {
                        Set<String> texts = new LinkedHashSet<>(List.of("", "%", "_", "5", "50%", "5000_", "M", "m", "MÜ", "ACME", "acme", "é", "É"));
                        for (Object v : values) {
                            String s = (String) v;
                            texts.add(s);
                            texts.add(s.toUpperCase());
                            texts.add(s.substring(0, Math.min(3, s.length())));
                        }
                        texts.forEach(t -> out.add(map("property", property, "op", name, "value", t)));
                    }
                    default -> {
                        operands.forEach(v -> out.add(map("property", property, "op", name, "value", v)));
                        out.add(map("property", property, "op", name, "value", null));
                    }
                }
            }
        });
        return out;
    }

    private static Object absent(String wireType) {
        return switch (wireType) {
            case "integer" -> 424242L;
            case "decimal" -> "1200.49";
            case "date" -> "2020-01-15";
            case "enum" -> "VOID";
            default -> "nobody";
        };
    }

    private static Set<String> admitted(JsonNode type, Map<String, Object> where, List<Map<String, Object>> all) {
        Map<String, ValueType> types = new LinkedHashMap<>();
        type.get("properties").fields().forEachRemaining(e -> types.put(e.getKey(),
                ValueType.valueOf(e.getValue().get("type").asText().toUpperCase())));
        ReferenceEvaluator evaluator = new ReferenceEvaluator(types::get, p -> ValueType.STRING);
        String identity = type.get("identity").asText();
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> record : all) {
            if (evaluator.test(WhereParser.parse(where), record)) {
                out.add(String.valueOf(record.get(identity)));
            }
        }
        return out;
    }

    private List<Map<String, Object>> queryAll(Map<String, Object> where) {
        List<Map<String, Object>> records = new ArrayList<>();
        Object cursor = null;
        do {
            Map<String, Object> request = map("type", "Customer", "cursor", cursor);
            if (where != null) {
                request.put("where", where);
            }
            RealmClient.Answer answer = realm.post("/query", request);
            assertThat(answer.status()).as("%s -> %s", where, answer.body()).isEqualTo(200);
            JsonNode page = assertValid("QueryResponse", answer.json());
            records.addAll(RealmClient.JSON.convertValue(page.get("records"), new TypeReference<List<Map<String, Object>>>() { }));
            cursor = page.get("next").isNull() ? null : page.get("next").asText();
        } while (cursor != null);
        return records;
    }

    private List<Map<String, Object>> fetch(String by, List<String> keys, Map<String, Object> where) {
        Map<String, Object> request = map("type", "Invoice", "by", by, "keys", keys);
        if (where != null) {
            request.put("where", where);
        }
        RealmClient.Answer answer = realm.post("/fetch", request);
        assertThat(answer.status()).as("%s -> %s", where, answer.body()).isEqualTo(200);
        JsonNode response = assertValid("FetchResponse", answer.json());
        PublisherIT.assertBatchContract(response, by, keys);
        return RealmClient.JSON.convertValue(response.get("records"), new TypeReference<List<Map<String, Object>>>() { });
    }
}
