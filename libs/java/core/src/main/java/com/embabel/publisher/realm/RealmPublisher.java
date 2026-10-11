package com.embabel.publisher.realm;

import com.embabel.publisher.json.JsonCodec;
import com.embabel.publisher.protocol.ErrorCode;
import com.embabel.publisher.protocol.RealmException;
import com.embabel.publisher.value.InvalidValueException;
import com.embabel.publisher.value.Values;
import com.embabel.publisher.where.Op;
import com.embabel.publisher.where.ReferenceEvaluator;
import com.embabel.publisher.where.Where;
import com.embabel.publisher.where.WhereParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serves a {@link Realm}: authenticates the world, then answers the manifest, fetches, queries and
 * verb calls, keeping the protocol's contracts the application's methods cannot know about: the
 * batch contract, canonical keys, declared capabilities, paging and problem details.
 */
public final class RealmPublisher implements RealmEndpoint {

    private static final System.Logger LOG = System.getLogger(RealmPublisher.class.getName());
    private static final String JSON = "application/json";
    private static final String PROBLEM = "application/problem+json";

    private final Realm realm;
    private final JsonCodec codec;
    private final byte[] tokenDigest;
    private final byte[] manifest;
    private final String etag;

    public RealmPublisher(Realm realm, JsonCodec codec) {
        this.realm = realm;
        this.codec = codec;
        String token = realm.settings().getAuth().getToken();
        this.tokenDigest = token == null || token.isBlank() ? null : sha256(token.getBytes(StandardCharsets.UTF_8));
        if (tokenDigest == null) {
            LOG.log(System.Logger.Level.WARNING, "No embabel.realm.auth.token is configured: every call to the publisher will be refused");
        }
        this.manifest = codec.write(realm.manifest());
        this.etag = "\"" + HexFormat.of().formatHex(sha256(manifest), 0, 16) + "\"";
    }

    public Realm realm() {
        return realm;
    }

