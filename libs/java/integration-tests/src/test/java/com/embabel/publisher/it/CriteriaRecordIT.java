package com.embabel.publisher.it;

import com.embabel.publisher.it.app.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.embabel.publisher.it.ProtocolSchema.assertValid;
import static com.embabel.publisher.it.RealmClient.map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A type served by the application's own search methods: criteria records, a repository with no
 * {@code JpaSpecificationExecutor}. The manifest declares exactly what the records carry.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CriteriaRecordIT {

    @LocalServerPort
    int port;

    RealmClient realm;

    @BeforeEach
    void client() {
        realm = new RealmClient(port, "test-token");
    }

    @Test
    void theManifestDeclaresExactlyTheRecordsPairs() {
        JsonNode product = PublisherIT.type(assertValid("Manifest", realm.get("").json()), "Product");
        assertThat(product.get("filter").toString()).isEqualTo("""
                {"properties":{"category":["eq","in"],"name":["prefix"],"price":["between"],"launched":["gte"]},"logic":["and"]}""");
        assertThat(product.get("sort").toString()).isEqualTo("[\"sku\"]");
        assertThat(product.get("query").toString()).isEqualTo("{\"maxPageSize\":3,\"total\":true}");
    }

    @Test
    void aPushedConjunctionFillsTheRecord() {
        JsonNode page = query(map("type", "Product", "where", map("and", List.of(
                map("property", "category", "op", "in", "value", List.of("TOOLS", "FOOD")),
                map("property", "price", "op", "between", "value", List.of("10", "20")))),
                "sort", List.of(map("property", "sku", "direction", "desc"))));
        assertThat(PublisherIT.ids(page, "sku")).containsExactly("X-000", "T-200", "T-100");
        assertThat(page.get("applied").asBoolean()).isTrue();
        assertThat(page.get("sorted").asBoolean()).isTrue();
        assertThat(page.get("total").asLong()).isEqualTo(3);
    }

    @Test
    void aSecondValueForOnePairIsNotAppliedAndSaysSo() {
        JsonNode page = query(map("type", "Product", "where", map("and", List.of(
                map("property", "name", "op", "prefix", "value", "Ham"),
                map("property", "name", "op", "prefix", "value", "Hammer"))),
                "sort", List.of(map("property", "sku")), "limit", 1));
        assertThat(page.get("applied").asBoolean()).isFalse();
        /* What was applied is one conjunct, so the records are a superset the world narrows; no limit was applied. */
        assertThat(PublisherIT.ids(page, "name")).containsExactly("Hamburger", "Hammer", "Hammer drill");
        assertThat(page.get("next").isTextual()).as("the walk goes on past the limit of 1").isTrue();
    }

    @Test
    void anOperandNoRecordCanMatchFindsNothingRatherThanEverything() {
        for (Object where : List.of(map("property", "category", "op", "eq", "value", null),
                map("property", "category", "op", "in", "value", Arrays.asList(null, "VOID")),
                map("property", "launched", "op", "gte", "value", null))) {
            JsonNode page = query(map("type", "Product", "where", where));
            assertThat(page.get("records").size()).as("%s", where).isZero();
            assertThat(page.get("total").asLong()).isZero();
            assertThat(page.get("next").isNull()).isTrue();
        }
    }

    @Test
    void aFilterTheRecordDoesNotCarryIsRefused() {
        RealmClient.Answer answer = realm.post("/query", map("type", "Product", "where", map("property", "sku", "op", "eq", "value", "T-100")));
        PublisherIT.assertProblem(answer, 400, "UNSUPPORTED");
        answer = realm.post("/query", map("type", "Product", "where", map("property", "name", "op", "iprefix", "value", "ham")));
        PublisherIT.assertProblem(answer, 400, "UNSUPPORTED");
    }

    private JsonNode query(Map<String, Object> request) {
        assertValid("QueryRequest", RealmClient.JSON.valueToTree(request));
        RealmClient.Answer answer = realm.post("/query", request);
        assertThat(answer.status()).as(answer.body()).isEqualTo(200);
        return assertValid("QueryResponse", answer.json());
    }
}
