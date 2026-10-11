# Realm Publishing Protocol

> **Status: proposal.** Nothing here is implemented yet. This document fixes the shape before any
> code exists, because a protocol is hard to change after it ships. Section 10 summarizes the
> [Spring Boot starter](../libs/java/spring-boot-starter/README.md), the planned reference
> implementation, and §14 collects futures. Both are informative; the rest is the contract.
>
> Licensed under the [Apache License 2.0](../LICENSE): anyone may implement either side of it.

An application that already holds business data should not need a realm author to work out its
API. Today every connector a world has reaches into a system that does not know a world exists,
and a realm spends most of its YAML reverse-engineering what that system does: how it pages,
which filters it can apply itself, which key it echoes back, how expensive a call is, and how long
an answer stays true. `learn_source` exists because the other side will not say.

This protocol is the other side saying it. An application that speaks it — a **publisher** —
publishes what it chooses to expose, in the shapes it chooses, with its own answers to batching,
filtering, paging, cost, caching and identity. A **world** that is given the publisher's URL reads
that declaration and installs the publisher as a realm. Nobody writes the realm.

Three things follow, and they are the reason for the protocol:

- **The world side is one URL.** The owner pastes the publisher's URL into the world, supplies a
  credential if the publisher asks for one, and is done. Types, joins, spine bindings, verbs and
  cache policy all come from the publisher.
- **The world sees what the programmers decided it should see.** A publisher exposes its
  application's own types and operations, through the same layer its user interface uses, with
  the same authorization. The world cannot reach anything the publisher did not declare.
- **Identity bridging can live in code.** A publisher can resolve a spine key — an email, a
  domain, a customer account — to its own records in its own code, where the knowledge of how
  its ids relate to the world's actually lives (§6).

The protocol is language-neutral: HTTP and JSON, with nothing that assumes the publisher's language,
framework or data store. The reference publisher is a Spring Boot starter. The conformance kit
(§11) is what makes a publisher in any other language trustworthy, and the adoption skill every
publisher library ships (§11.1) is what makes one easy to drop in.

---

## 1. Roles and direction

| Role | Who | Does |
|---|---|---|
| **Publisher** | The application | Serves the protocol. Never needs to know a world exists. |
| **World** | An Embabel world, or any system that implements this side of the protocol | Is given the publisher's URL, reads its manifest, calls it at query time. |

**The world calls the publisher. The publisher never has to call the world.** An existing application
should not have to discover, register with, or hold a credential for a world in order to be useful
to one. Every publisher-to-world direction in this document — push delivery (§7.3) and the tunnel (§8) — is optional, off unless
the application's owner configures it.

A publisher may be installed into many worlds. It does not know or care how many.

## 2. Transport and conventions

- HTTPS. Plain HTTP is accepted only for a publisher on the world's own network, and the world says so
  when installing it.
- Request and response bodies are JSON (`application/json`, UTF-8).
- Every operation lives at a fixed path relative to the **publisher URL** — the URL the owner was
  given. Nothing in the protocol is located by a URL the manifest names, so a manifest cannot
  redirect the world somewhere else.

| Operation | Method and path | § |
|---|---|---|
| Manifest | `GET {publisher}` | 3 |
| Realm documents and skills | `GET {publisher}/{about}`, `GET {publisher}/skills/{name}/…` | 3.7 |
| Fetch by keys | `POST {publisher}/fetch` | 4.2 |
| Query | `POST {publisher}/query` | 4.3 |
| Aggregate | `POST {publisher}/aggregate` | 4.4 |
| Invoke a verb | `POST {publisher}/verbs/{name}` | 5 |
| Changes | `GET {publisher}/changes` | 7.1 |
| Events | `GET {publisher}/events` | 7.2 |
| Subscribe to push delivery | `POST {publisher}/subscriptions` | 7.3 |

- Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details with a `code` from
  the table in §4.6. The world classifies by `code` and HTTP status, never by message text.
- Unknown fields are ignored on both sides. Evolution within a protocol version is additive.

### 2.1 Values

Records join on keys, and keys join only if both sides write them identically. So the value rules
are strict where a lenient rule would quietly break a join.

| Type | JSON form |
|---|---|
| `string` | string |
| `boolean` | `true` / `false` |
| `integer` | number when it fits in ±2^53, otherwise a string of decimal digits |
| `decimal` | **string** in plain decimal notation (`"1234.50"`), never exponent notation, never a JSON number |
| `date` | `"2026-10-10"` |
| `datetime` | RFC 3339 with an offset (`"2026-10-10T09:30:00Z"`) |
| `duration` | ISO 8601 (`"PT15M"`) |
| `enum` | string, one of the declared values |
| `list` | array of one scalar type, or of parts (§3.5) |
| `object` | a nested object with declared properties — a value (§3.5) |

**Keys are always strings on the wire**, in the canonical form of their declared type: an integer
key `42` is sent and echoed as `"42"`, never `42.0` or `"4.2E1"`. A publisher that receives a key it
cannot parse as the declared type treats it as missing (§4.2), not as an error.

`null` means "this record has no value here". A property absent from a record means the same. The
two are never used to signal anything else.

### 2.2 Turning a publisher off

Every publisher library honours one switch that turns all publication off:

```shell
EMBABEL_REALM_ENABLED=false
```

It is an environment variable because that works for every language and every way of deploying
an application. A library may also accept the same setting through its stack's own configuration,
under the name that stack's conventions give it (`embabel.realm.enabled` in Spring Boot, which
binds the variable without extra code). The variable wins over anything a configuration file says,
so an operator can always turn a publisher off without a build. The default is on, and a library
with nothing declared publishes an empty realm.

When publication is off:
- Every path of the protocol, the manifest and an index included, answers `503` with code
  `PUBLISHER_DISABLED` and nothing else. No types, names or counts are revealed.
- The publisher makes no outbound call: no push delivery and no subscription pings.
- It does no background work for the protocol, and records nothing for it. An event published while
  off is not kept for later. Off means nothing leaves the application and nothing accumulates to
  leave it later.

A world that receives `PUBLISHER_DISABLED`:
- marks the realm **disabled**, not failed, and says so to its owner;
- stops serving anything it cached from that publisher, because a publisher turned off for a reason
  should not keep answering through a cache;
- never treats the answer as "no records", so nothing is negatively cached;
- checks the manifest again only occasionally, honouring `Retry-After`.

When the publisher comes back, the world revalidates the manifest and resumes. Event cursors from
before are answered `410 Gone`, and the world reports the gap (§7.2).

## 3. The manifest

`GET {publisher}` returns the manifest. It is the whole of what the world knows about the publisher,
and the world builds the realm from it alone. It carries an `ETag`; the world revalidates with
`If-None-Match` and rebuilds the realm when it changes.

```json
{
  "protocol": "1",
  "name": "billing",
  "title": "Billing",
  "description": "Customers, invoices and payments from the billing system.",
  "auth": { "schemes": ["bearer"], "actingUser": "required" },
  "types": [ ... ],
  "verbs": [ ... ],
  "changes": { "supported": true },
  "events": [ ... ],
  "eventRetention": "P7D",
  "delivery": { "push": true }
}
```

| Field | Meaning |
|---|---|
| `protocol` | The protocol major version. A world refuses a version it does not speak, and says which. |
| `name` | The realm name the publisher asks for. The world prefixes it (`publisher-billing`) and resolves collisions; the publisher does not choose the final name. |
| `title`, `description` | Shown to the owner on install and to models that read the realm brief. Write them for both. |
| `version`, `about`, `skills`, … | Realm metadata and skills (§3.7). |
| `auth` | §3.4. |
| `types` | §3.1. |
| `verbs` | §5. |
| `agents` | Proposed agents (§5.3). |
| `changes` | Whether `GET /changes` exists (§7.1). |
| `events`, `eventRetention` | The events the publisher publishes, and how long it keeps them (§7.2). |
| `delivery` | Whether the publisher can push events and changes to a world that subscribes (§7.3). |

### 3.1 Types

A type is what the publisher exposes, **not its storage model**. In a layered application it is the
shape the service layer returns — a DTO or projection — and that is deliberate: the publisher decides
what the world sees, at the layer where it already decides what its own UI sees.

