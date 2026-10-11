package com.embabel.publisher.realm;

import com.embabel.publisher.annotation.RealmType;
import com.embabel.publisher.annotation.RealmVerb;
import com.embabel.publisher.where.Op;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A realm as declared: its settings, types, lookups, queries and verbs, validated together and
 * rendered as the manifest (§3). Immutable once built, so the manifest and its ETag never drift
 * from what the operations serve.
 */
public final class Realm {

    /**
     * The operators this version of the library evaluates exactly, in a pushed-down translation
     * and in the reference evaluator alike. A conjunction of these is what it accepts.
     */
    public static final Set<Op> SUPPORTED_OPS = EnumSet.of(
            Op.EQ, Op.IN, Op.GT, Op.GTE, Op.LT, Op.LTE, Op.BETWEEN, Op.PREFIX, Op.IS_NULL, Op.IS_NOT_NULL, Op.IEQ, Op.IPREFIX);

    private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");
    private static final Set<String> MATURITIES = Set.of("experimental", "beta", "stable", "deprecated");

    /** One type with everything that serves it. */
    public record TypeEntry(TypeModel model, Map<String, LookupBinding> lookups, QueryBinding query,
                            Map<String, Set<Op>> filter, List<String> sort) {
    }

    private final RealmSettings settings;
    private final Map<String, TypeEntry> types;
    private final Map<String, VerbBinding> verbs;

    private Realm(RealmSettings settings, Map<String, TypeEntry> types, Map<String, VerbBinding> verbs) {
        this.settings = settings;
        this.types = types;
        this.verbs = verbs;
    }

    public RealmSettings settings() {
        return settings;
    }

    public TypeEntry type(String name) {
        return types.get(name);
    }

    public Collection<TypeEntry> types() {
        return types.values();
    }

    public VerbBinding verb(String name) {
        return verbs.get(name);
    }

    /** The operators a property of a type may be filtered with, resolving an empty {@code @Filterable} to all supported. */
    public static Set<Op> resolveOps(PropertyModel property) {
        if (!property.filterable()) {
            return Set.of();
        }
        if (!property.filterOps().isEmpty()) {
            return property.filterOps();
        }
        Set<Op> all = new LinkedHashSet<>();
        for (Op op : SUPPORTED_OPS) {
            if (op.appliesTo(property.type())) {
                all.add(op);
            }
        }
        return all;
    }

