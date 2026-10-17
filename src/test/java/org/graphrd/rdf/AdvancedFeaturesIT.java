package org.graphrd.rdf;

import static org.graphrd.rdf.EntityQuery.Op.GT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.graphrd.RdfClient;
import org.graphrd.TestServer;

/** The second wave: one-request saves, delta saves, bulk reads, typed
 *  criteria, derived repositories, lazy references, lang/datatype. */
class AdvancedFeaturesIT {

    private static RdfClient client;
    private static RdfEntityManager em;

    private final Company acme = new Company("http://ex.org/acme", "Acme");
    private final Company initech = new Company("http://ex.org/init", "Initech");

    @RdfType("http://ex.org/Person")
    static class Person {

        @RdfId(generated = true)
        String id;

        @RdfProperty("http://ex.org/name")
        String name;

        @RdfProperty("http://ex.org/age")
        Integer age;

        @RdfProperty("http://ex.org/salary")
        Integer salary;

        @RdfProperty("http://ex.org/worksFor")
        Company worksFor;

        @RdfProperty("http://ex.org/boss")
        Ref<Person> boss;

        /** The other direction of team membership: the teams this person is in. */
        @RdfInverse("http://ex.org/member")
        List<Team> teams;

        @RdfProperty(value = "http://ex.org/bio", lang = "tr")
        String bio;

        @RdfProperty(value = "http://ex.org/weight", datatype = "http://www.w3.org/2001/XMLSchema#double")
        String weight;

        Person() {
        }

        Person(String id, String name, Integer age, Integer salary) {
            this.id = id;
            this.name = name;
            this.age = age;
            this.salary = salary;
        }

        String getName() {
            return name;
        }

        Integer getAge() {
            return age;
        }

        Integer getSalary() {
            return salary;
        }
    }

    /** A team holds several members — a multi-valued reference. */
    @RdfType("http://ex.org/Team")
    static class Team {

        @RdfId
        String id;

        @RdfProperty("http://ex.org/name")
        String name;

        @RdfProperty("http://ex.org/member")
        List<Person> members;

        Team() {
        }
    }

    interface People extends RdfRepositories.RdfRepository<Person> {

        List<Person> findByName(String name);

        List<Person> findByAgeGreaterThanAndSalaryLessThan(int age, int salary);

        Optional<Person> findFirstByName(String name);

        long countByWorksFor(String companyIri);

        void deleteByName(String name);
    }

    @BeforeAll
    static void up() throws Exception {
        client = new RdfClient(TestServer.start());
        client.createRepository("adv");
        em = new RdfEntityManager(client, "adv");
    }

    @AfterAll
    static void down() {
        TestServer.stop();
    }

    @BeforeEach
    void fresh() {
        em.deleteAll(Person.class);
        em.deleteAll(Company.class);
        em.save(acme);
        em.save(initech);
        em.saveAll(List.of(
                new Person("http://ex.org/alice", "Alice", 34, 82000),
                new Person("http://ex.org/bob", "Bob", 28, 61000),
                new Person("http://ex.org/carol", "Carol", 41, 95000)));
    }

    @Test
    void saveKeepsOtherPredicatesInOneRequest() {
        // the chained save replaces only its own entity's predicates;
        // a triple added out of band to ANOTHER resource stays put
        client.update("adv", "INSERT DATA { <http://ex.org/other> <http://ex.org/name> \"Kept\" }");
        Person alice = em.find(Person.class, "http://ex.org/alice");
        alice.salary = 90000;
        em.save(alice);
        assertEquals(90000, em.find(Person.class, alice.id).salary);
        assertEquals("Kept", client.query("adv",
                "SELECT ?n WHERE { <http://ex.org/other> <http://ex.org/name> ?n }").get(0).get("n"));
    }

    @Test
    void saveSmartWritesOnlyTheDifference() {
        Person bob = em.find(Person.class, "http://ex.org/bob");
        bob.salary = 70000; // age and name unchanged
        em.saveSmart(bob);
        Person after = em.find(Person.class, bob.id);
        assertEquals(70000, after.salary);
        assertEquals(28, after.age);
        assertEquals("Bob", after.name);

        // saving an unchanged entity writes nothing at all
        String before = client.getStatements("adv", "text/turtle");
        em.saveSmart(after);
        assertEquals(before, client.getStatements("adv", "text/turtle"));
    }