```json
{
  "name": "Customer",
  "description": "A company that buys from us.",
  "identity": "id",
  "properties": {
    "id":      { "type": "integer" },
    "name":    { "type": "string" },
    "website": { "type": "string", "spine": "Organization" },
    "billingEmail": { "type": "string", "spine": "Person" },
    "tier":    { "type": "enum", "values": ["free", "pro", "enterprise"] },
    "balance": { "type": "decimal", "description": "Outstanding, in the account currency." }
  },
  "lookups": [
    { "by": "id", "maxKeys": 500 },
    { "by": "website", "maxKeys": 200 },
    { "by": "billingEmail", "maxKeys": 200 }
  ],
  "filter": {
    "properties": {
      "tier":    ["eq", "ne", "in", "notIn"],
      "balance": ["eq", "gt", "gte", "lt", "lte", "between", "isNull"],
      "name":    ["eq", "ieq", "prefix", "iprefix", "icontains"]
    },
    "logic": ["and", "or", "not"],
    "exists": ["Invoice.customerId"]
  },
  "sort": ["name", "balance"],
  "project": true,
  "query": { "maxPageSize": 200, "total": true, "search": true },
  "aggregate": {
    "groupBy": ["tier"],
    "measures": { "count": true, "sum": ["balance"], "avg": ["balance"], "min": ["balance"], "max": ["balance"] }
  },
  "cache": { "scope": "user", "ttlSeconds": 300, "negativeTtlSeconds": 3600 },
  "cost": { "maxRequestsPerSecond": 20, "maxConcurrency": 4 }
}
```

| Field | Meaning |
|---|---|
| `name` | The label in the world, before the world applies the realm's namespace. |
| `identity` | The property that identifies a record of this type. It must also be a lookup. |
| `properties` | Name → `{type, description?, spine?, values?, item?, properties?, references?}`. `item` is the element type of a `list`; `properties` are an `object`'s (§3.5). Only declared properties are read from records; anything else a record carries is dropped. |
| `lookups` | The properties the publisher can fetch by, in batches (§4.2). Relationships (§3.2) and identity bridging (§6) are lookups too. A lookup that can cap its records per key adds `"limitPerKey": true`. |
| `filter` | What the publisher can evaluate itself, in fetches, queries and aggregates alike (§4.1). Absent means nothing. |
| `sort` | Properties the publisher can order by. |
| `project` | The publisher honours `fields` and returns only the properties asked for (§4.1). |
| `query` | Present if the type can be listed without keys (§4.3). Absent means it cannot; the type is reached only through lookups. |
| `aggregate` | Reductions the publisher can compute itself (§4.4). |
| `cache` | §3.3. |
| `cost` | Pacing the world must observe across all its calls to this type. |

**A lookup is the one join primitive.** Fetch by identity, follow a foreign key, resolve a spine
key to records: each is "give me the records whose property `P` is one of these values", and each
is declared once, with its own batch limit.

### 3.2 Relationships

A relationship is a foreign key the publisher exposes, plus a lookup that makes it traversable.

```json
{
  "name": "Invoice",
  "identity": "number",
  "properties": {
    "number":     { "type": "string" },
    "customerId": { "type": "integer", "references": { "type": "Customer", "relationship": "BILLED", "direction": "in" } },
    "amount":     { "type": "decimal" },
    "dueDate":    { "type": "date" },
    "status":     { "type": "enum", "values": ["draft", "open", "paid", "void"] }
  },
  "lookups": [
    { "by": "number", "maxKeys": 500 },
    { "by": "customerId", "maxKeys": 100 }
  ]
}
```

`references` says `Invoice.customerId` holds a `Customer`'s identity. `direction: "in"` makes the
edge `(:Customer)-[:BILLED]->(:Invoice)`; `"out"` would point it from the invoice. The world
traverses it both ways:

- From a customer to its invoices, by the lookup on `customerId`. A reference with no lookup on its
  property is traversable only from the invoice side, and the world says so when it installs.
- From an invoice to its customer, by the lookup on `Customer.id`.

The referenced type must be in the same manifest, or in a sibling realm of the same application (§3.6).
A publisher never names any other realm's types; reaching across applications is what spines are for (§6).

### 3.3 Caching

The publisher knows how long its answers stay true. The world does not, and every guess it makes is
wrong for somebody.

| Field | Meaning | Default |
|---|---|---|
| `scope` | `shared`: one answer for every user of the world. `user`: the answer depends on the acting user (§3.4), and the world caches per user or not at all. | `user` |
| `ttlSeconds` | How long a fetched record may be reused. `0` means never. | `0` |
| `negativeTtlSeconds` | How long "no record for this key" may be reused. | `0` |
| `immutable` | Records never change once they exist. Reference data. | `false` |

**The default scope is `user` because the safe mistake is a cache miss.** A publisher that declares
`shared` is stating that its authorization does not vary the answer for this type. A world that has
the changes feed (§7.1) may hold records longer than `ttlSeconds` and invalidate on change instead.

A **failed** fetch is never negatively cached. "Could not ask" must not become "asked, and there was
nothing".

### 3.4 Authentication and the acting user

Two separate questions: whether the *world* may call the publisher, and *on whose behalf*.

**The world authenticates as a client of the publisher.** `auth.schemes` lists what the publisher
accepts: `bearer`, `basic`, `mtls` or `none`. The owner supplies the credential when installing,
and the world keeps it in its own secret store. It never appears in a realm file.

**The acting user travels separately**, in an `Embabel-Acting-User` header on every operation: a
signed JWT the world issues, carrying the world user's subject and verified email. The world
publishes its signing keys at a JWKS URL the publisher is configured with. The publisher maps that
identity onto its own principal, using its own rules, and applies its own authorization — the same
as it would for that person in its own UI.

| `auth.actingUser` | Meaning |
|---|---|
| `required` | Every call must carry an acting user. Calls that cannot (a scheduled job with no user) are refused by the publisher, and the world does not attempt them. |
| `optional` | The publisher uses the acting user when present and its service identity otherwise. |
| `ignored` | The publisher answers the same for everyone. Implies `cache.scope: shared` is safe. |

The publisher is the authority on what a user may see. The world never filters a publisher's answer
for authorization; it only refrains from sharing an answer the publisher said was per-user.

### 3.5 Nested data: values and parts

Application data is rarely flat. An invoice has a billing address and a list of lines. These are two
different kinds of nesting, the distinction domain-driven design draws, and the protocol keeps them
apart because the world treats them differently.

**A value** has no identity of its own. An address is the invoice's address, and two invoices with
the same address do not share anything. It is declared as an `object` property:

```json
"billingAddress": { "type": "object", "properties": {
  "line1":    { "type": "string" },
  "city":     { "type": "string" },
  "postcode": { "type": "string" } } }
```

On the wire it is a nested JSON object. In the world it becomes flat properties on the owning node,
named by joining the path in camel case (`billingAddressPostcode`), because graph properties are
scalars. Filters, sorts and `fields` address it by path: `billingAddress.postcode`. A property inside
a value can carry `spine`, so an invoice's postcode can attach to the place it names. Values may
contain values, to a depth of 3, but not lists of values. A list of things that each have fields is a
list of parts.

**A part** has an identity, but only within its owner. Line 3 of `INV-1001` exists only as part of that
invoice, and it is created, changed and deleted with it. Parts are an aggregate's members. Each part
becomes a **type and a node** in the world, so it can be matched, filtered, joined and counted like any
other type:

```json
{
  "name": "InvoiceLine",
  "description": "One line of an invoice.",
  "partOf": { "type": "Invoice", "property": "lines", "relationship": "HAS_LINE", "ownerKey": "invoiceNumber" },
  "identity": "lineNo",
  "properties": {
    "lineNo":        { "type": "integer" },
    "invoiceNumber": { "type": "string" },
    "sku":           { "type": "string", "spine": "Product" },
    "quantity":      { "type": "integer" },
    "amount":        { "type": "decimal" }
  },
  "lookups": [{ "by": "sku", "maxKeys": 200 }, { "by": "invoiceNumber", "maxKeys": 200 }],
  "filter": { "properties": { "sku": ["eq", "in"], "amount": ["gt", "gte", "lt", "lte"] } },
  "aggregate": { "groupBy": ["sku"], "measures": { "count": true, "sum": ["quantity", "amount"] } }
}
```

The owner declares the parts as a list property:

```json
"lines": { "type": "list", "item": { "part": "InvoiceLine" } }
```