    public static Realm build(RealmSettings settings, List<LookupBinding> lookups, List<QueryBinding> queries, List<VerbBinding> verbs) {
        checkSettings(settings);
        Map<String, TypeModel> models = new TreeMap<>();
        Map<String, Map<String, LookupBinding>> lookupsByType = new TreeMap<>();
        Map<String, QueryBinding> queryByType = new TreeMap<>();

        for (LookupBinding lookup : lookups) {
            TypeModel model = register(models, lookup.type());
            if (model.property(lookup.by()) == null) {
                throw new RealmDefinitionException(lookup.source() + ": " + model.name() + " has no property " + lookup.by());
            }
            if (!model.property(lookup.by()).type().isScalar()) {
                throw new RealmDefinitionException(lookup.source() + ": a lookup is by a scalar property");
            }
            if (lookup.maxKeys() < 1 || lookup.maxKeys() > 1000) {
                throw new RealmDefinitionException(lookup.source() + ": maxKeys is between 1 and 1000");
            }
            LookupBinding previous = lookupsByType.computeIfAbsent(model.name(), k -> new TreeMap<>()).put(lookup.by(), lookup);
            if (previous != null) {
                throw new RealmDefinitionException("Two lookups on " + model.name() + "." + lookup.by() + ": " + previous.source() + " and " + lookup.source());
            }
        }
        for (QueryBinding query : queries) {
            TypeModel model = register(models, query.type());
            if (query.maxPageSize() < 1 || query.maxPageSize() > 1000) {
                throw new RealmDefinitionException(query.source() + ": maxPageSize is between 1 and 1000");
            }
            QueryBinding previous = queryByType.put(model.name(), query);
            if (previous != null) {
                throw new RealmDefinitionException("Two queries on " + model.name() + ": " + previous.source() + " and " + query.source());
            }
        }

        Map<String, TypeEntry> entries = new TreeMap<>();
        for (TypeModel model : models.values()) {
            Map<String, LookupBinding> typeLookups = lookupsByType.getOrDefault(model.name(), Map.of());
            if (!typeLookups.containsKey(model.identity())) {
                throw new RealmDefinitionException(model.name() + " has no lookup on its identity " + model.identity()
                        + ": declare a @RealmLookup(by = \"" + model.identity() + "\") method");
            }
            Map<String, LookupBinding> ordered = new LinkedHashMap<>();
            ordered.put(model.identity(), typeLookups.get(model.identity()));
            typeLookups.forEach(ordered::putIfAbsent);
            QueryBinding query = queryByType.get(model.name());
            entries.put(model.name(), new TypeEntry(model, ordered, query,
                    filterOf(model, query, ordered.values()), sortOf(model, ordered.values(), query)));
        }

        for (TypeEntry entry : entries.values()) {
            for (PropertyModel property : entry.model().properties().values()) {
                if (property.reference() != null) {
                    String target = TypeIntrospector.typeName(property.reference().target());
                    if (!entries.containsKey(target)) {
                        throw new RealmDefinitionException(entry.model().name() + "." + property.name() + " references " + target
                                + ", which the realm does not publish: give it a @RealmLookup on its identity");
                    }
                }
            }
        }

        Map<String, VerbBinding> verbMap = new TreeMap<>();
        for (VerbBinding verb : verbs) {
            if (!Pattern.matches("^[A-Za-z][A-Za-z0-9_]{0,127}$", verb.name())) {
                throw new RealmDefinitionException(verb.source() + ": " + verb.name() + " is not a valid verb name");
            }
            if (verb.description() == null || verb.description().isBlank()) {
                throw new RealmDefinitionException(verb.source() + ": a verb needs a description");
            }
            if (verb.approval() == RealmVerb.Required.WHEN || verb.approval() == RealmVerb.Required.DECIDED) {
                throw new RealmDefinitionException(verb.source() + ": approval " + verb.approval().name().toLowerCase(Locale.ROOT)
                        + " is not supported by this version of the library; use never or always");
            }
            if (verb.subject() != null && !entries.containsKey(TypeIntrospector.typeName(verb.subject()))) {
                throw new RealmDefinitionException(verb.source() + ": the subject " + TypeIntrospector.typeName(verb.subject()) + " is not published");
            }
            if (verbMap.put(verb.name(), verb) != null) {
                throw new RealmDefinitionException("Two verbs named " + verb.name());
            }
        }
        return new Realm(settings, entries, verbMap);
    }

    private static TypeModel register(Map<String, TypeModel> models, TypeModel model) {
        TypeModel existing = models.putIfAbsent(model.name(), model);
        if (existing != null && existing.view() != model.view()) {
            throw new RealmDefinitionException("Two view records are named " + model.name() + ": " + existing.view().getName() + " and " + model.view().getName());
        }
        return existing == null ? model : existing;
    }

