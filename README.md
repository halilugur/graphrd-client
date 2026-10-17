<p align="center"><img src="logo-mark.svg" alt="GraphRD" width="72"></p>

# GraphRD Java client

[![Maven Central](https://img.shields.io/maven-central/v/org.graphrd/graphrd-client.svg)](https://central.sonatype.com/artifact/org.graphrd/graphrd-client)

The pure-Java client library of [GraphRD](https://graphrd.org) — SPARQL, RDF
statements, repository management and a JPA-shaped object mapper in one jar,
with **zero dependencies** (the small JSON reader is in the jar). Java 17+.

## Install

```xml
<dependency>
  <groupId>org.graphrd</groupId>
  <artifactId>graphrd-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Quick start

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

Two layers, one jar:

- **`org.graphrd.RdfClient`** — SPARQL queries and updates, statements in any
  RDF format, repository management. Thread-safe; connections pooled.
- **`org.graphrd.rdf.RdfEntityManager`** — annotated entities over SPARQL: a
  JPA-shaped mapper that rides the server's indexed query path (no SQL, no
  Hibernate).

## Mapping entities

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
delete-then-insert, one transaction, idempotent. References are IRIs, so there
is no join configuration to get wrong; lists are read in bulk (one query for
the rows, one per referenced type — no per-row follow-ups).

Beyond the basics:

- **`saveSmart`** compares the entity with what is stored and touches only the
  predicates that differ (saving an unchanged entity writes nothing at all);
  **`saveAll`** sends the whole batch as one chained request.
- **`@RdfId(generated = true)`** — leave the id null; an IRI is minted under
  the type's namespace (`…/person/a1b2c3`) and written back into the field.
- **`@RdfInverse("…predicate") List<Person>`** — the other direction of a
  reference, JPA's `@OneToMany`: everyone pointing at this entity, read in bulk.
- **Multi-valued references** — `List<Company>` fields collect every value of
  the predicate, each member resolved in one bulk query.
- **`where("worksFor", EQ, company)`** — an entity (or a `Ref`) as a value
  compares by IRI automatically.
- **`q.page(0, 20)`** — items plus the total, in one helper.

## Queries

The fluent query covers filters, LIKE, multi-key sorts and paging; a typed
version makes a typo a compile error:

```java
em.query(Person.class).where(Person::getAge, EntityQuery.Op.GT, 30).list();
```

Derived queries, Spring-Data-style but with no Spring involved (a JDK proxy):

```java
interface People extends RdfRepositories.RdfRepository<Person> {
    List<Person> findByAgeGreaterThanAndSalaryLessThan(int age, int salary);
    Optional<Person> findFirstByName(String name);
}
People people = em.repository(People.class);
```

Lazy references: a `Ref<Company>` field holds just the IRI until `get()`
fetches it once. Case-blind search is `whereILike`, multi-key sorts are
`orderBy(…).thenBy(…)`.

## Namespaces and literal shapes

A manager can carry a namespace
(`new RdfEntityManager(client, repo, "https://schema.example.com/")`): short
names in `@RdfType`/`@RdfProperty`/`@RdfInverse` resolve against it, full IRIs
always win, and `{ns}` in a raw SELECT stands for it — the vocabulary moves
without touching Java. `@RdfProperty` also takes `lang` and `datatype` for
string literals (`"…"@tr`, `"…"^^<…double>`).

## Writing entities out

`em.toRdf(entity, RdfFormat)` (and a list overload) writes Turtle (prefix line
included), N-Triples or expanded JSON-LD — references as `@id`, `lang` and
`datatype` shaped — for serving or exporting any resource as it is stored.

## In a Spring Boot application

Spring wiring — `RdfClient`/`RdfEntityManager` beans from `graphrd.*` settings
in application.yml, closed on shutdown — lives in the companion module
[graphrd-spring-data](https://github.com/halilugur/graphrd-spring-data), together
with Spring Data repositories. Non-Spring applications only need this jar. The
[showcase application](https://github.com/halilugur/graphrd/tree/main/examples/spring-boot-graphrd)
runs on both.

## Requirements and tests

Java 17+ and a running GraphRD server. `mvn test` boots a throwaway server and
runs the whole suite against it: it expects the release binary at
`../../target/release/graphrd` (clone GraphRD next to this repository and run
`cargo build --release`; override with `-Dgraphrd.bin=`). Without the binary
the tests skip.

## License

[Apache-2.0](LICENSE)
