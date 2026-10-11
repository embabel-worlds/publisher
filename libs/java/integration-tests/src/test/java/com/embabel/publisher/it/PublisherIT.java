package com.embabel.publisher.it;

import com.embabel.publisher.it.app.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.embabel.publisher.it.ProtocolSchema.assertValid;
import static com.embabel.publisher.it.RealmClient.map;
import static org.assertj.core.api.Assertions.assertThat;

/** The operations over HTTP, every body validated against the protocol's schema. */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublisherIT {

    @LocalServerPort
    int port;

    RealmClient realm;

    @BeforeEach
    void client() {
        realm = new RealmClient(port, "test-token");
    }

    /* ---- manifest ---- */

    @Test
    void theManifestIsValidAndRevalidatesByEtag() {
        RealmClient.Answer answer = realm.get("");
        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.header("Content-Type")).startsWith("application/json");
        JsonNode manifest = assertValid("Manifest", answer.json());
        assertThat(manifest.get("name").asText()).isEqualTo("billing");
        assertThat(manifest.get("auth").toString()).isEqualTo("{\"schemes\":[\"bearer\"],\"actingUser\":\"ignored\"}");

        JsonNode customer = type(manifest, "Customer");
        assertThat(customer.get("description").asText()).isEqualTo("A company that buys from us.");
        assertThat(customer.at("/properties/balance/description").asText()).isEqualTo("Outstanding, in the account currency.");
        assertThat(customer.at("/properties/tier/values").toString()).isEqualTo("[\"FREE\",\"PRO\",\"ENTERPRISE\"]");
        assertThat(customer.at("/filter/properties/name").toString())
                .isEqualTo("[\"eq\",\"in\",\"isNull\",\"isNotNull\",\"prefix\",\"ieq\",\"iprefix\"]");
        assertThat(customer.at("/filter/logic").toString()).isEqualTo("[\"and\"]");
        assertThat(customer.get("filter").get("properties").has("externalRef")).as("not opted in").isFalse();
        assertThat(customer.get("sort").toString()).isEqualTo("[\"name\",\"city\",\"balance\"]");
        assertThat(customer.get("query").toString()).isEqualTo("{\"maxPageSize\":5,\"total\":true}");
        assertThat(customer.get("cache").toString()).isEqualTo("{\"scope\":\"shared\",\"ttlSeconds\":60}");

        JsonNode invoice = type(manifest, "Invoice");
        assertThat(invoice.at("/properties/customerId/references").toString())
                .isEqualTo("{\"type\":\"Customer\",\"relationship\":\"BILLED\",\"direction\":\"in\"}");
        assertThat(invoice.get("lookups").toString())
                .isEqualTo("[{\"by\":\"number\",\"maxKeys\":3},{\"by\":\"customerId\",\"maxKeys\":100}]");
        assertThat(invoice.get("query").toString()).as("a Limit query does not count").isEqualTo("{\"maxPageSize\":3}");

        String etag = answer.header("ETag");
        assertThat(etag).matches("\"[0-9a-f]{32}\"");
        RealmClient.Answer again = realm.get("", "If-None-Match", etag);
        assertThat(again.status()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
    }

    @Test
    void verbsAreDeclaredWithTheirApprovalPolicy() {
        JsonNode manifest = realm.get("").json();
        JsonNode writeOff = verb(manifest, "writeOff");
        assertThat(writeOff.get("approval").toString())
                .isEqualTo("{\"required\":\"always\",\"reason\":\"Write-offs need a second person.\"}");
        assertThat(writeOff.at("/input/properties/amount").toString()).isEqualTo("{\"type\":\"string\",\"format\":\"decimal\"}");
        assertThat(writeOff.at("/input/required").toString()).isEqualTo("[\"customerId\",\"amount\"]");
        JsonNode rename = verb(manifest, "rename");
        assertThat(rename.get("effect").asText()).isEqualTo("write");
        assertThat(rename.get("idempotent").asBoolean()).isTrue();
        assertThat(rename.get("subject").asText()).isEqualTo("Customer");
        assertThat(rename.get("approval").toString()).isEqualTo("{\"required\":\"never\"}");
    }

    /* ---- authentication ---- */

    @Test
    void theWorldsCredentialIsRequiredOnEveryPath() {
        for (RealmClient client : List.of(new RealmClient(port, null), new RealmClient(port, "wrong"), new RealmClient(port, "test-tokenx"))) {
            for (RealmClient.Answer answer : List.of(client.get(""), client.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of("1"))),
                    client.post("/query", map("type", "Customer")), client.post("/verbs/rename", map()), client.get("/nowhere"))) {
                assertThat(answer.status()).isEqualTo(401);
                assertThat(answer.header("Content-Type")).startsWith("application/problem+json");
                assertThat(assertValid("Problem", answer.json()).get("code").asText()).isEqualTo("UNAUTHENTICATED");
                assertThat(answer.body()).doesNotContain("Customer");
            }
        }
    }

    /* ---- fetch ---- */

    @Test
    void everyKeyIsAccountedForAndRecordsKeepTheirDeclaredForm() {
        JsonNode response = fetch(map("type", "Customer", "by", "id", "keys", List.of("1", "2", "999", "abc", "01", "2")));
        assertValid("FetchResponse", response);
        assertBatchContract(response, "id", List.of("1", "2", "999", "abc", "01"));
        assertThat(ids(response, "id")).containsExactlyInAnyOrder("1", "2");
        assertThat(strings(response.get("missing"))).containsExactlyInAnyOrder("999", "abc", "01");
        JsonNode acme = record(response, "id", "1");
        assertThat(acme.get("id").isNumber()).as("an integer property stays a number").isTrue();
        assertThat(acme.get("balance").asText()).isEqualTo("1200.50");
        assertThat(acme.get("balance").isTextual()).isTrue();
        assertThat(acme.get("externalRef").asText()).as("beyond 2^53, a string").isEqualTo("9007199254740993");
        assertThat(acme.get("externalRef").isTextual()).isTrue();
        assertThat(acme.get("since").asText()).isEqualTo("2020-01-15");
        assertThat(acme.get("region").asText()).isEqualTo("North");
        assertThat(response.get("applied").asBoolean()).isTrue();
    }

    @Test
    void aPushedFilterNarrowsAndKeysThatMatchNothingAreMissing() {
        JsonNode response = fetch(map("type", "Invoice", "by", "customerId", "keys", List.of("1", "2", "3", "4"),
                "where", map("property", "status", "op", "eq", "value", "OPEN"),
                "sort", List.of(map("property", "dueDate", "direction", "desc"))));
        assertValid("FetchResponse", response);
        assertBatchContract(response, "customerId", List.of("1", "2", "3", "4"));
        assertThat(ids(response, "number")).containsExactly("INV-2", "INV-1", "INV-5");
        assertThat(strings(response.get("missing"))).containsExactlyInAnyOrder("2", "3");
        assertThat(response.get("sorted").asBoolean()).isTrue();
        for (JsonNode r : response.get("records")) {
            assertThat(r.get("status").asText()).isEqualTo("OPEN");
            assertThat(r.get("customerId").isNumber()).isTrue();
        }
    }

    @Test
    void aLookupThatTakesNoFilterIsFilteredByThePublisherExactly() {
        JsonNode response = fetch(map("type", "Invoice", "by", "number", "keys", List.of("INV-1", "INV-3", "INV-404"),
                "where", map("and", List.of(map("property", "amount", "op", "gte", "value", "50"),
                        map("property", "status", "op", "in", "value", List.of("OPEN", "PAID"))))));
        assertValid("FetchResponse", response);
        assertBatchContract(response, "number", List.of("INV-1", "INV-3", "INV-404"));
        assertThat(ids(response, "number")).containsExactly("INV-1");
        assertThat(strings(response.get("missing"))).containsExactlyInAnyOrder("INV-3", "INV-404");
        assertThat(response.get("applied").asBoolean()).isTrue();
        assertThat(response.has("sorted")).isFalse();
    }

    @Test
    void aSortTheLookupCannotTakeIsReportedNotFaked() {
        JsonNode response = fetch(map("type", "Invoice", "by", "number", "keys", List.of("INV-1", "INV-2"),
                "sort", List.of(map("property", "dueDate"))));
        assertValid("FetchResponse", response);
        assertThat(response.get("sorted").asBoolean()).isFalse();
    }

    @Test
    void fetchRefusals() {
        assertProblem(realm.post("/fetch", map("type", "Nope", "by", "id", "keys", List.of("1"))), 404, "UNKNOWN_TYPE");
        assertProblem(realm.post("/fetch", map("type", "Customer", "by", "name", "keys", List.of("x"))), 400, "UNSUPPORTED");
        assertProblem(realm.post("/fetch", map("type", "Invoice", "by", "number", "keys", List.of("a", "b", "c", "d"))), 400, "KEY_BOUND");
        assertProblem(realm.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of("1"),
                "where", map("property", "externalRef", "op", "eq", "value", 1))), 400, "UNSUPPORTED");
        assertProblem(realm.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of("1"),
                "where", map("or", List.of(map("property", "name", "op", "eq", "value", "x"))))), 400, "UNSUPPORTED");
        assertProblem(realm.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of("1"),
                "where", map("property", "since", "op", "gt", "value", "yesterday"))), 400, "INVALID_REQUEST");
        assertProblem(realm.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of("1"),
                "sort", List.of(map("property", "since")))), 400, "UNSUPPORTED");
        assertProblem(realm.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of("1"), "limitPerKey", 1)), 400, "UNSUPPORTED");
        assertProblem(realm.post("/fetch", "{not json"), 400, "INVALID_REQUEST");
        assertProblem(realm.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of())), 400, "INVALID_REQUEST");
        assertProblem(realm.get("/fetch"), 400, "UNSUPPORTED");
        assertProblem(realm.get("/changes"), 404, "UNSUPPORTED");
        Object deep = map("property", "name", "op", "isNull");
        for (int i = 0; i < 9; i++) {
            deep = map("and", List.of(deep));
        }
        assertProblem(realm.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of("1"), "where", deep)), 400, "EXPRESSION_BOUND");
    }

    /* ---- query ---- */

    @Test
    void aWalkPagesByCursorToTheEndWithATotal() {
        Map<String, Object> request = map("type", "Customer", "where", map("property", "city", "op", "ieq", "value", "MADISON"),
                "sort", List.of(map("property", "name", "direction", "asc")), "pageSize", 2);
        List<String> walked = new ArrayList<>();
        Object cursor = null;
        int pages = 0;
        do {
            request.put("cursor", cursor);
            JsonNode page = query(request);
            assertValid("QueryResponse", page);
            assertThat(page.get("total").asLong()).isEqualTo(5);
            assertThat(page.get("applied").asBoolean()).isTrue();
            assertThat(page.get("sorted").asBoolean()).isTrue();
            assertThat(page.get("records").size()).isLessThanOrEqualTo(2);
            page.get("records").forEach(r -> walked.add(r.get("name").asText()));
            cursor = page.get("next").isNull() ? null : page.get("next").asText();
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        /* Order within a page follows the store's collation for strings; the walk must cover each match exactly once. */
        assertThat(walked).hasSize(5);
        assertThat(new HashSet<>(walked)).containsExactlyInAnyOrder("5000_off", "50%_off", "Acme", "acme labs", "muller & sons");
    }

    @Test
    void thePageSizeIsCappedAtTheDeclaredMaximum() {
        JsonNode page = query(map("type", "Customer", "pageSize", 1000));
        assertValid("QueryResponse", page);
        assertThat(page.get("records").size()).isEqualTo(5);
        assertThat(page.get("total").asLong()).isEqualTo(10);
        assertThat(page.get("next").isTextual()).isTrue();
    }

    @Test
    void aLimitEndsTheWalkWithoutTruncation() {
        Map<String, Object> request = map("type", "Customer", "sort", List.of(map("property", "name")), "pageSize", 2, "limit", 3);
        JsonNode first = query(request);
        assertValid("QueryResponse", first);
        request.put("cursor", first.get("next").asText());
        JsonNode second = query(request);
        assertValid("QueryResponse", second);
        assertThat(second.get("records").size()).isEqualTo(1);
        assertThat(second.get("next").isNull()).isTrue();
        assertThat(second.has("truncated")).isFalse();
    }

    @Test
    void nullsSortAsTheGreatestValue() {
        List<String> asc = walk(map("type", "Customer", "sort", List.of(map("property", "balance", "direction", "asc"))), "id");
        assertThat(asc).containsExactly("6", "2", "9", "4", "10", "1", "5", "7", "3", "8");
        List<String> desc = walk(map("type", "Customer", "sort", List.of(map("property", "balance", "direction", "desc"))), "id");
        assertThat(desc).containsExactly("3", "8", "7", "1", "5", "10", "4", "9", "2", "6");
    }

    @Test
    void aQueryThatCannotPageSaysItWasTruncated() {
        JsonNode page = query(map("type", "Invoice", "sort", List.of(map("property", "dueDate"))));
        assertValid("QueryResponse", page);
        assertThat(page.get("records").size()).isEqualTo(3);
        assertThat(page.get("next").isNull()).isTrue();
        assertThat(page.get("truncated").get("reason").asText()).isNotBlank();
        assertThat(page.has("total")).isFalse();

        JsonNode exact = query(map("type", "Invoice", "where", map("property", "status", "op", "eq", "value", "OPEN"), "limit", 2,
                "sort", List.of(map("property", "dueDate"))));
        assertValid("QueryResponse", exact);
        assertThat(ids(exact, "number")).containsExactly("INV-5", "INV-1");
        assertThat(exact.has("truncated")).as("the limit ended the walk").isFalse();
    }

    @Test
    void queryRefusals() {
        assertProblem(realm.post("/query", map("type", "Nope")), 404, "UNKNOWN_TYPE");
        assertProblem(realm.post("/query", map("type", "Customer", "search", "acme")), 400, "UNSUPPORTED");
        assertProblem(realm.post("/query", map("type", "Customer", "cursor", "forged")), 400, "INVALID_REQUEST");
        assertProblem(realm.post("/query", map("type", "Customer", "where", map("not", map("property", "name", "op", "isNull")))), 400, "UNSUPPORTED");
        assertProblem(realm.post("/query", map("type", "Customer", "where", map("exists", map("type", "Invoice", "via", "customerId")))), 400, "UNSUPPORTED");
    }

    /* ---- verbs ---- */

    @Test
    void aVerbRunsThroughTheServiceAndAnswersItsDeclaredOutput() {
        JsonNode manifest = realm.get("").json();
        RealmClient.Answer answer = realm.post("/verbs/rename", map("id", 10, "name", "Beta Two"));
        assertThat(answer.status()).isEqualTo(200);
        ProtocolSchema.assertValidAgainst(verb(manifest, "rename").get("output"), answer.json());
        assertThat(answer.json().toString()).isEqualTo("{\"id\":10,\"name\":\"Beta Two\"}");
        assertThat(record(fetch(map("type", "Customer", "by", "id", "keys", List.of("10"))), "id", "10").get("name").asText()).isEqualTo("Beta Two");
    }

    @Test
    void anAlwaysApprovedVerbRunsOnTheWorldsCredentialInThisVersion() {
        JsonNode manifest = realm.get("").json();
        RealmClient.Answer answer = realm.post("/verbs/writeOff", map("customerId", 6, "amount", "0.50"));
        assertThat(answer.status()).isEqualTo(200);
        ProtocolSchema.assertValidAgainst(verb(manifest, "writeOff").get("output"), answer.json());
        assertThat(answer.json().get("balance").asText()).isEqualTo("-10.50");
    }

    @Test
    void verbRefusals() {
        assertProblem(realm.post("/verbs/explode", map()), 404, "UNKNOWN_VERB");
        assertProblem(realm.post("/verbs/rename", map("name", "x")), 400, "INVALID_REQUEST");
        assertProblem(realm.post("/verbs/rename", map("id", "ten", "name", "x")), 400, "INVALID_REQUEST");
        assertProblem(realm.post("/verbs/rename", map("id", 404, "name", "x")), 400, "INVALID_REQUEST");
        assertProblem(realm.post("/verbs/writeOff", map("customerId", 1, "amount", 1.5)), 400, "INVALID_REQUEST");
    }

    /* ---- helpers ---- */

    private JsonNode fetch(Map<String, Object> request) {
        assertValid("FetchRequest", RealmClient.JSON.valueToTree(request));
        RealmClient.Answer answer = realm.post("/fetch", request);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return answer.json();
    }

    private JsonNode query(Map<String, Object> request) {
        assertValid("QueryRequest", RealmClient.JSON.valueToTree(request));
        RealmClient.Answer answer = realm.post("/query", request);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return answer.json();
    }

    private List<String> walk(Map<String, Object> request, String property) {
        List<String> values = new ArrayList<>();
        Object cursor = null;
        do {
            request.put("cursor", cursor);
            JsonNode page = assertValid("QueryResponse", query(request));
            page.get("records").forEach(r -> values.add(r.get(property).asText()));
            cursor = page.get("next").isNull() ? null : page.get("next").asText();
        } while (cursor != null);
        return values;
    }

    static void assertProblem(RealmClient.Answer answer, int status, String code) {
        assertThat(answer.status()).as(answer.body()).isEqualTo(status);
        assertThat(answer.header("Content-Type")).startsWith("application/problem+json");
        JsonNode problem = assertValid("Problem", answer.json());
        assertThat(problem.get("code").asText()).isEqualTo(code);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
    }

    /**
     * What the schema cannot say (§4.2): every record's lookup value is the canonical form of a
     * requested key, and every requested key has a record or is missing or failed, never two of those.
     */
    static void assertBatchContract(JsonNode response, String by, List<String> requested) {
        Set<String> withRecords = new LinkedHashSet<>();
        for (JsonNode record : response.get("records")) {
            JsonNode value = record.get(by);
            assertThat(value).as("every record carries the lookup property").isNotNull();
            assertThat(value.isNull()).isFalse();
            String canonical = value.isNumber() ? value.bigIntegerValue().toString() : value.asText();
            assertThat(requested).as("the record's %s is a requested key", by).contains(canonical);
            withRecords.add(canonical);
        }
        Set<String> missing = strings(response.get("missing"));
        Set<String> failed = strings(response.get("failed"));
        for (String key : new LinkedHashSet<>(requested)) {
            int places = (withRecords.contains(key) ? 1 : 0) + (missing.contains(key) ? 1 : 0) + (failed.contains(key) ? 1 : 0);
            assertThat(places).as("key %s is accounted for exactly once", key).isEqualTo(1);
        }
    }

    static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    static List<String> ids(JsonNode response, String property) {
        List<String> out = new ArrayList<>();
        response.get("records").forEach(r -> out.add(r.get(property).asText()));
        return out;
    }

    static JsonNode record(JsonNode response, String property, String value) {
        for (JsonNode r : response.get("records")) {
            if (r.get(property).asText().equals(value)) {
                return r;
            }
        }
        throw new AssertionError("no record with " + property + " " + value + " in " + response);
    }

    static JsonNode type(JsonNode manifest, String name) {
        for (JsonNode t : manifest.get("types")) {
            if (t.get("name").asText().equals(name)) {
                return t;
            }
        }
        throw new AssertionError("no type " + name);
    }

    static JsonNode verb(JsonNode manifest, String name) {
        for (JsonNode v : manifest.get("verbs")) {
            if (v.get("name").asText().equals(name)) {
                return v;
            }
        }
        throw new AssertionError("no verb " + name);
    }
}