| `partOf` | Meaning |
|---|---|
| `type`, `property` | The owning type, and the property on it that holds the parts. |
| `relationship` | The edge the world draws from owner to part: `(:Invoice)-[:HAS_LINE]->(:InvoiceLine)`. |
| `ownerKey` | The part's property holding its owner's identity. A part returned outside its owner — by its own lookup or query — always carries it, so the world can attach it. |

A part is reachable in two ways, and the world uses whichever the query makes cheaper:

- **Inside its owner.** Fetching invoices with `"fields": ["number", "lines"]` returns each invoice
  with its lines embedded. `"lines.sku"` returns them with only that property, plus `lineNo`. Lines
  are not returned unless `fields` asks for them, so an invoice listing never pays for its lines.
- **On its own.** A part can declare lookups, a query and aggregates like any type. "Which invoices
  contain SKU X?" is a lookup on `InvoiceLine.sku` that returns lines carrying `invoiceNumber`, with
  no invoice scan. "Units sold per SKU" is an aggregate over lines, computed in the publisher.

A part's identity is its owner's identity plus its own. It has no lookup by its own identity alone,
because `lineNo: 3` means nothing without an invoice. A change to a part is reported as a change to
its owner (§7.1), because the owner is the unit that changes. Parts may own parts — an order's
shipments, a shipment's packages — to a depth of 3.

**Filtering through parts.** `exists` (§4.1) takes a part property in place of a referencing type:

```json
{ "exists": { "part": "lines", "where": { "property": "sku", "op": "in", "value": ["SKU-1", "SKU-7"] } } }
```

On an `Invoice` read this keeps the invoices with a line for either SKU, evaluated by the publisher in
one query. The capability is declared as `"lines"` in the owner's `filter.exists`.