    @Test
    void typedCriteriaUsesGetterReferences() {
        List<Person> over30 = em.query(Person.class).where(Person::getAge, GT, 30).list();
        assertEquals(2, over30.size());
        Person top = em.query(Person.class)
                .whereLike(Person::getName, "A%")
                .orderBy(Person::getSalary, true)
                .limit(1).list().get(0);
        assertEquals("Alice", top.name);
        List<Person> withPay = em.query(Person.class).whereNotNull("salary").list();
        assertEquals(3, withPay.size());
    }

    @Test
    void derivedRepositoryProxies() {
        People people = em.repository(People.class);

        List<Person> alices = people.findByName("Alice");
        assertEquals(1, alices.size());

        List<Person> combo = people.findByAgeGreaterThanAndSalaryLessThan(30, 100000);
        assertEquals(List.of("Alice", "Carol"),
                combo.stream().map(p -> p.name).sorted().toList());

        assertEquals(0, people.countByWorksFor("http://ex.org/nowhere"));
        assertEquals(Optional.of("Alice"), people.findFirstByName("Alice").map(p -> p.name));
        assertTrue(people.findByName("Nobody").isEmpty());

        people.deleteByName("Bob");
        assertNull(em.find(Person.class, "http://ex.org/bob"));
    }

    @Test
    void lazyRefResolvesOnDemand() {
        Person alice = em.find(Person.class, "http://ex.org/alice");
        Person boss = new Person("http://ex.org/grace", "Grace", 60, 120000);
        em.save(boss);
        alice.boss = new Ref<>("http://ex.org/grace", Person.class,
                (t, iri) -> em.find(t, iri));
        em.save(alice);

        Person back = em.find(Person.class, alice.id);
        assertNotNull(back.boss);
        assertEquals("Grace", back.boss.get().name); // fetched here, not on find
    }

    @Test
    void langAndDatatypeShapeTheLiteral() {
        Person p = em.find(Person.class, "http://ex.org/alice");
        p.bio = "merhaba";
        p.weight = "72.5";
        em.save(p);

        String turtle = client.getStatements("adv", "text/turtle");
        assertTrue(turtle.contains("\"merhaba\"@tr"), turtle);
        assertTrue(turtle.contains("\"72.5\"^^xsd:double"), turtle);
        assertEquals("merhaba", em.find(Person.class, p.id).bio);
    }

    @Test
    void generatedIdIsMintedOnSave() {
        Person p = new Person(null, "Nobody", 50, 1000); // no id given
        em.save(p);
        assertTrue(p.id.startsWith("http://ex.org/person/"), p.id);
        assertEquals("Nobody", em.find(Person.class, p.id).name);
    }

    @Test
    void multiValuedReferencesComeBackAsLists() {
        Person alice = em.find(Person.class, "http://ex.org/alice");
        Person bob = em.find(Person.class, "http://ex.org/bob");
        Person carol = em.find(Person.class, "http://ex.org/carol");
        Team core = new Team();
        core.id = "http://ex.org/team/core";
        core.name = "Core";
        core.members = List.of(alice, bob);
        Team wide = new Team();
        wide.id = "http://ex.org/team/wide";
        wide.name = "Wide";
        wide.members = List.of(alice, carol);
        em.saveAll(List.of(core, wide)); // one chained request

        List<Team> teams = em.findAll(Team.class);
        assertEquals(2, teams.size());
        for (Team t : teams) {
            if (t.name.equals("Core")) {
                assertEquals(List.of("Alice", "Bob"),
                        t.members.stream().map(m -> m.name).sorted().toList());
            } else {
                assertEquals(List.of("Alice", "Carol"),
                        t.members.stream().map(m -> m.name).sorted().toList());
            }
        }
    }

    @Test
    void inverseReferencesPointBack() {
        Person alice = em.find(Person.class, "http://ex.org/alice");
        Person bob = em.find(Person.class, "http://ex.org/bob");
        Person carol = em.find(Person.class, "http://ex.org/carol");
        Team core = new Team();
        core.id = "http://ex.org/team/core";
        core.name = "Core";
        core.members = List.of(alice, bob);
        Team wide = new Team();
        wide.id = "http://ex.org/team/wide";
        wide.name = "Wide";
        wide.members = List.of(alice, carol);
        em.saveAll(List.of(core, wide));

        Person a = em.find(Person.class, "http://ex.org/alice");
        assertEquals(List.of("Core", "Wide"), a.teams.stream().map(t -> t.name).sorted().toList());
        Person b = em.find(Person.class, "http://ex.org/bob");
        assertEquals(List.of("Core"), b.teams.stream().map(t -> t.name).toList());
    }