    /**
     * What a type declares it can filter, which every read of it must then honour (§3.1). A query
     * cannot be filtered after the fact without breaking its pages, so a type with a query declares
     * exactly what the query carries: its criteria record's pairs, or with a {@code Specification}
     * the {@code @RealmType.Filterable} properties. A lookup honours any of it, because the publisher
     * evaluates whatever its method could not carry over the records it returns.
     */
    private static Map<String, Set<Op>> filterOf(TypeModel model, QueryBinding query, Collection<LookupBinding> lookups) {
        boolean annotated = model.properties().values().stream().anyMatch(PropertyModel::filterable);
        if (query != null && query.criteria() != null) {
            if (annotated) {
                throw new RealmDefinitionException(query.source() + ": " + model.name() + "'s filters are declared by its query's criteria record; "
                        + "remove @RealmType.Filterable, which declares them for a Specification");
            }
            return copy(query.criteria());
        }
        Map<String, Set<Op>> filter = new LinkedHashMap<>();
        if (query == null) {
            for (LookupBinding lookup : lookups) {
                if (lookup.criteria() != null) {
                    lookup.criteria().forEach((property, ops) -> filter.computeIfAbsent(property, k -> new LinkedHashSet<>()).addAll(ops));
                }
            }
        }
        for (PropertyModel property : model.properties().values()) {
            if (!property.filterable()) {
                continue;
            }
            String where = model.name() + "." + property.name();
            if (!property.type().isScalar()) {
                throw new RealmDefinitionException(where + ": filtering on a list is not supported by this version of the library");
            }
            Set<Op> ops = resolveOps(property);
            for (Op op : ops) {
                if (!SUPPORTED_OPS.contains(op)) {
                    throw new RealmDefinitionException(where + ": " + op.wire() + " is not supported by this version of the library");
                }
            }
            filter.computeIfAbsent(property.name(), k -> new LinkedHashSet<>()).addAll(ops);
        }
        if (!filter.isEmpty() && query != null && !query.pushesWhere()) {
            throw new RealmDefinitionException(query.source() + ": " + model.name()
                    + " declares filters, but its query cannot take them; give it a criteria record carrying them");
        }
        return filter;
    }

    private static Map<String, Set<Op>> copy(Map<String, Set<Op>> criteria) {
        Map<String, Set<Op>> filter = new LinkedHashMap<>();
        criteria.forEach((property, ops) -> filter.put(property, new LinkedHashSet<>(ops)));
        return filter;
    }

    private static List<String> sortOf(TypeModel model, Collection<LookupBinding> lookups, QueryBinding query) {
        List<String> sort = new ArrayList<>();
        for (PropertyModel property : model.properties().values()) {
            if (property.sortable()) {
                if (!property.type().isScalar()) {
                    throw new RealmDefinitionException(model.name() + "." + property.name() + ": a list cannot be sorted by");
                }
                sort.add(property.name());
            }
        }
        boolean anySorts = (query != null && query.sorts()) || lookups.stream().anyMatch(LookupBinding::sorts);
        if (!sort.isEmpty() && !anySorts) {
            throw new RealmDefinitionException(model.name() + " declares sortable properties, but none of its methods takes a Sort or a Pageable");
        }
        return sort;
    }

    private static void checkSettings(RealmSettings settings) {
        if (settings.getName() == null || !NAME.matcher(settings.getName()).matches()) {
            throw new RealmDefinitionException("embabel.realm.name must match " + NAME.pattern() + ", was " + settings.getName());
        }
        if (settings.getPath() == null || !settings.getPath().startsWith("/") || settings.getPath().length() < 2 || settings.getPath().endsWith("/")) {
            throw new RealmDefinitionException("embabel.realm.path must start with / and not end with one, was " + settings.getPath());
        }
        if (!"ignored".equals(settings.getActingUser().getMode())) {
            throw new RealmDefinitionException("embabel.realm.acting-user.mode " + settings.getActingUser().getMode()
                    + " is not supported by this version of the library; only ignored is");
        }
        if (settings.getMaturity() != null && !MATURITIES.contains(settings.getMaturity())) {
            throw new RealmDefinitionException("embabel.realm.maturity must be one of " + MATURITIES);
        }
    }