The test for choosing between a value, a part and a referenced type is the same as the spine test in
[VIRTUAL_CYPHER.md §5.4.2](https://github.com/embabel-worlds/realm-spec/blob/main/VIRTUAL_CYPHER.md#542-an-identity-is-a-spine-a-record-is-a-parent-label),
applied inside one application:
- If it has no identity, it is a **value**.
- If it has an identity only inside its owner, and lives and dies with it, it is a **part**.
- If something else refers to it independently, it is a **type** with a reference (§3.2).

### 3.6 Several realms from one application

One application may serve several realms. An ERP's billing, inventory and HR are different
realms to a world owner, who may want only one. A finance view and a sales view of the same data are
different realms, each behind its own credential. Each realm is a complete publisher at its own
URL — its own manifest, credential, cache, events and subscriptions — so nothing else in this protocol
changes.

An application with several realms may serve an **index** at a root URL:

```http
GET https://erp.example.com/embabel
```

```json
{
  "protocol": "1",
  "application": "Acme ERP",
  "realms": [
    { "name": "billing",   "title": "Billing",   "url": "https://erp.example.com/embabel/billing",
      "description": "Customers, invoices, payments and refunds." },
    { "name": "inventory", "title": "Inventory", "url": "https://erp.example.com/embabel/inventory",
      "description": "Products, stock levels and warehouses." }
  ]
}
```

A response with `realms` and no `types` is an index, not a manifest. Given an index URL, the world
lists the realms and the owner chooses which to install. Given a realm's own URL, the world installs
that realm. The world side is still one URL. Every realm URL in an index must have the index's
origin, so an index cannot point a world at another application.

**Sibling references.** Realms from one index share the application's identities, so one may
reference another's type directly:

```json
"customerId": { "type": "integer", "references": { "realm": "crm", "type": "Customer", "relationship": "BILLED", "direction": "in" } }
```

A sibling reference is allowed only between realms of the same index. If the world installs one realm
without its sibling, the reference is inert, and the world reports it as it does a spine it does
not have. Anything outside the application is reached through spines (§6), never by raw id.

The index is also the answer to large applications. A manifest with hundreds of types is better
split into realms an owner can choose between, than paged.

### 3.7 Realm metadata and skills

A realm is more than its types. An owner choosing what to install, and a model deciding how to use
what was installed, both need to know what the realm is for and how it is meant to be used. The
manifest carries that at the top level, in the same terms as a git realm's `realm.yml`
([README](https://github.com/embabel-worlds/realm-spec/blob/main/README.md#realmyml)), so a publisher's realm and an authored one are described alike:

```json
{
  "protocol": "1",
  "name": "billing",
  "title": "Billing",
  "description": "Customers, invoices, payments and refunds from the billing system.",
  "version": "4.2.0",
  "author": "Acme Finance Engineering",
  "url": "https://wiki.acme.example/billing",
  "icon": "icon.svg",
  "category": "finance",
  "tags": ["invoicing", "payments"],
  "maturity": "beta",
  "about": "about.md",
  "skills": [
    { "name": "collections", "description": "Chase overdue invoices: who to remind, when to escalate, when a refund needs approval.",
      "files": ["references/escalation.md"], "digest": "sha256:9f2c…" }
  ],
  "types": [ ... ]
}
```

| Field | Meaning |
|---|---|
| `version`, `author`, `url`, `category`, `tags`, `maturity` | As in `realm.yml`. `maturity` is the publisher's own readiness claim; absent is not a claim of being finished. |
| `icon` | A path relative to the publisher URL, served from it. |
| `about` | A path to a Markdown document: what the realm holds, what its numbers mean, and what it does not cover. It is shown to the owner on install and is part of the realm's brief for models. Keep it a description and a pointer; how-to belongs in skills. |
| `skills` | Skills the realm brings, below. |

The index of §3.6 repeats `title`, `description`, `icon`, `category` and `maturity` for each realm, so
an owner can choose between realms without fetching every manifest.

**Skills.** A publisher can ship [Agent Skills](https://agentskills.io/specification): instructions a
model activates when a user's request matches them, at no cost otherwise. They are the right place
for what the developers know about using their own application well. Examples: which verb to try
before which, what "overdue" means in this business, when a refund will need approval and how to say
so.

```http
GET {publisher}/skills/{name}/SKILL.md
GET {publisher}/skills/{name}/references/{file}
GET {publisher}/skills/{name}/assets/{file}
```

- `SKILL.md` is a standard Agent Skills file: YAML front matter with `name` and `description`, then
  Markdown instructions. `references/` and `assets/` hold files the instructions point to. The
  manifest lists the files under each skill as `files`, so the world fetches exactly those.
- `digest` covers the whole skill. The world refetches a skill only when its digest changes.
- A skill arrives with its realm and leaves with it, like any realm's skills. An agent scoped to the
  realm gets its skills, and one that is not scoped to it does not.
- Instructions name the realm's types, verbs and events by their manifest names. The world checks
  those names on install and reports a skill that mentions a verb the manifest does not have, rather
  than letting a model try to call it.
- **No `scripts/`.** A publisher's skill is instructions and reference material. A script would be
  code the application ships into the world to run, which needs a sandbox, an approval and a
  signature to trust. That is a future (§14.6), not version 1. A skill that declares scripts is
  installed without them, and the world says so.

## 4. Reading

The publisher sits next to its data, with indexes, a query planner and its own authorization. Every
filter, ordering, limit and reduction it evaluates is a row that never crosses the wire and never
has to be evaluated twice. So the protocol is built to push down **as much of a query as the
publisher can take**, and to make sure that whatever it takes is taken correctly.

### 4.1 Pushdown

**Expressions.** Fetches, queries and aggregates all carry an optional `where`, an expression tree:

```json
{ "and": [
  { "property": "status", "op": "in", "value": ["open", "draft"] },
  { "or": [
    { "property": "amount",  "op": "gt", "value": "1000" },
    { "property": "dueDate", "op": "lt", "value": "2026-10-01" }
  ] },
  { "not": { "property": "customerId", "op": "isNull" } }
] }
```

| Operator | Value | Applies to |
|---|---|---|
| `eq`, `ne` | one value | every scalar type |
| `in`, `notIn` | array | every scalar type |
| `gt`, `gte`, `lt`, `lte` | one value | `integer`, `decimal`, `date`, `datetime`, `duration` |
| `between` | `[low, high]`, both inclusive | as for `gt` |
| `isNull`, `isNotNull` | none | every type |
| `prefix`, `contains` | string | `string` |
| `ieq`, `iprefix`, `icontains` | string | `string`, compared after Unicode simple case folding |
| `has`, `hasAny` | one value / array | `list`: the list contains it / any of them |

Range operators are not defined on strings. Collations differ between stores, and a publisher that
sorted `"Ä"` differently from the world would drop records that the world's re-check could never
bring back.

**Nulls.** A record whose property is null or absent satisfies only `isNull`. It fails every other
operator, `ne` and `notIn` included. That is SQL's and Cypher's rule, and a publisher must apply it
even where its store differs.

**Existence across a relationship.** A type that declares `filter.exists` can test its related
records without returning them:

```json
{ "exists": { "type": "Invoice", "via": "customerId",
              "where": { "and": [
                { "property": "status",  "op": "eq", "value": "open" },
                { "property": "dueDate", "op": "lt", "value": "2026-10-10" } ] } } }
```

On a `Customer` query this keeps the customers that have an open, overdue invoice. `via` is the
referencing property (§3.2), and the capability is declared as `"Invoice.customerId"`. `exists` over
a part (§3.5) names the part property instead: `{ "exists": { "part": "lines", "where": … } }`. The inner
`where` is limited by `Invoice`'s own `filter` declaration. One level of `exists` is allowed; an
`exists` nested inside another is not.

**What the world sends.** The world normalizes a query's predicate into a conjunction and pushes every
conjunct the publisher can evaluate **completely**: every property, operator, logical connective and
`exists` inside it must be declared. A disjunction with one unsupported branch is not pushed at all,
because pushing half of an `or` would drop the records the other half admits. Whatever is not pushed
the world evaluates itself, over what comes back.

Only declared properties can be filtered on. Every filterable property is also a returned property,
so filtering can never become a way to learn a value the publisher chose not to expose.

**What the publisher must do.** Evaluate everything it is sent, exactly. The world re-applies every
pushed predicate to the returned records, which catches a publisher that returns *too much*, but no
re-check can find a record the publisher wrongly left out. Exactness is what the conformance kit
(§11) tests hardest.

A publisher that, on some call, cannot apply the whole `where` it was sent (a fallback path, a
degraded index) answers with `"applied": false` and **must not** then apply `limit` or
`limitPerKey`. The world then treats the response as unfiltered input. A publisher that applied
everything answers with `"applied": true`. The echo lets the world rely on what happened on this call,
not just on what the manifest promised.

**Paths.** A filter, sort or field may name a property inside a value by its path
(`billingAddress.postcode`). The capability is declared under the same path.

**Projection.** `fields` lists the properties the world needs. Parts are returned only when `fields`
names them (§3.5). A publisher that declared `project`
returns at least those, plus the identity and the lookup property. It may return more.

**Order and limit.** `sort` is a list of `{property, direction}`. The world sends `limit` (on a
query) or `limitPerKey` (on a fetch) only when the whole predicate was pushed and the sort is
declared, because only then are the first N records the right N. A publisher confirms with
`"sorted": true`.

### 4.2 Fetch by keys

```http
POST {publisher}/fetch
```

```json
{
  "type": "Invoice", "by": "customerId", "keys": ["17", "42", "99"],
  "where": { "property": "status", "op": "eq", "value": "open" },
  "fields": ["number", "amount", "dueDate"],
  "sort": [{ "property": "dueDate", "direction": "asc" }],
  "limitPerKey": 3
}
```

```json
{
  "records": [
    { "number": "INV-1001", "customerId": "17", "amount": "1200.00", "dueDate": "2026-10-31" },
    { "number": "INV-1007", "customerId": "17", "amount": "80.00",   "dueDate": "2026-11-30" }
  ],
  "missing": ["42"],
  "failed": ["99"],
  "applied": true,
  "sorted": true
}
```

That is "the three earliest open invoices of each of these customers", with only three columns read,
in one call. Without pushdown the same traversal moves every invoice the customers ever had.

The batch contract, which every publisher must keep:

- **All keys in one call**, up to the lookup's `maxKeys`. The world splits larger batches; the
  publisher never receives more than it declared.
- **Every record carries the lookup property**, with a value whose canonical string form (§2.1)
  equals one of the requested keys. The property keeps its declared JSON form, so an integer
  `ownerId` is `6` in the record and `"6"` among the keys. That is how the world attaches each record to its anchor; a record without it is
  dropped and reported.
- **Every requested key is accounted for**: it has at least one record, or it is in `missing` (no
  record matches the key *and* the `where`), or it is in `failed` (the publisher could not answer for
  it). A key in none of the three is treated as `failed`.
- `missing` may be negatively cached (§3.3) only for a fetch with no `where`. `failed` never is.
- A lookup on a non-identity property may return many records per key. A lookup on `identity`
  returns at most one.
- `limitPerKey` is sent only to a lookup that declared it, and caps the records per key, in `sort`
  order.

A publisher that holds only a single-record operation internally — `findById` — may loop over the
keys itself. That loop runs inside the publisher, next to its data, which is still far cheaper than
the world making one HTTP call per key. It should declare a modest `maxKeys` to match.

### 4.3 Query

```http
POST {publisher}/query
```

```json
{
  "type": "Customer",
  "where": { "and": [
    { "property": "tier", "op": "in", "value": ["pro", "enterprise"] },
    { "exists": { "type": "Invoice", "via": "customerId",
                  "where": { "property": "status", "op": "eq", "value": "open" } } }
  ] },
  "fields": ["id", "name", "balance"],
  "sort": [{ "property": "balance", "direction": "desc" }],
  "limit": 20,
  "pageSize": 20,
  "cursor": null
}
```

```json
{
  "records": [ ... ],
  "applied": true,
  "sorted": true,
  "next": null,
  "total": 312
}
```

**`search`** — present only if the type declared `query.search` — is free text for the
publisher's own search, e.g. `"search": "acme logistics"`. It combines with `where`. Records come back in
the publisher's relevance order and the world treats the result as a ranked lexical match, with the
same contract as a `remote-search` producer ([VIRTUAL_CYPHER.md §5.3](https://github.com/embabel-worlds/realm-spec/blob/main/VIRTUAL_CYPHER.md#53-producer-kinds)):
rank, no similarity score.

**Paging is by opaque cursor.** `next` is a string to send back as `cursor`, or `null` at the end.
`total`, if the type declared `query.total`, is the number of records the `where` matches across all
pages; it lets the world fetch the remaining pages concurrently. `limit` ends the walk once that many
records have been returned.

**Nothing is ever silently cut short.** A publisher that stops early for its own reasons — a cap, a
timeout — answers with `"truncated": { "reason": "..." }`, and the world carries that warning into
the query result. A page that ends a walk without `next` and without `truncated` is a promise that
the walk is complete.

### 4.4 Aggregate

```http
POST {publisher}/aggregate
```

```json
{
  "type": "Invoice",
  "where": { "property": "status", "op": "eq", "value": "open" },
  "by": "customerId", "keys": ["17", "42"],
  "groupBy": [],
  "measures": [
    { "fn": "count", "as": "openInvoices" },
    { "fn": "sum", "property": "amount", "as": "outstanding" }
  ]
}
```

```json
{
  "groups": [
    { "key": { "customerId": "17" }, "values": { "openInvoices": "2", "outstanding": "1280.00" } }
  ],
  "missing": ["42"],
  "applied": true
}
```

The functions are `count`, `countDistinct`, `sum`, `avg`, `min` and `max`, over the properties the
type declared for each under `aggregate.measures`, grouped by the declared `aggregate.groupBy`
properties.

**Keyed aggregates** — `by` and `keys`, which require a lookup on `by` — return one group per key that
has rows. This is the reduction a traversal most often asks for ("each customer's open balance"). It
follows the batch contract of §4.2: a key with no matching rows is in `missing`, and the world reads
that as a count of zero and a null sum.

**Values follow §2.1.** Counts are integers; `sum`, `min` and `max` keep the property's type; `avg`
of an `integer` or `decimal` is a `decimal`. A sum is exact, never rounded through floating point.

This is the one operation whose answer the world cannot re-check, because it never sees the rows. So
a type declares exactly the measures it computes exactly, and the conformance kit compares every
declared measure with the same reduction over fetched rows. The world uses aggregate pushdown only
when the whole `where` was pushed, and falls back to fetching rows when `applied` is `false`.

### 4.5 Limits

| Limit | Value |
|---|---|
| Keys per fetch or keyed aggregate | the lookup's `maxKeys`, at most 1000 |
| Records per page | the type's `maxPageSize`, at most 1000 |
| Expression depth | 8 |
| Nodes in one `where` | 256 |
| Values in one `in` / `notIn` / `hasAny` | 1000 |
| Response body | 16 MiB |
| Cursor | 2048 bytes |

A publisher that answers over a limit is refused with `RESULT_BOUND`, never truncated by the world.

### 4.6 Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `UNAUTHENTICATED` | 401 | The world's credential was missing or wrong. |
| `FORBIDDEN` | 403 | The acting user may not do this. |
| `ACTING_USER_REQUIRED` | 401 | The manifest said `required` and none was sent. |
| `UNKNOWN_TYPE` | 404 | Not a type in the current manifest. The world revalidates the manifest. |
| `UNSUPPORTED` | 400 | A lookup, operator, connective, `exists`, sort, measure or grouping the type did not declare. |
| `KEY_BOUND` | 400 | More keys than `maxKeys`. |
| `APPROVAL_REQUIRED` | 403 | The verb call needs an approval it did not carry, or carried one that is expired or does not match the call (§5.2). Nothing was done. |
| `APPROVER_NOT_AUTHORIZED` | 403 | The approval is valid, but the publisher does not accept this approver (§5.2). |
| `EXPRESSION_BOUND` | 400 | A `where` over a limit in §4.5. |
| `RATE_LIMITED` | 429 | With `Retry-After`. |
| `UNAVAILABLE` | 503 | Temporarily unable to answer. Never negatively cached. |
| `PUBLISHER_DISABLED` | 503 | Publication is turned off (§2.2). The world stops serving the realm, cache included. |
| `RESULT_BOUND` | — | World-side: the answer was over a limit in §4.5. |

## 5. Verbs

A verb is an operation the publisher lets the world perform: issue a refund, assign a ticket,
recalculate a quote. It is the application's own service operation, behind its own validation and
authorization.

```json
{
  "name": "issueRefund",
  "description": "Refund part or all of a paid invoice to the customer's original payment method.",
  "subject": "Invoice",
  "input":  { "type": "object",
              "properties": { "number": { "type": "string" }, "amount": { "type": "string", "format": "decimal" }, "reason": { "type": "string" } },
              "required": ["number", "amount", "reason"] },
  "output": { "type": "object", "properties": { "refundId": { "type": "string" } } },
  "effect": "external",
  "idempotent": true,
  "approval": {
    "required": "when",
    "when": { "property": "amount", "op": "gt", "value": "500" },
    "reason": "Refunds over 500 need a second person."
  }
}
```

| Field | Meaning |
|---|---|
| `subject` | Optional. The type the verb acts on, so the world can offer it from a record. |
| `input`, `output` | JSON Schema (2020-12). The world validates `input` before calling. |
| `effect` | `read`: no change anywhere. `write`: changes the publisher's own data. `external`: has an effect outside the publisher that cannot be taken back — an email sent, a payment made. |
| `idempotent` | Whether repeating the same call is safe. |
| `dryRun` | Whether the verb accepts a dry run (§5.1). |
| `approval` | §5.2. Absent means `{ "required": "never" }`. |

### 5.1 Calling a verb

`POST {publisher}/verbs/{name}` carries the input as its body, the acting user, and an
`Idempotency-Key` header the world generates per logical attempt and reuses on retry. A publisher
that has seen that key returns the first answer again.

**Dry runs.** A verb that declares `"dryRun": true` accepts `POST {publisher}/verbs/{name}?dryRun=true`.
The publisher validates the input and authorizes the call exactly as for a real call. It then reports
what the call *would* do, and does none of it:

```json
{
  "dryRun": true,
  "records": [
    { "type": "Invoice", "key": "INV-1001", "op": "update",
      "changes": { "status": { "from": "paid", "to": "partiallyRefunded" } } }
  ],
  "external": [
    { "kind": "payment", "description": "Refund 1280.00 to the card ending 4242" }
  ],
  "approval": { "required": true, "reason": "Refunds over 500 need a second person." }
}
```

| Field | Meaning |
|---|---|
| `records` | Changes to the publisher's own records, as `create`, `update` or `delete`, with the changed properties of an update. |
| `external` | Effects outside the publisher, described for a person: the email that would be sent, the payment that would be made. |
| `approval` | Whether the real call would need approval, and why. This is how a world learns the answer for a `decided` verb before asking anyone. |

A dry run needs no approval and no idempotency key, and it must have no effect. It writes no record,
calls no external system and publishes no change or event. The conformance kit checks this. A dry run
is a prediction, not a reservation: the real call may differ if the data changed in between, and the
publisher's real answer is the one that counts.

A world uses dry runs in three places:
- it shows the approver of a call what the call will do (§5.2);
- it lets an agent check a plan before acting;
- it gives an agent in the observing stage something to report (§5.3).

### 5.2 Approval

Some operations must not happen on one person's say-so, still less on an agent's. The publisher
knows which operations those are, because the rule is usually its own business rule — refunds over
a limit, a contract change, a deletion. So **the publisher declares which verbs need approval, and
enforces it**. The world collects the approval through the world's approvals, from people the world
lets approve.

| `approval.required` | Meaning |
|---|---|
| `never` | The verb runs on the acting user's authority alone. |
| `always` | Every call needs an approval. |
| `when` | A call needs an approval when its input satisfies `approval.when`, an expression in the `where` grammar of §4.1 evaluated over the input's properties. |
| `decided` | The publisher decides per call, by rules it cannot state as an expression — the customer's history, a running total. It answers `APPROVAL_REQUIRED` (below) for the calls that need one. |

`approval.reason` is shown to the person asked to approve, and to an agent planning the call, so it
can tell the user that the action will wait for someone.

**The world may ask for more approval, never less.** A world can require approval for any verb its
owner chooses, such as every `external` verb. It cannot waive approval for a verb the publisher
says needs one, because the publisher refuses the call without it.

**The flow:**

1. The world evaluates `approval`. If the call needs approval, it raises an approval request in the
   world with the verb, its input, the acting user and `reason`, and does not call the publisher yet.
2. The publisher may still require approval the world did not foresee (`decided`, or a rule that changed).
   It answers `403` with `code: "APPROVAL_REQUIRED"` and a `reason`, and **it must not have
   performed any part of the operation**. The world then raises the request as in step 1.
3. When someone approves, the world calls the verb again, with the same `Idempotency-Key`, carrying
   an `Embabel-Approval` header.

**The approval is evidence the publisher can check, not a flag it has to trust.** `Embabel-Approval`
is a JWT signed with the same keys as the acting user (§3.4). It carries:
- the approval request's id;
- the approver's subject and verified email;
- the verb name;
- a SHA-256 digest of the canonical JSON input ([RFC 8785](https://www.rfc-editor.org/rfc/rfc8785));
- the time of approval and an expiry.

The publisher checks the signature, that the verb and digest match this call, and that the token has
not expired. It also checks that the approver is not the acting user, unless it declared
`approval.selfApproval: true`. It may then apply its own rules about who may approve. A finance
system might accept a refund approval only from someone it knows as a finance manager. It refuses
with `APPROVER_NOT_AUTHORIZED` when the approver fails those rules.

An approval authorizes **one** call with **that** input. Changing the amount after approval
changes the digest, and the publisher refuses the call. Retrying with the same `Idempotency-Key`
returns the first answer, so one approval can never trigger the operation twice.

A rejected request is never sent to the publisher. The world reports the rejection to whoever asked.

**Verbs the publisher approves itself.** An application with its own approval workflow, such as a
purchase order that goes through the app's own sign-off, does not need the world to collect the
approval. It declares `required: "never"` and answers the call with `202 Accepted` and
`{ "status": "pending", "reference": "PO-88123" }`. The outcome arrives as an event or a change (§7)
like any other change to its records.

### 5.3 Routines and agents

A world's agents are colleagues the world governs. The world chooses their sponsor, sets their
stage (off duty, observing, on duty) and runs only the version their sponsor signed
([README, `agents/`](https://github.com/embabel-worlds/realm-spec/blob/main/README.md#agents--agents-and-their-routines)). A realm proposes agents and the
world decides about them. A publisher gets exactly the same arrangement: it proposes, and the world
governs.

**A routine whose body is a verb.** A routine has a trigger — a signal or a cron schedule — and a
body. With `spec.kind: verb`, the trigger stays in the world and the body is one of the publisher's
verbs:

```json
{
  "name": "dunning",
  "description": "Every weekday morning, remind customers whose invoices are newly overdue.",
  "schedule": "0 0 8 * * MON-FRI",
  "spec": { "kind": "verb", "verb": "runDunning", "input": { "asOf": "now" } }
}
```

```json
{
  "name": "refund-on-cancellation",
  "match": { "signalType": "crm.SubscriptionCancelled" },
  "spec": { "kind": "verb", "verb": "refundUnusedPeriod",
            "input": { "customerEmail": "signal.properties.email", "cancelledAt": "signal.occurredAt" } }
}
```

- `input` maps each input property to a path into the routine's context: `signal.subject`,
  `signal.occurredAt`, `signal.properties.<name>`, or `now`. Any other JSON value is passed as a
  constant. Paths are the whole language; there is no templating. The world validates the mapped input
  against the verb's schema before calling, and records a routine whose mapping cannot satisfy the
  schema as a problem when it loads.
- The trigger may be any signal in the world, from any realm. That is why the trigger lives in the
  world: a CRM's cancellation can drive the billing system's refund, and neither application knows
  about the other.
- The call is an ordinary verb call. It carries approval (§5.2), idempotency (an `Idempotency-Key`
  derived from the routine execution, so a redelivered trigger never runs the verb twice) and the
  publisher's own authorization. The acting user is the agent's sponsor.
- **Stage.** On duty, the world calls the verb. Observing, it calls the verb as a dry run if the verb
  declares `dryRun`, and reports what it would have done. It never calls a `write` or `external` verb
  for real. If the verb has no dry run, it reports that the routine would have run. Off duty, nothing
  runs.

**Proposed agents.** The manifest may carry `agents`, in the shape of a realm's agent files: `name`,
`job`, `routing`, `routines` and `duties`. Routines may use `kind: verb` and nothing else, because a
publisher ships no code into the world. As with a realm's agents, the world ignores any sponsor,
owner or stage a manifest declares. A proposed agent arrives off duty, with no sponsor, and unsigned.

```json
"agents": [
  {
    "name": "collections",
    "job": "Make sure no invoice goes unpaid without someone chasing it.",
    "routing": "Overdue invoices, reminders, refunds, write-offs",
    "routines": [ { "name": "dunning", "...": "as above" } ],
    "duties": [ { "name": "no-silent-overdue", "text": "No invoice is overdue for a week without a reminder",
                  "holds": "SilentOverdueInvoice", "every": "0 0 * * * *" } ]
  }
]
```

**What a sponsor signs.** For a verb-backed routine, the signature covers the agent and routine
definitions, the input mapping, and the declaration of every verb its routines call: name, schemas,
`effect` and `approval`. A publisher that changes any of these causes an unsigned change on the agent,
which runs the signed version until the sponsor signs again. The world cannot sign the publisher's
code, only what the publisher declares about it. The specification says so plainly because that is
where the publisher's own release discipline takes over.

**Conversational agents.** An agent people talk to is reached over
[A2A](https://a2a-protocol.org/), not over this protocol. A proposed agent may name an A2A endpoint
relative to the publisher URL (`"a2a": "a2a/collections"`), and the world offers it as a colleague,
under the same sponsorship and stage rules. This protocol carries the agent's proposal and its
governance, and A2A carries the conversation.

## 6. Spines and identity bridging

A publisher's records join the rest of the world through **spines** — the single node a person, a
company or another shared entity resolves to ([VIRTUAL_CYPHER.md §5.4](https://github.com/embabel-worlds/realm-spec/blob/main/VIRTUAL_CYPHER.md#54-entity-canonicalization--the-spines-a-join-anchors-on)).
There are two ways for a publisher to attach, and a publisher can use both.

**A spine-bound property.** `"spine": "Organization"` on `Customer.website` is the protocol's form of
`hub: Organization`: the world normalizes the value by the spine's rules and the record attaches to
that company's `Organization` node. Nothing more is asked of the publisher. The spine may be a
built-in (`Person`, `Organization`) or one a realm in the world declares (`CustomerAccount`); a
binding to a spine the world does not have is inert, and the world reports it on install rather than
failing.

**A lookup by a spine-bound property** turns that attachment into a two-way join. Because
`Customer` declares `{ "by": "website" }`, a query anchored on an `Organization` the world already
knows — from email, from a CRM realm, from anywhere — can ask the publisher which customers that
company is, in one batched call. This is identity bridging, and **the publisher does it in code**:

- It can match on whatever it knows. Several websites per customer, billing contacts, a domain
  alias table, a merger history — all of it is the publisher's to consult when answering
  `fetch by website`.
- It returns records carrying the requested key, exactly as for any lookup (§4.2), so the world
  attaches each to the spine node it asked about.
- `missing` is a real answer: this company is not one of our customers. The world may cache that for
  `negativeTtlSeconds`.

The world normalizes keys **before** sending them: a publisher looked up by `Organization` receives
registrable domains, lower-cased; by `Person`, lower-cased emails. It never sees the variety of
forms the world collected them in, and it need not normalize again.

What a publisher cannot do is assert identity between spine nodes, or attach a record to a spine by
anything but a declared property. Spine resolution stays deterministic and stays the world's.

## 7. Changes and events

Two kinds of news flow from a publisher. **Changes** say that a record is different now, so the world
can stop guessing with TTLs. **Events** say that something happened that means something — an
invoice became overdue, a customer cancelled — so the world's handlers and agents can react. Both are
available to a world that only polls. A publisher that can reach the world may also push them (§7.3).

### 7.1 Changes

```http
GET {publisher}/changes?since={cursor}
```

```json
{
  "changes": [
    { "type": "Invoice", "key": "INV-1002", "op": "upsert", "at": "2026-10-10T09:30:00Z" },
    { "type": "Customer", "key": "42", "op": "delete", "at": "2026-10-10T09:31:12Z" }
  ],
  "next": "c-000173"
}
```

- Without `since`, the publisher returns no changes and a `next` cursor for "from now".
- `key` is the record's identity. The world evicts and refetches; the feed is an invalidation
  signal, not a replication stream, so it carries no record bodies.
- A publisher may answer `410 Gone` for a cursor it no longer holds. The world then drops its cache
  for that publisher and starts again from now.
- Changes are reported only after they are committed. A world told about an uncommitted change
  would refetch the old record and cache it as new.

### 7.2 Events

The manifest declares the events a publisher publishes:

```json
"events": [
  {
    "name": "InvoiceOverdue",
    "description": "An open invoice passed its due date unpaid.",
    "subject": "Invoice",
    "payload": { "type": "object",
                 "properties": { "number": { "type": "string" }, "daysOverdue": { "type": "integer" } },
                 "required": ["number"] }
  }
]
```

| Field | Meaning |
|---|---|
| `name`, `description` | The event, as shown to the owner and to agents choosing what to react to. |
| `subject` | Optional. The type the event is about. The event's `key` is then that record's identity, and the world can traverse from the event to the record and on into the world. |
| `payload` | JSON Schema (2020-12) for the event's own data. Events carry what happened; the record itself is fetched. |

Each event becomes a **source** in the world: something a handler or an agent can be triggered by,
like any other realm's sources.

```http
GET {publisher}/events?since={cursor}
```

```json
{
  "events": [
    { "id": "evt-90311", "name": "InvoiceOverdue", "key": "INV-1001",
      "occurredAt": "2026-10-10T00:00:05Z",
      "payload": { "number": "INV-1001", "daysOverdue": 1 },
      "changes": [{ "type": "Invoice", "key": "INV-1001", "op": "upsert" }] }
  ],
  "next": "e-004410"
}
```

- `id` is unique per publisher and stable. The world deduplicates on it, so redelivery is harmless,
  and the same event arriving both by poll and by push (§7.3) is one event.
- `changes` is optional. An event that also changed records says so, and the world invalidates
  them as if they had arrived on the changes feed, so a handler reacting to the event reads the new
  record, not a cached old one.
- Events are published only after the transaction that produced them commits, and in commit order
  per `key`.
- The cursor rules are those of §7.1. A publisher keeps events for at least as long as it declares
  in `eventRetention` (ISO 8601 duration, at least `P1D`), and answers `410 Gone` for an older
  cursor. A world that receives `410` reports the gap: events it can no longer get are lost, and the
  world should know that rather than assume a quiet day.

### 7.3 Push delivery

A publisher whose application *can* reach the world may deliver events and changes as they happen,
rather than waiting to be polled. That is the application's choice, so it is declared in the
manifest, and the world still sets up delivery: the application never has to find the world itself.

```json
"delivery": { "push": true }
```

When it installs a publisher that declares `push`, the world subscribes:

```http
POST {publisher}/subscriptions
```

```json
{
  "callback": "https://world.example.com/api/v1/publishers/billing/inbox",
  "secret": "<per-subscription signing secret>",
  "events": ["InvoiceOverdue"],
  "changes": true
}
```

The publisher answers `201` with a subscription id. It then sends a `ping` to the callback and treats
the subscription as live only if the ping is acknowledged. A publisher that cannot reach the
callback (a firewall, no route out) answers the subscription with `409` and code `UNREACHABLE`, and
the world polls instead. The world removes its subscription with `DELETE {publisher}/subscriptions/{id}`
when the realm is removed.

Each delivery is a `POST` to the callback with a batch in the same shape as a poll response:
`events` and `changes`. It is signed per the [Standard Webhooks](https://www.standardwebhooks.com/)
scheme (`webhook-id`, `webhook-timestamp`, `webhook-signature`, an HMAC over the body with the
subscription's secret).

- **The world acknowledges with `2xx` only after it has durably stored the batch.** Anything else, or no
  answer, and the publisher retries with backoff for at least `eventRetention`.
- **Push never replaces the cursor.** The world keeps its poll cursor and polls occasionally even while
  push is live, and the `id` dedupes the overlap. A delivery lost on both paths is then caught by
  the next poll, not lost silently.
- The secret authenticates deliveries to *this* callback only. It grants no other access to the
  world. It is not an API key, and the application holds no general credential for the world.

A publisher may serve several subscriptions, one per world that installed it, and delivers to each
independently.

## 8. Other publisher-to-world directions

| Extension | Why |
|---|---|
| **Outbound tunnel** | The publisher holds an outbound connection to the world and receives operations over it. This is for a publisher behind a firewall the world cannot reach. The operations and their semantics are unchanged; only who opened the socket differs. Specified separately when it is built. |

An application that wants to *consume* a world — run its views, query its graph — uses the world's
existing REST and GraphQL doors. That is a client of the world, not this protocol.

## 9. How a world installs a publisher

This is what makes the world side one URL. Given `https://billing.example.com/embabel`, a world:

1. Takes the credential together with the URL, when the owner has one. A publisher usually protects
   its manifest as well as its data, so the credential has to come first. The world keeps it in its own
   secret store, and it never appears in anything the world shows or exports.
2. `GET`s the URL, presenting the credential. If the answer is `401`, the world asks the owner for a
   credential and tries again. It never guesses one. If the URL is an index (§3.6), the world lets the
   owner choose realms and installs each one as below. It refuses an unsupported `protocol` and says
   which version it speaks.
3. Builds a realm from the manifest alone: the types with their identities, properties, values and
   parts; joins through lookups, references and spine bindings; the declared pushdown; cache and
   cost policy; verbs with their approval policies; events as sources; skills; and proposed agents.
4. Reports anything inert: a spine the world does not have, a reference with no lookup behind it, a
   sibling realm that is not installed, a skill that names a verb the manifest does not have.

Nobody writes or edits the realm a world builds from a manifest. A change to the manifest is a
change to the realm. An owner who wants more — views over the publisher's types, rules, an app —
builds it in the world, depending on the published realm.

How a world represents a published realm internally, and what typed surface it offers its own
scripts, is the world's business. A world may generate typed interfaces for types, functions for
verbs and methods for verbs with a `subject`. If it does, it should put approval in the return
type, so that code cannot assume an operation that is waiting for a person has happened. Embabel's
own mapping, onto its Virtual Cypher engine and its code mode, is described in
[realm-spec's PUBLISHERS.md](https://github.com/embabel-worlds/realm-spec/blob/main/PUBLISHERS.md).

## 10. The Spring Boot publisher (informative)

The reference publisher is a Spring Boot starter, described in
[the Spring starter](../libs/java/spring-boot-starter/README.md). In short:
- It binds at the **service layer**, as an inbound adapter beside the application's controllers. It
  never binds at the repositories, so transactions, method security and invariants apply to every
  call.
- It derives **pushdown from parameter types**. A service method that takes a Spring Data
  `Specification`, a Querydsl `Predicate` or a jOOQ `Condition` receives the whole filter tree,
  translated by the starter and tested by the conformance kit. `Sort`, `Limit`, `ScrollPosition`
  and `Window` carry ordering, limits and cursors.
- It enforces **approval** itself, from `@RealmVerb.Approval` on `@RealmVerb` methods.
- It follows the **Embabel agent framework's conventions**: annotations that a reader turns into
  metadata, Jackson descriptions, and an injected `EmbabelRealm` for registering things from code,
  as `AgentPlatform` is used for agents.

## 11. Conformance

A protocol is only as good as the second implementation of it. The conformance kit is a test suite
that runs against any publisher URL and checks every MUST in this document:
- the batch contract and key canonical forms;
- `missing` versus `failed`;
- the exactness of every declared operator, null rule and `exists`, comparing pushed results with
  the same predicate evaluated over unfiltered fetches;
- `applied` and `sorted` honesty;
- every declared aggregate, against the same reduction over fetched rows;
- cursor termination and truncation reporting;
- idempotency keys, and approval enforcement with and without a valid token;
- that a dry run has no effect: no changed record, change or event afterwards;
- that with `EMBABEL_REALM_ENABLED=false` every path answers `PUBLISHER_DISABLED` and nothing is sent;
- error codes.

The kit reads the publisher's own manifest and data, so it needs no fixtures. A publisher in C#,
TypeScript, Python, Go or anything else is conformant when the kit passes. Porting the Spring
publisher is not the requirement.

### 11.1 The adoption skill

The conformance kit makes a publisher library trustworthy, and the adoption skill makes it easy to
adopt. Every publisher library, in every language, **ships an
[Agent Skill](https://agentskills.io/specification) that lets a coding agent drop the publisher into an
existing application.** It is the first thing a developer meets, so it carries the protocol's
principles to the place where code is written.

It is not the same as a realm's skills (§3.7). Those are for agents inside a world, using an
installed realm. The adoption skill is for the coding agent in the application's repository, while
the publisher is being built. One of the things it does is help write the realm's skills.

**One core, one reference per stack.** The skill's core is language-neutral and maintained here,
beside this specification, in [`skill/embabel-realm-publisher/`](../skill/embabel-realm-publisher/SKILL.md).
Its hard rules and workflow change when the protocol changes, in the same pull request. Each library
adds exactly one file, `references/<stack>.md`, which maps the core's workflow onto that library and
lists the stack's pitfalls. A library release bundles the core at the protocol version it implements,
plus its own reference.

The core's workflow is the same everywhere:
1. **Survey** the application read-only: service layer, views, identity, authorization, side effects,
   events, and shared identities that can become spines.
2. **Propose** an exposure plan and wait for the developer's agreement. What a world sees is the
   application owner's decision.
3. **Implement** it through the library.
4. **Verify** it, with the conformance kit as the last word.
5. **Hand over** the URL, the credential, what was exposed and what was left out.

A library ships the skill in the way its ecosystem distributes files, and offers one command that
installs it into a project's agent skills directory. Examples:
- a resource in the Maven or Gradle artifact, installed by the build plugin;
- a `skills/` directory in the npm or PyPI package, installed by the package's CLI;
- `contentFiles` in a NuGet package.

The skill is versioned with the library, so a project's skill always describes the library it has.

## 12. Prior art

- **GraphQL Federation**'s `_entities(representations)` is batched entity resolution by key across
  services — the closest precedent for §4.2. This protocol adds declared cost, cache semantics,
  pushdown capability and the acting user, and drops the query language.
- **OData's capabilities vocabulary** (`FilterRestrictions`, `SortRestrictions`) is the precedent
  for declaring per property what a source can filter on (§3.1), and OData's `$apply` for
  pushed-down aggregation (§4.4).
- **Spring Data's web support** binds request parameters to a Querydsl `Predicate` in a controller.
  The Spring publisher does the same for the protocol's filter tree.
- **RFC 9457** problem details for errors; **JSON Schema 2020-12** for verb input and output;
  **RFC 8785** canonical JSON for approval digests.

## 13. Open questions

- **Acting-user trust.** A world-signed JWT needs the publisher configured with the world's JWKS URL —
  one piece of world knowledge on the publisher side. Is that acceptable as the price of per-user
  authorization and verifiable approvals, or should `actingUser` default to `ignored` and per-user
  be the opt-in?
- **Writes as records.** Verbs cover consequential operations. Whether a publisher should also accept
  plain creates and updates against its types, or whether those must always be verbs, is undecided.
  §14.3 sketches one answer.
- **Collation.** Range operators are excluded from strings, and case folding is Unicode simple case
  folding. Whether a publisher may declare a collation the world can match is open.

## 14. Futures

Version 1 is deliberately small: one manifest per publisher, the same declaration for every caller,
reads, verbs and an invalidation feed. Each idea below makes publishers more powerful, and each is
designed to be **additive**: a version 1 world that ignores it stays correct. Most of them become
cheap because of choices version 1 already makes. One expression grammar serves filters, approval
rules, preconditions and watches. Every call carries an acting user. The publisher is the authority
on what each user may see.

### 14.1 Who sees what

**Role-scoped manifests.** The manifest today is the same for everyone. Instead, the publisher could
answer the manifest differently depending on who connects:

- **Per connection.** Each installation authenticates with its own credential, so the publisher can
  give each one a different manifest. A partner's world sees `Customer` without `balance` and no
  `Invoice` at all. The world's own finance installation sees everything. The publisher decides by
  the credential, and the world just installs what it is given. This works within version 1 once the
  manifest's `ETag` is understood to be per credential. It is mainly a matter of saying so.
- **Per acting user.** Within one installation, different people see different fields and objects.
  The manifest would declare the full shape, with `visibility` on each type, property and verb
  saying which publisher-side roles see it. The world would request a user's own view with the
  acting-user header on `GET {publisher}`. That gives each user a manifest of their own: a model
  helping a sales rep would not even know `margin` exists, rather than meeting nulls. The publisher
  still enforces visibility on every read, refusing a filter on a property the user cannot see,
  because a filter on a hidden value reveals it as surely as returning it.

Most stacks already have a way to describe audience-specific shapes, so a publisher rarely needs a
new one. Examples are serializer views or contexts, GraphQL field authorization and OData
`$select` restrictions. The Spring publisher maps it onto Jackson's `@JsonView` (../libs/java/spring-boot-starter/README.md).

**Masking.** Some properties are useful in a form that does not disclose them: an IBAN shown as its
last four digits, an email hashed so it still joins. A property could declare
`mask: last4 | hash | redact` per role, using the same vocabulary as Virtual Cypher's `governance:`
grammar. A hashed spine key would then join without ever leaving the publisher in clear.

**Data classification.** Properties could carry `classification: pii | financial | health |
secret`, and a `use` constraint that the world enforces: `display` (show to people, never send to a
model), `noCache`, and a retention limit. A publisher could then expose a patient's diagnosis to the
clinician's world while guaranteeing it never reaches an LLM prompt. That would be a guarantee the
world can make, and the publisher can audit through §14.6.

**Tenancy.** One publisher serving many tenants, with the tenant taken from the connection's
credential. Each tenant's world sees only its own types, including tenant-specific custom fields
(the Spring publisher's `EmbabelRealm` already registers those at run time).

### 14.2 Reading more, moving less

- **Statistics for planning.** The publisher could declare cardinalities, the selectivity of common
  filters and per-call latency. The world's planner would then price a publisher's lookups by their
  actual economics, not by producer kind: which side of a join to drive from, and whether an anchor
  set is worth pushing at all. A `count` operation that answers with an estimate in milliseconds
  serves the same purpose.
- **Consistent snapshots.** A multi-call traversal can see the source change between calls. A
  publisher that supports it would return a `snapshot` token from the first call. The world sends it
  on every later call, so the whole query reads one state, e.g. a database transaction's snapshot
  or an `asOf` timestamp.
- **Time travel.** `asOf` on any read, for publishers with history. "Customers who were enterprise
  tier at the start of the quarter" becomes a pushed-down read rather than an impossibility.
- **Semantic search.** A `similar` operator over a property the publisher has embedded, with the
  embedding model declared. This serves the world's `vector` relevance contract when the publisher
  already owns the index.
- **Expensive properties.** Properties declared `cost: high` — a computed risk score, a property
  backed by the publisher's own model — are returned only when `fields` asks for them, and the world
  never asks for them unless the query needs them.
- **Bulk streaming.** NDJSON or Arrow responses for large scans and materialised views, so a
  million-row snapshot does not arrive as pages of JSON.
- **Explain.** `"explain": true` on a read returns the publisher's own plan and cost. It would be
  shown beside the world's plan in query diagnostics, so a slow publisher is visibly slow, not a
  mystery.

### 14.3 Acting, planning and proposing

- **Preconditions and effects on verbs.** A verb could declare `pre` and `post` as expressions over
  its subject in the §4.1 grammar: `sendReminder` requires `status = open` and
  `dueDate < today`, and afterwards `lastRemindedAt` is set. The Embabel framework's GOAP planner
  plans with exactly this shape, the `pre` and `post` of `@Action`. The world's agents could then
  chain a publisher's verbs into plans, rather than calling them one at a time and hoping.
- **Record proposals.** Instead of a verb per change, a world proposes record edits against a type:
  "set this invoice's due date". The publisher validates the edit with its own rules, answers with
  the diff and the approval it requires, and applies it on approval. That is §13's open question
  answered in the same approval flow as verbs.
- **Compensation.** A verb could name its inverse (`issueRefund` ↔ `reverseRefund`). A failed
  multi-step plan could then be unwound by the planner, not by a person.

### 14.4 Events and time

- **Standing queries.** The world registers a `where` with the publisher — "customers whose balance
  exceeds their limit" — and the publisher reports records entering and leaving the set. That pushes
  down a watch, not just a filter, and the publisher evaluates it on its own writes, which is where
  it is cheapest.
- **Scheduled verbs.** A verb invoked "at the end of the month", held by the publisher, which owns
  the business calendar, not by the world.

### 14.5 Identity and joining

- **Publisher-declared spines.** A publisher that is the system of record for an identity — products
  by SKU, sites by site code — could declare the spine itself, with its normalization. Other realms
  would then join on it. Today only realms declare spines.
- **Suggested bridges.** Version 1 forbids a publisher from asserting identity between spine nodes.
  A future version could let it *suggest* one, with a confidence and its evidence ("these two
  domains are the same company after a merger"). The world keeps the decision, through the same
  offline tier that does fuzzy resolution today.
- **Deep links.** A `link` template per type (`https://billing.example.com/invoices/{number}`). Every
  record in a world can take the user back to the application's own screen for it. This is small to
  build and very useful.

### 14.6 Trust, audit and economics

- **Skills with scripts.** A publisher skill that ships a script, run in the world's sandbox under the
  same approval and capability rules as a captured realm's code, and trusted through a signed
  manifest.
- **Signed manifests.** The publisher signs its manifest. A world pins the signer on install and is
  warned if a later manifest is signed by someone else: supply-chain protection for the realm
  itself.
- **Purpose and agent context.** Every call carries why it was made: the world, the agent, the run
  and the user's question, in an `Embabel-Context` header. The publisher's audit log records that
  "the collections agent read these invoices for this account manager at 09:30 answering 'who owes us most'". For
  regulated data, that log is the difference between allowed and forbidden.
- **Quotas and metering.** A publisher that is a commercial data product declares a price per call
  or per record, and the world meters it against a budget the owner sets. A data vendor publishing a
  publisher URL becomes a paid realm, with no integration work on either side.
- **Directories.** A signed list of publishers, published by an organization or a vendor. A world
  can then browse what it may install, the way realm sources are browsed today.

### 14.7 Presentation

- **Cards.** A publisher supplies a small rendering for its types: an invoice card, a customer
  header. World apps and chat can then show a record the way the application's own users would
  recognize it. Content-security rules for embedded content need settling first.
- **Localization.** Descriptions, enum labels and approval reasons in several languages, chosen by
  the acting user's locale.