    @Test
    void whereAcceptsEntityValues() {
        List<Person> people = em.findAll(Person.class);
        people.forEach(p -> p.worksFor = acme);
        people.forEach(em::save);
        List<Person> atAcme = em.query(Person.class).where("worksFor", EntityQuery.Op.EQ, acme).list();
        assertEquals(3, atAcme.size());
    }

    @Test
    void pagingAndSecondSortKey() {
        // equal salaries for two people so the second key decides
        Person x = new Person("http://ex.org/x1", "Xena", 30, 50000);
        Person y = new Person("http://ex.org/x2", "Yara", 40, 50000);
        em.saveAll(List.of(x, y));

        EntityQuery.Paged<Person> page = em.query(Person.class)
                .where(Person::getSalary, GT, 0)
                .orderBy(Person::getSalary, true)
                .thenBy(Person::getAge, false) // equal salary → younger first
                .page(0, 2);
        assertEquals(5, page.total());
        assertEquals(2, page.items().size());

        List<Person> tie = em.query(Person.class)
                .where(Person::getSalary, GT, 0)
                .orderBy(Person::getSalary, true)
                .thenBy(Person::getAge, false)
                .limit(2).offset(3).list();
        assertEquals("Xena", tie.get(0).name); // 50000, age 30 before 40
    }

    @Test
    void caseInsensitiveLike() {
        assertEquals(1, em.query(Person.class).whereILike(Person::getName, "aLiCe%").list().size());
    }

    @Test
    void entitiesWrittenOutInEveryFormat() {
        Person p = em.find(Person.class, "http://ex.org/alice");
        p.worksFor = acme;
        p.bio = "merhaba";
        p.weight = "72.5";
        em.save(p);

        String turtle = em.toRdf(p, org.graphrd.RdfFormat.TURTLE);
        org.junit.jupiter.api.Assertions.assertTrue(turtle.contains("a <http://ex.org/Person>"), turtle);
        org.junit.jupiter.api.Assertions.assertTrue(turtle.contains("<http://ex.org/salary> 82000"), turtle);
        org.junit.jupiter.api.Assertions.assertTrue(turtle.contains("<http://ex.org/worksFor> <http://ex.org/acme>"), turtle);
        org.junit.jupiter.api.Assertions.assertTrue(turtle.contains("\"merhaba\"@tr"), turtle);

        String ntriples = em.toRdf(p, org.graphrd.RdfFormat.NTRIPLES);
        org.junit.jupiter.api.Assertions.assertTrue(ntriples.contains("<http://ex.org/alice> <http://ex.org/age> \"34\" .")
                || ntriples.contains("<http://ex.org/alice> <http://ex.org/age> 34 ."), ntriples);

        String jsonld = em.toRdf(p, org.graphrd.RdfFormat.JSONLD);
        org.junit.jupiter.api.Assertions.assertTrue(jsonld.contains("\"@id\":\"http://ex.org/alice\""), jsonld);
        org.junit.jupiter.api.Assertions.assertTrue(jsonld.contains("\"@type\":\"http://ex.org/Person\""), jsonld);
        org.junit.jupiter.api.Assertions.assertTrue(jsonld.contains("{\"@id\":\"http://ex.org/acme\"}"), jsonld);
        org.junit.jupiter.api.Assertions.assertTrue(jsonld.contains("\"@language\":\"tr\""), jsonld);

        String list = em.toRdf(em.findAll(Person.class), org.graphrd.RdfFormat.JSONLD);
        org.junit.jupiter.api.Assertions.assertTrue(list.startsWith("[") && list.endsWith("]"), list);
    }

    @Test
    void eagerReferencesComeBackInBulk() {
        // worksFor set for everyone, then read the list: references arrive,
        // resolved by one follow-up query per type, not one per row
        for (Person p : em.findAll(Person.class)) {
            p.worksFor = p.age >= 35 ? initech : acme;
            em.save(p);
        }
        List<Person> all = em.findAll(Person.class);
        assertEquals(3, all.size());
        all.forEach(p -> assertNotNull(p.worksFor, p.name + " has no company"));
        assertEquals(List.of("Carol"), all.stream()
                .filter(p -> p.worksFor.name.equals("Initech"))
                .map(p -> p.name).toList());
    }
}
