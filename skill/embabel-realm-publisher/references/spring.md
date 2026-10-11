# Spring Boot

The Spring publisher is a Java starter. The full programming model is
[the Spring starter's README](https://github.com/embabel-worlds/publisher/blob/main/libs/java/spring-boot-starter/README.md); this
is the working checklist.

## Recognize the stack

`pom.xml` or `build.gradle(.kts)` with `spring-boot-starter-*`. Service classes are `@Service` beans,
usually `@Transactional`. Security is Spring Security if `spring-boot-starter-security` is present.

## Add the starter

```xml
<dependency>
  <groupId>com.embabel.publisher</groupId>
  <artifactId>embabel-publisher-spring-boot-starter</artifactId>
</dependency>
```

That is the Spring Boot 4 starter. On Spring Boot 3.2 or later, use
`embabel-publisher-spring-boot3-starter` instead; the application code is the same. Requires Java 17.
Until the library is released, build it first: `mvn install` in `libs/java` of
embabel-worlds/publisher, then depend on `0.1.0-SNAPSHOT`.

Configure under `embabel.realm`: `name`, `title`, `description`, `maturity`, and `path` (default
`/embabel`). The world's credential is `embabel.realm.auth.token`; set it from the environment
(`EMBABEL_REALM_AUTH_TOKEN`), never in a committed file. Without it every call is refused.
`embabel.realm.enabled: false`, or `EMBABEL_REALM_ENABLED=false`, turns all publication off.

The [starter's README](https://github.com/embabel-worlds/publisher/blob/main/libs/java/spring-boot-starter/README.md#status-what-is-built)
says which parts are built. Do not propose what is not: in particular, approval `when` and
`decided`, dry runs, events, aggregates, values and parts, and the acting user.

## Map the plan onto the model

| Plan | Spring |
|---|---|
| type | `@RealmType(name, entity, cache)` on the view record; `@RealmType.Id`, `.Spine`, `.Reference`, `.Path` on components |
| filter | a criteria record the existing search method takes; each component is one (property, operator): `eq` by default, `in` for a `Collection`, `between` for a `Range`, or `@RealmQuery.Criterion(property = "lastName", op = Op.PREFIX)` |
| filter on a type with no query | `@RealmType.Filterable({…})` on the component; its lookups are filtered in-process |
| sortable property | `@RealmType.Sortable` on the component |
| value | a nested record with no annotation |
| part | `@RealmType.Part List<T>`, and `@RealmType.OwnerKey` on the part |
| lookup | `@RealmLookup(by, maxKeys)` on a public service method taking `Collection<K>`, and optionally a criteria record and a `Sort` |
| query | `@RealmQuery(maxPageSize)` on a public method wrapping the existing search: a criteria record and a `Pageable`, returning `Page<T>` |
| richer pushdown, last resort | a `Specification<E>` parameter and `@RealmType.Filterable` properties, only when the developer asks and accepts `JpaSpecificationExecutor` on the repository |
| aggregate | `@RealmAggregate(groupBy, count, sum, …)` |
| verb | `@RealmVerb(description, subject, effect, idempotent)` with `@RealmVerb.Approval(required, reason)`, on a public method taking one record (its input; `@NotNull` marks a required field) and returning a record or nothing |
| event | `@RealmEvent(subject)` on the application event record, `@RealmEvent.Key` on its key |
| agent | `src/main/resources/embabel/agents/<name>.yml` |
| realm skill | `src/main/resources/embabel/skills/<name>/SKILL.md` |
| about | `src/main/resources/embabel/realm/ABOUT.md` |
| several realms | `embabel.realm.realms.<name>.*`, and `realm =` on types or `@RealmPackage` |

## Spring-specific pitfalls

- **Add a service layer when the controllers call repositories.** Many applications (Spring
  PetClinic among them) have none. Add services beside the controllers for exactly what the realm
  exposes, and leave the controllers alone: that keeps the change small and reviewable.
- **Wrap the existing search; do not widen the repository.** Publish the query through the method
  the application's search screen already calls (PetClinic's `findByLastNameStartingWith`), with a
  criteria record of what it takes. Do not add `JpaSpecificationExecutor` to a repository to get more
  pushdown: it gives every caller of the repository arbitrary queries and deletes. The world filters
  what the criteria do not carry. Offer a `Specification` only if the developer asks for richer
  pushdown, and say what it costs.
- **Batch, from the side that maps the association.** A lookup by a foreign key the entity does not
  map (a unidirectional `@OneToMany` from the owner, say) reads the pairs with one JPQL join from the
  owning side, then loads the records with `findAllById`. Never loop per key.
- **Mirror the UI's rules in the verb.** If a controller enforces a rule the service does not (a
  visit must be in the future), the verb must enforce it too, or the realm is a way around it. Throw
  `IllegalArgumentException`; the starter answers `INVALID_REQUEST` with its message.
- **Sort by attributes that cannot be null.** The protocol sorts nulls last ascending, and Spring
  Data JPA 3 cannot place nulls. On Boot 3, a sortable property must map to an attribute that cannot
  be null (`@Column(nullable = false)`, `@NotNull`, `@NotBlank`), unless the method takes a
  `Specification`. The starter says so at startup.
- **A criteria component is `null` when nothing was pushed for it**, so treat `null` as "no filter",
  as the search form does. Never make one primitive.
- **Combine, never replace.** A method receiving a `Specification` must `and` it with its own rules:
  `where.and(visibleToCurrentUser())`. The starter never passes `null`.
- **Call through the proxy.** Put realm annotations on the bean's public methods. A method the bean
  calls on itself bypasses `@Transactional` and `@PreAuthorize`, which is why the starter only
  invokes methods from outside.
- **Dry runs.** The starter rolls back the transaction, but rollback cannot recall an email or a
  payment. Check `call.isDryRun()` before every external effect.
- **Fetch plans.** If a part list is expensive, take `@RealmFields Set<String>` and load the parts
  only when they are asked for, for example through an `@EntityGraph` repository method.
- **Events need a `DataSource`.** The starter refuses to start without one. With Spring Modulith, it
  uses the event publication registry.
- **Acting user.** If there is a `UserDetailsService`, the default mapper uses it. Otherwise declare a
  `RealmActingUserMapper` bean that maps the world user's email to the application's principal.

## Verify

The conformance kit (`@RealmConformance`) is not written yet. Until it is:

- Start the application with a token and read the manifest as a world owner would:
  `curl -H "Authorization: Bearer $EMBABEL_REALM_AUTH_TOKEN" http://localhost:8080/embabel`.
  Compare it with the agreed plan, property by property.
- Exercise a lookup with a filter (`POST /embabel/fetch`), a query walked to its end by cursor
  (`POST /embabel/query`), and each verb, including a refusal. Validate each body against
  `spec/schema/protocol.schema.json`, and a verb's output against its `output` schema.
- Check that every requested key comes back as a record, in `missing` or in `failed`.
- Restart with `EMBABEL_REALM_ENABLED=false` and check every path answers `PUBLISHER_DISABLED`.
- Add a `@SpringBootTest` that fetches the manifest and one filtered lookup, so the build keeps
  checking the realm.