    @Override
    public Response handle(Request request) {
        try {
            authenticate(request);
            String path = request.subPath().equals("/") ? "" : request.subPath();
            if (path.isEmpty()) {
                requireMethod(request, "GET");
                return manifest(request);
            }
            if (path.equals("/fetch")) {
                requireMethod(request, "POST");
                return json(200, fetch(body(request)));
            }
            if (path.equals("/query")) {
                requireMethod(request, "POST");
                return json(200, query(body(request)));
            }
            if (path.startsWith("/verbs/") && path.indexOf('/', 7) < 0) {
                requireMethod(request, "POST");
                return json(200, verb(path.substring(7), request.body().length == 0 ? Map.of() : body(request)));
            }
            return problem(new RealmException(ErrorCode.UNSUPPORTED, "not an operation this publisher serves"), 404);
        }
        catch (RealmException e) {
            return problem(e, e.code().status());
        }
        catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "The publisher failed answering " + request.method() + " " + request.subPath(), e);
            return problem(new RealmException(ErrorCode.INTERNAL, null), 500);
        }
    }

    /* Both sides hashed first, so the comparison is constant-time and does not leak the token's length. */
    private void authenticate(Request request) {
        String header = request.header().apply("Authorization");
        if (tokenDigest == null || header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                || !MessageDigest.isEqual(tokenDigest, sha256(header.substring(7).trim().getBytes(StandardCharsets.UTF_8)))) {
            throw new RealmException(ErrorCode.UNAUTHENTICATED, null);
        }
    }

    private static void requireMethod(Request request, String method) {
        if (!method.equalsIgnoreCase(request.method())) {
            throw new RealmException(ErrorCode.UNSUPPORTED, request.method() + " is not an operation here; use " + method);
        }
    }

    private Response manifest(Request request) {
        String ifNoneMatch = request.header().apply("If-None-Match");
        if (ifNoneMatch != null && (ifNoneMatch.equals(etag) || ifNoneMatch.equals("W/" + etag))) {
            return new Response(304, null, Map.of("ETag", etag), new byte[0]);
        }
        return new Response(200, JSON, Map.of("ETag", etag), manifest);
    }

    private Map<?, ?> body(Request request) {
        Object tree;
        try {
            tree = codec.read(request.body());
        }
        catch (RuntimeException e) {
            throw RealmException.invalid("the body is not JSON");
        }
        if (!(tree instanceof Map<?, ?> map)) {
            throw RealmException.invalid("the body is a JSON object");
        }
        return map;
    }

    /* ---- fetch (§4.2) ---- */

    private Map<String, Object> fetch(Map<?, ?> body) {
        Realm.TypeEntry entry = typeOf(body);
        String by = string(body, "by", true);
        LookupBinding lookup = entry.lookups().get(by);
        if (lookup == null) {
            throw RealmException.unsupported(entry.model().name() + " has no lookup by " + by);
        }
        if (body.get("limitPerKey") != null) {
            throw RealmException.unsupported("limitPerKey is not declared for this lookup");
        }
        List<String> keys = keys(body.get("keys"));
        if (keys.size() > lookup.maxKeys()) {
            throw new RealmException(ErrorCode.KEY_BOUND, keys.size() + " keys; the lookup takes at most " + lookup.maxKeys());
        }
        Where where = checkedWhere(entry, body.get("where"));
        List<SortKey> sort = checkedSort(entry, body.get("sort"));

        PropertyModel property = entry.model().property(by);
        Set<String> requested = new LinkedHashSet<>(keys);
        Set<String> missing = new LinkedHashSet<>();
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String key : requested) {
            Values.parseKey(key, property.type(), property.javaType()).ifPresentOrElse(v -> parsed.put(key, v), () -> missing.add(key));
        }

        List<Map<String, Object>> records = new ArrayList<>();
        Set<String> failed = new LinkedHashSet<>();
        boolean sorted = false;
        if (!parsed.isEmpty()) {
            Collection<?> views;
            try {
                views = lookup.invoker().fetch(new ArrayList<>(parsed.values()), lookup.pushesWhere() ? where : null,
                        lookup.sorts() ? sort : List.of());
            }
            catch (RealmException e) {
                throw e;
            }
            catch (RuntimeException e) {
                /* Could not ask is not asked-and-found-nothing: every key is failed, never missing (§3.3). */
                LOG.log(System.Logger.Level.ERROR, "Lookup " + lookup.source() + " failed", e);
                failed.addAll(parsed.keySet());
                views = List.of();
            }
            sorted = lookup.sorts() && !sort.isEmpty();
            ReferenceEvaluator evaluator = evaluator(entry);
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Object view : views) {
                Map<String, Object> record = RecordWriter.write(view, entry.model());
                Object value = record.get(by);
                String key = value == null ? null : Values.canonicalKeyOfWire(value, property.type());
                if (key == null || !parsed.containsKey(key)) {
                    LOG.log(System.Logger.Level.WARNING, lookup.source() + " returned a record whose " + by + " is not a requested key; dropped");
                    continue;
                }
                if (!lookup.pushesWhere() && !evaluator.test(where, record)) {
                    continue;
                }
                counts.merge(key, 1, Integer::sum);
                records.add(record);
            }
            if (by.equals(entry.model().identity())) {
                /* An identity lookup returns at most one record per key; more is the publisher unable to say which. */
                for (Map.Entry<String, Integer> count : counts.entrySet()) {
                    if (count.getValue() > 1) {
                        failed.add(count.getKey());
                    }
                }
                records.removeIf(r -> failed.contains(Values.canonicalKeyOfWire(r.get(by), property.type())));
            }
            for (String key : parsed.keySet()) {
                if (!counts.containsKey(key) && !failed.contains(key)) {
                    missing.add(key);
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", records);
        if (!missing.isEmpty()) {
            response.put("missing", List.copyOf(missing));
        }
        if (!failed.isEmpty()) {
            response.put("failed", List.copyOf(failed));
        }
        response.put("applied", true);
        if (!sort.isEmpty()) {
            response.put("sorted", sorted);
        }
        return response;
    }

    private static List<String> keys(Object tree) {
        if (!(tree instanceof List<?> list) || list.isEmpty()) {
            throw RealmException.invalid("keys is a non-empty array");
        }
        List<String> keys = new ArrayList<>(list.size());
        for (Object key : list) {
            if (!(key instanceof String s) || s.isEmpty()) {
                throw RealmException.invalid("keys are non-empty strings, in canonical form");
            }
            keys.add(s);
        }
        return keys;
    }

    /* ---- query (§4.3) ---- */

    private Map<String, Object> query(Map<?, ?> body) {
        Realm.TypeEntry entry = typeOf(body);
        QueryBinding query = entry.query();
        if (query == null) {
            throw RealmException.unsupported(entry.model().name() + " cannot be listed; reach it through a lookup");
        }
        if (body.get("search") != null) {
            throw RealmException.unsupported("search is not declared for " + entry.model().name());
        }
        Where where = checkedWhere(entry, body.get("where"));
        List<SortKey> sort = checkedSort(entry, body.get("sort"));
        Integer limit = positive(body, "limit");
        Integer requestedSize = positive(body, "pageSize");

        Cursor cursor;
        Object cursorTree = body.get("cursor");
        if (cursorTree == null) {
            int size = Math.min(requestedSize == null ? query.maxPageSize() : requestedSize, query.maxPageSize());
            cursor = new Cursor(0, size);
        }
        else if (cursorTree instanceof String s) {
            cursor = Cursor.decode(s);
            if (cursor.pageSize() > query.maxPageSize()) {
                throw RealmException.invalid("not a cursor this publisher issued");
            }
        }
        else {
            throw RealmException.invalid("cursor is a string or null");
        }

        long offset = (long) cursor.page() * cursor.pageSize();
        List<Map<String, Object>> records = new ArrayList<>();
        Map<String, Object> response = new LinkedHashMap<>();
        boolean more;
        Long total = null;
        if (limit != null && offset >= limit) {
            more = false;
        }
        else {
            QueryBinding.Page page = query.invoker().query(where, query.sorts() ? sort : List.of(), cursor.page(), cursor.pageSize());
            List<?> items = page.items();
            more = page.hasNext();
            total = page.total();
            if (query.paging() == QueryBinding.Paging.NONE && items.size() > cursor.pageSize()) {
                items = items.subList(0, cursor.pageSize());
                more = true;
            }
            for (Object view : items) {
                records.add(RecordWriter.write(view, entry.model()));
            }
            if (limit != null && offset + records.size() >= limit) {
                /* The limit ends the walk (§4.3): what is beyond it was not asked for, so nothing is cut short. */
                records = records.subList(0, (int) (limit - offset));
                more = false;
            }
        }
        boolean pages = query.paging() == QueryBinding.Paging.PAGE || query.paging() == QueryBinding.Paging.SLICE;
        response.put("records", records);
        response.put("applied", true);
        if (!sort.isEmpty()) {
            response.put("sorted", query.sorts());
        }
        response.put("next", more && pages ? new Cursor(cursor.page() + 1, cursor.pageSize()).encode() : null);
        if (query.paging() == QueryBinding.Paging.PAGE) {
            response.put("total", total == null ? 0L : total);
        }
        if (more && !pages) {
            response.put("truncated", Map.of("reason", "More records match than one page holds, and this query does not page."));
        }
        return response;
    }

    private static Integer positive(Map<?, ?> body, String name) {
        Object value = body.get(name);
        if (value == null) {
            return null;
        }
        try {
            Object parsed = Values.fromWire(value, Integer.class);
            if ((Integer) parsed >= 1) {
                return (Integer) parsed;
            }
        }
        catch (InvalidValueException e) {
            /* Refused below. */
        }
        throw RealmException.invalid(name + " is a positive integer");
    }

    /* ---- verbs (§5) ---- */

    private Map<String, Object> verb(String name, Object body) {
        VerbBinding verb = realm.verb(name);
        if (verb == null) {
            throw new RealmException(ErrorCode.UNKNOWN_VERB, name);
        }
        Object input = verb.inputType() == null ? null : VerbSchemas.bind(body, verb.inputType());
        Object output;
        try {
            output = verb.invoker().invoke(input);
        }
        catch (RealmException e) {
            throw e;
        }
        catch (RuntimeException e) {
            String refusal = refusal(e);
            if (refusal != null) {
                throw RealmException.invalid(refusal);
            }
            throw e;
        }
        return output == null ? new LinkedHashMap<>() : RecordWriter.writeRecord(output);
    }

    /**
     * The application refusing the input, as opposed to failing: an {@link IllegalArgumentException}
     * or a Bean Validation {@code ConstraintViolationException}, however deep a transaction wrapped it.
     */
    private static String refusal(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof IllegalArgumentException) {
                return t.getMessage() == null ? "the input was refused" : t.getMessage();
            }
            String name = t.getClass().getName();
            if (name.equals("jakarta.validation.ConstraintViolationException") || name.equals("javax.validation.ConstraintViolationException")) {
                return violations(t);
            }
        }
        return null;
    }

    /**
     * {@code property: message} for each violation, read reflectively so the core needs no
     * validation API. The exception's own message names the application's classes, which a
     * world has no business seeing.
     */
    private static String violations(Throwable e) {
        try {
            List<String> parts = new ArrayList<>();
            for (Object violation : (Iterable<?>) e.getClass().getMethod("getConstraintViolations").invoke(e)) {
                Object path = violation.getClass().getMethod("getPropertyPath").invoke(violation);
                String message = String.valueOf(violation.getClass().getMethod("getMessage").invoke(violation));
                parts.add(path + ": " + (message.startsWith("{") ? "is not valid" : message));
            }
            return parts.isEmpty() ? "the input was refused" : String.join("; ", parts);
        }
        catch (ReflectiveOperationException | RuntimeException ignored) {
            return "the input was refused";
        }
    }

    /* ---- shared request checks ---- */

    private Realm.TypeEntry typeOf(Map<?, ?> body) {
        String type = string(body, "type", true);
        Realm.TypeEntry entry = realm.type(type);
        if (entry == null) {
            throw new RealmException(ErrorCode.UNKNOWN_TYPE, type);
        }
        return entry;
    }

    private static String string(Map<?, ?> body, String name, boolean required) {
        Object value = body.get(name);
        if (value instanceof String s && !s.isEmpty()) {
            return s;
        }
        if (value == null && !required) {
            return null;
        }
        throw RealmException.invalid(name + " is a non-empty string");
    }

    /** Parses a {@code where} and refuses anything the type did not declare, before any method is called. */
    private static Where checkedWhere(Realm.TypeEntry entry, Object tree) {
        Where where = WhereParser.parse(tree);
        if (where != null) {
            check(entry, where);
        }
        return where;
    }

    private static void check(Realm.TypeEntry entry, Where where) {
        if (where instanceof Where.And and) {
            for (Where operand : and.operands()) {
                check(entry, operand);
            }
            return;
        }
        if (where instanceof Where.Or) {
            throw RealmException.unsupported("or is not declared");
        }
        if (where instanceof Where.Not) {
            throw RealmException.unsupported("not is not declared");
        }
        if (where instanceof Where.Exists) {
            throw RealmException.unsupported("exists is not declared");
        }
        Where.Comparison c = (Where.Comparison) where;
        Set<Op> ops = entry.filter().get(c.property());
        if (ops == null || !ops.contains(c.op())) {
            throw RealmException.unsupported(c.op().wire() + " on " + entry.model().name() + "." + c.property() + " is not declared");
        }
        PropertyModel property = entry.model().property(c.property());
        try {
            if (c.op().takesArray()) {
                for (Object v : (List<?>) c.value()) {
                    if (v != null) {
                        Values.comparable(v, property.type());
                    }
                }
            }
            else if (c.op().takesValue() && c.value() != null) {
                Values.comparable(c.value(), property.type());
            }
            if (property.type().isOrdered() && c.op().takesValue() && c.op() != Op.EQ && c.op() != Op.IN) {
                /* A range bound beyond what the property's Java type can hold cannot be applied exactly. */
                for (Object v : c.value() instanceof List<?> l ? l : java.util.Collections.singletonList(c.value())) {
                    if (v != null) {
                        Values.fromWire(v, property.javaType());
                    }
                }
            }
        }
        catch (InvalidValueException e) {
            throw RealmException.invalid(c.property() + ": " + e.getMessage());
        }
    }

    private static List<SortKey> checkedSort(Realm.TypeEntry entry, Object tree) {
        if (tree == null) {
            return List.of();
        }
        if (!(tree instanceof List<?> list)) {
            throw RealmException.invalid("sort is an array");
        }
        List<SortKey> sort = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> key) || !(key.get("property") instanceof String property)) {
                throw RealmException.invalid("a sort key names a property");
            }
            Object direction = key.get("direction");
            if (direction != null && !"asc".equals(direction) && !"desc".equals(direction)) {
                throw RealmException.invalid("direction is asc or desc");
            }
            if (!entry.sort().contains(property)) {
                throw RealmException.unsupported("sorting " + entry.model().name() + " by " + property + " is not declared");
            }
            sort.add(new SortKey(property, "desc".equals(direction)));
        }
        return sort;
    }

    private static ReferenceEvaluator evaluator(Realm.TypeEntry entry) {
        return new ReferenceEvaluator(entry.model()::typeOf, entry.model()::itemTypeOf);
    }

    /* ---- responses ---- */

    private Response json(int status, Object tree) {
        return new Response(status, JSON, Map.of(), codec.write(tree));
    }

    private Response problem(RealmException e, int status) {
        Map<String, String> headers = e.code() == ErrorCode.UNAUTHENTICATED ? Map.of("WWW-Authenticate", "Bearer") : Map.of();
        Map<String, Object> problem = e.toProblem();
        problem.put("status", status);
        return new Response(status, PROBLEM, headers, codec.write(problem));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
