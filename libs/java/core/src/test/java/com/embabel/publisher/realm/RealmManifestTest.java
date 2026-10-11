package com.embabel.publisher.realm;

import com.embabel.publisher.annotation.RealmType;
import com.embabel.publisher.annotation.RealmVerb;
import com.embabel.publisher.where.Op;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealmManifestTest {

    @RealmType(name = "Customer", cache = @RealmType.Cache(value = RealmType.Scope.SHARED, ttlSeconds = 60))
    record CustomerView(@RealmType.Id Long id,
                        @RealmType.Filterable({Op.EQ, Op.IPREFIX}) @RealmType.Sortable String name,
                        @RealmType.Spine("Organization") String website,
                        Tier tier,
                        @RealmType.Filterable BigDecimal balance,
                        List<String> tags) {
    }

    @RealmType(name = "Invoice")
    record InvoiceView(@RealmType.Id String number,
                       @RealmType.Reference(type = CustomerView.class, relationship = "BILLED", direction = RealmType.Direction.IN) Long customerId,
                       LocalDate dueDate) {
    }

    enum Tier { FREE, PRO }

    record Refund(String number, BigDecimal amount, boolean notifyCustomer) {
    }

    record RefundResult(String refundId) {
    }

    private static RealmSettings settings() {
        RealmSettings settings = new RealmSettings();
        settings.setName("billing");
        settings.setTitle("Billing");
        settings.getAuth().setToken("t");
        return settings;
    }

    private static LookupBinding lookup(Class<?> view, String by, boolean pushes, boolean sorts) {
        return new LookupBinding(TypeIntrospector.introspect(view), by, 100, pushes, sorts, (k, w, s) -> List.of(), "test");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theManifestCarriesWhatTheViewsDeclare() {
        VerbBinding refund = new VerbBinding("issueRefund", "Refund an invoice.", InvoiceView.class, RealmVerb.Effect.EXTERNAL, true,
                RealmVerb.Required.ALWAYS, "Refunds need a second person.", Refund.class, RefundResult.class, in -> null, "test");
        Realm realm = Realm.build(settings(),
                List.of(lookup(CustomerView.class, "id", true, true), lookup(InvoiceView.class, "customerId", false, false),
                        lookup(InvoiceView.class, "number", false, false)),
                List.of(new QueryBinding(TypeIntrospector.introspect(CustomerView.class), 200, true, true, QueryBinding.Paging.PAGE, null, "test")),
                List.of(refund));
        Map<String, Object> manifest = realm.manifest();
        assertThat(manifest).containsEntry("protocol", "1").containsEntry("name", "billing")
                .containsEntry("auth", Map.of("schemes", List.of("bearer"), "actingUser", "ignored"));
        List<Map<String, Object>> types = (List<Map<String, Object>>) manifest.get("types");
        assertThat(types).extracting(t -> t.get("name")).containsExactly("Customer", "Invoice");

        Map<String, Object> customer = types.get(0);
        Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) customer.get("properties");
        assertThat(props.keySet()).containsExactly("id", "name", "website", "tier", "balance", "tags");
        assertThat(props.get("id")).isEqualTo(Map.of("type", "integer"));
        assertThat(props.get("website")).containsEntry("spine", "Organization");
        assertThat(props.get("tier")).containsEntry("type", "enum").containsEntry("values", List.of("FREE", "PRO"));
        assertThat(props.get("balance")).containsEntry("type", "decimal");
        assertThat(props.get("tags")).containsEntry("type", "list").containsEntry("item", Map.of("type", "string"));
        assertThat(customer.get("filter")).isEqualTo(Map.of(
                "properties", Map.of("name", List.of("eq", "iprefix"),
                        "balance", List.of("eq", "in", "gt", "gte", "lt", "lte", "between", "isNull", "isNotNull")),
                "logic", List.of("and")));
        assertThat(customer.get("sort")).isEqualTo(List.of("name"));
        assertThat(customer.get("query")).isEqualTo(Map.of("maxPageSize", 200, "total", true));
        assertThat(customer.get("cache")).isEqualTo(Map.of("scope", "shared", "ttlSeconds", 60));

        Map<String, Object> invoice = types.get(1);
        assertThat(((List<Map<String, Object>>) invoice.get("lookups"))).extracting(l -> l.get("by")).containsExactly("number", "customerId");
        assertThat(((Map<String, Map<String, Object>>) invoice.get("properties")).get("customerId").get("references"))
                .isEqualTo(Map.of("type", "Customer", "relationship", "BILLED", "direction", "in"));
        assertThat(invoice).doesNotContainKeys("filter", "sort", "query", "cache");

        Map<String, Object> verb = ((List<Map<String, Object>>) manifest.get("verbs")).get(0);
        assertThat(verb).containsEntry("name", "issueRefund").containsEntry("subject", "Invoice").containsEntry("effect", "external")
                .containsEntry("idempotent", true)
                .containsEntry("approval", Map.of("required", "always", "reason", "Refunds need a second person."));
        assertThat((Map<String, Object>) verb.get("input")).containsEntry("required", List.of("notifyCustomer"));
        assertThat(((Map<String, Map<String, Object>>) ((Map<String, Object>) verb.get("input")).get("properties")).get("amount"))
                .isEqualTo(Map.of("type", "string", "format", "decimal"));
    }

    @Test
    void anIdentityWithoutALookupIsRefused() {
        assertThatThrownBy(() -> Realm.build(settings(), List.of(lookup(InvoiceView.class, "customerId", false, false),
                lookup(CustomerView.class, "id", true, true)), List.of(), List.of()))
                .isInstanceOf(RealmDefinitionException.class).hasMessageContaining("Invoice has no lookup on its identity number");
    }

    @Test
    void aReferenceToAnUnpublishedTypeIsRefused() {
        assertThatThrownBy(() -> Realm.build(settings(), List.of(lookup(InvoiceView.class, "number", false, false)), List.of(), List.of()))
                .isInstanceOf(RealmDefinitionException.class).hasMessageContaining("references Customer");
    }

    @Test
    void sortableWithoutAnythingThatSortsIsRefused() {
        assertThatThrownBy(() -> Realm.build(settings(), List.of(lookup(CustomerView.class, "id", true, false)), List.of(), List.of()))
                .isInstanceOf(RealmDefinitionException.class).hasMessageContaining("sortable");
    }

    @Test
    void approvalRulesTheLibraryCannotEnforceAreRefused() {
        VerbBinding when = new VerbBinding("v", "d", null, RealmVerb.Effect.WRITE, false, RealmVerb.Required.WHEN, "", null, null, in -> null, "test");
        assertThatThrownBy(() -> Realm.build(settings(), List.of(lookup(CustomerView.class, "id", true, true)), List.of(), List.of(when)))
                .isInstanceOf(RealmDefinitionException.class).hasMessageContaining("approval when");
    }

    @RealmType
    record Floaty(@RealmType.Id Long id, double score) {
    }

    @RealmType
    record Ranged(@RealmType.Id Long id, @RealmType.Filterable(Op.GT) String name) {
    }

    @Test
    void typesTheProtocolDoesNotHaveAreRefused() {
        assertThatThrownBy(() -> TypeIntrospector.introspect(Floaty.class)).hasMessageContaining("no floating point");
        assertThatThrownBy(() -> TypeIntrospector.introspect(Ranged.class)).hasMessageContaining("does not define gt on a string");
    }

    @Test
    void settingsAreChecked() {
        RealmSettings bad = settings();
        bad.setName("Billing");
        assertThatThrownBy(() -> Realm.build(bad, List.of(), List.of(), List.of())).hasMessageContaining("embabel.realm.name");
        RealmSettings acting = settings();
        acting.getActingUser().setMode("required");
        assertThatThrownBy(() -> Realm.build(acting, List.of(), List.of(), List.of())).hasMessageContaining("only ignored");
    }

    @Test
    void theOffSwitchVariableWinsOnlyToTurnOff() {
        assertThat(OffSwitch.enabled("false", true)).isFalse();
        assertThat(OffSwitch.enabled("FALSE", true)).isFalse();
        assertThat(OffSwitch.enabled("true", false)).isFalse();
        assertThat(OffSwitch.enabled(null, true)).isTrue();
        assertThat(OffSwitch.enabled("true", true)).isTrue();
    }
}
