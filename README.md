<p align="center"><img src="logo-mark.svg" alt="GraphRD" width="72"></p>

# GraphRD Java client

The pure-Java client library of [GraphRD](https://github.com/halilugur/graphrd) —
SPARQL, RDF statements, repository management, and a JPA-shaped object mapper,
with zero dependencies.

```xml
<dependency>
  <groupId>org.graphrd</groupId>
  <artifactId>graphrd-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

Two layers, zero dependencies (the small JSON reader is in the jar), Java 17+:

- **`org.graphrd.RdfClient`** — SPARQL queries and updates, statements in any RDF
  format, repository management. Thread-safe; connections pooled.
- **`org.graphrd.rdf.RdfEntityManager`** — annotated entities over SPARQL: a
  JPA-shaped mapper that rides the server's indexed query path (no SQL, no
  Hibernate).

```java
try (RdfClient client = new RdfClient("http://localhost:7200")) {
    client.createRepository("demo");

    try (RdfEntityManager em = new RdfEntityManager(client, "demo")) {
        Company acme = new Company("http://example.org/acme", "Acme");
        em.save(acme);
        em.save(new Person("http://example.org/alice", "Alice", 34, 82000, acme, List.of("Al")));

        Person alice = em.find(Person.class, "http://example.org/alice");
        em.findBy(Person.class, "name", "Alice");
        em.query(Person.class).where("age", EntityQuery.Op.GT, 30)
          .whereLike("name", "A%")
          .orderBy("salary", true)
          .limit(10).list();
    }
}
```

## The mapping

```java
@RdfType("http://example.org/Person")
public class Person {
    @RdfId             String id;                                    // the subject IRI
    @RdfProperty("http://example.org/name")   String  name;
    @RdfProperty("http://example.org/salary") Integer salary;
    @RdfProperty("http://example.org/nick")   List<String> nicks;    // every value of the predicate
    @RdfProperty("http://example.org/worksFor") Company worksFor;    // an IRI reference, read eagerly
}
```

`save` writes the whole entity in **one request** — a `;`-chained
delete-then-insert, one transaction, idempotent. `saveSmart` compares the
entity with what is stored and touches only the predicates that differ
(saving an unchanged entity writes nothing at all). References are IRIs, so
there is no join configuration to get wrong; lists are read in bulk (one
query for the rows, one per referenced type — no per-row follow-ups).

The second wave, in the same spirit:

- **`@RdfId(generated = true)`** — leave the id null; an IRI is minted under
  the type's namespace (`…/person/a1b2c3`) and written back into the field.
- **`@RdfInverse("…predicate") List<Person>`** — the other direction of a
  reference, JPA's `@OneToMany`: everyone pointing at this entity, read in bulk.
- **Multi-valued references** — `List<Company>` fields collect every value of
  the predicate (each member resolved in one bulk query).
- **`saveAll` is one chained request** — one transaction for the whole batch.
- **`where("worksFor", EQ, company)`** — an entity (or `Ref`) as a value
  compares by IRI automatically.
- **`q.page(0, 20)`** — items plus the total, in one helper.
- **`whereILike`** and **`orderBy(…).thenBy(…)`** for case-blind search and
  multi-key sorts.

Beyond the fluent query there are three more shapes:

```java
// type-safe criteria — a typo cannot compile
em.query(Person.class).where(Person::getAge, EntityQuery.Op.GT, 30).list();

// Spring-Data-style derived queries, no Spring needed (a JDK proxy)
interface People extends RdfRepositories.RdfRepository<Person> {
    List<Person> findByAgeGreaterThanAndSalaryLessThan(int age, int salary);
    Optional<Person> findFirstByName(String name);
}
People people = em.repository(People.class);

// a lazy reference: Ref<Company> fields hold the IRI until get()
```

`@RdfProperty` also takes `lang` and `datatype` for string literals
(`"…"@tr`, `"…"^^<…double>`). A manager can carry a namespace
(`new RdfEntityManager(client, repo, "https://schema.example.com/")`): short
names in `@RdfType`/`@RdfProperty` resolve against it, full IRIs still win,
and `{ns}` in a raw SELECT stands for it — the vocabulary moves without
touching Java. Entities also go the other way: `em.toRdf(entity, RdfFormat)`
(and a list overload) writes Turtle (prefix line included), N-Triples or
expanded JSON-LD — references as `@id`, `lang`/`datatype` shaped — for
serving or exporting any resource as it is stored. In a Spring Boot application, just add the jar:
`GraphRdAutoConfiguration` wires `RdfClient` and `RdfEntityManager` from
`graphrd.*` settings in application.yml and closes them on shutdown —
see the the [showcase application](https://github.com/halilugur/graphrd/tree/main/examples/spring-boot-graphrd), which runs on it.

For Spring Data's own interface structure — `GraphRdRepository` with
`Pageable`, `Sort`, derived names parsed by Spring — there is a separate,
optional module: [graphrd-spring-data](https://github.com/halilugur/graphrd-spring-data).

## Tests

`mvn test` boots a throwaway GraphRD server; it expects the release binary at `../../target/release/graphrd` (clone GraphRD next to this repository and run `cargo build --release`)
(`-Dgraphrd.bin=` overrides) and runs the whole suite against it — build the
server with `cargo build --release` first; without the binary the tests skip.