    /** The manifest (§3), as a JSON tree. */
    public Map<String, Object> manifest() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("protocol", "1");
        m.put("name", settings.getName());
        putIf(m, "title", settings.getTitle());
        putIf(m, "description", settings.getDescription());
        putIf(m, "version", settings.getVersion());
        putIf(m, "author", settings.getAuthor());
        putIf(m, "url", settings.getUrl());
        putIf(m, "category", settings.getCategory());
        if (settings.getTags() != null && !settings.getTags().isEmpty()) {
            m.put("tags", List.copyOf(settings.getTags()));
        }
        putIf(m, "maturity", settings.getMaturity());
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("schemes", List.of("bearer"));
        auth.put("actingUser", "ignored");
        m.put("auth", auth);
        List<Object> typeList = new ArrayList<>();
        for (TypeEntry entry : types.values()) {
            typeList.add(type(entry));
        }
        m.put("types", typeList);
        if (!verbs.isEmpty()) {
            List<Object> verbList = new ArrayList<>();
            for (VerbBinding verb : verbs.values()) {
                verbList.add(verb(verb));
            }
            m.put("verbs", verbList);
        }
        return m;
    }

    private static Map<String, Object> type(TypeEntry entry) {
        TypeModel model = entry.model();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", model.name());
        putIf(t, "description", model.description());
        t.put("identity", model.identity());
        Map<String, Object> properties = new LinkedHashMap<>();
        for (PropertyModel property : model.properties().values()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", property.type().wire());
            putIf(p, "description", property.description());
            if (property.enumValues() != null) {
                p.put("values", property.enumValues());
            }
            if (property.itemType() != null) {
                p.put("item", Map.of("type", property.itemType().wire()));
            }
            putIf(p, "spine", property.spine());
            if (property.reference() != null) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("type", TypeIntrospector.typeName(property.reference().target()));
                r.put("relationship", property.reference().relationship());
                r.put("direction", property.reference().direction().name().toLowerCase(Locale.ROOT));
                p.put("references", r);
            }
            properties.put(property.name(), p);
        }
        t.put("properties", properties);
        List<Object> lookups = new ArrayList<>();
        for (LookupBinding lookup : entry.lookups().values()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("by", lookup.by());
            l.put("maxKeys", lookup.maxKeys());
            lookups.add(l);
        }
        t.put("lookups", lookups);
        if (!entry.filter().isEmpty()) {
            Map<String, Object> props = new LinkedHashMap<>();
            entry.filter().forEach((name, ops) -> props.put(name, ops.stream().map(Op::wire).toList()));
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("properties", props);
            filter.put("logic", List.of("and"));
            t.put("filter", filter);
        }
        if (!entry.sort().isEmpty()) {
            t.put("sort", entry.sort());
        }
        if (entry.query() != null) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("maxPageSize", entry.query().maxPageSize());
            if (entry.query().paging() == QueryBinding.Paging.PAGE) {
                q.put("total", true);
            }
            t.put("query", q);
        }
        RealmType.Cache cache = model.cache();
        if (cache.value() != RealmType.Scope.USER || cache.ttlSeconds() != 0 || cache.negativeTtlSeconds() != 0 || cache.immutable()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("scope", cache.value().name().toLowerCase(Locale.ROOT));
            if (cache.ttlSeconds() != 0) {
                c.put("ttlSeconds", cache.ttlSeconds());
            }
            if (cache.negativeTtlSeconds() != 0) {
                c.put("negativeTtlSeconds", cache.negativeTtlSeconds());
            }
            if (cache.immutable()) {
                c.put("immutable", true);
            }
            t.put("cache", c);
        }
        return t;
    }

    private static Map<String, Object> verb(VerbBinding verb) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", verb.name());
        v.put("description", verb.description());
        if (verb.subject() != null) {
            v.put("subject", TypeIntrospector.typeName(verb.subject()));
        }
        v.put("input", verb.inputType() == null ? Map.of("type", "object") : VerbSchemas.schema(verb.inputType(), true));
        if (verb.outputType() != null) {
            v.put("output", VerbSchemas.schema(verb.outputType(), false));
        }
        v.put("effect", verb.effect().name().toLowerCase(Locale.ROOT));
        if (verb.idempotent()) {
            v.put("idempotent", true);
        }
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("required", verb.approval().name().toLowerCase(Locale.ROOT));
        if (verb.approvalReason() != null && !verb.approvalReason().isBlank()) {
            approval.put("reason", verb.approvalReason());
        }
        v.put("approval", approval);
        return v;
    }

    private static void putIf(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}
