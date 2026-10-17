package org.graphrd.rdf;

import static org.graphrd.rdf.EntityQuery.Op.GT;
import static org.graphrd.rdf.EntityQuery.Op.LT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.graphrd.RdfClient;
import org.graphrd.TestServer;

class RdfEntityManagerIT {

    private static RdfClient client;
    private static RdfEntityManager em;

    private final Company acme = new Company("http://ex.org/acme", "Acme");
    private final Company initech = new Company("http://ex.org/initech", "Initech");

    @BeforeAll
    static void up() throws Exception {
        client = new RdfClient(TestServer.start());
        client.createRepository("orm");
        em = new RdfEntityManager(client, "orm");
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
                new Person("http://ex.org/alice", "Alice", 34, 82000, acme, List.of("Al", "Ace")),
                new Person("http://ex.org/bob", "Bob", 28, 61000, acme, null),
                new Person("http://ex.org/carol", "Carol", 41, 95000, initech, List.of("Caz")),
                new Person("http://ex.org/dave", "Dave", 52, 58000, initech, null)));
    }

    @Test
    void findReadsReferencesAndMultiValues() {
        Person alice = em.find(Person.class, "http://ex.org/alice");
        assertEquals("Alice", alice.name);
        assertEquals("Acme", alice.worksFor.name); // eager IRI reference
        assertEquals(List.of("Al", "Ace"), alice.nicks); // every value of the predicate
        assertNull(em.find(Person.class, "http://ex.org/nobody"));
    }

    @Test
    void findAllAndCount() {
        assertEquals(4, em.findAll(Person.class).size());
        assertEquals(2, em.findAll(Company.class).size());
        assertEquals(4, em.count(Person.class));
    }

    @Test
    void findByField() {
        List<Person> found = em.findBy(Person.class, "name", "Carol");
        assertEquals(1, found.size());
        assertEquals("Initech", found.get(0).worksFor.name);
    }

    @Test
    void criteria() {
        List<Person> over40 = em.query(Person.class).where("age", GT, 40).list();
        assertEquals(2, over40.size());
        assertEquals(1, em.query(Person.class).where("age", GT, 30).where("age", LT, 40).count());

        List<Person> top = em.query(Person.class)
                .whereLike("name", "A%")
                .orderBy("salary", true)
                .limit(1)
                .list();
        assertEquals("Alice", top.get(0).name);

        List<Person> atAcme = em.query(Person.class).whereIn("name", List.of("Alice", "Bob", "Nobody")).list();
        assertEquals(2, atAcme.size());

        // references filter by IRI pattern, not by literal comparison
        List<Person> acmePeople = em.query(Person.class).whereRef("worksFor", "http://ex.org/acme").list();
        assertEquals(2, acmePeople.size());
    }

    @Test
    void saveUpdatesAndIsIdempotent() {
        Person bob = em.find(Person.class, "http://ex.org/bob");
        bob.salary = 70000;
        em.save(bob);
        assertEquals(70000, em.find(Person.class, bob.id).salary);
        em.save(bob);
        em.save(bob);
        assertEquals(1, em.findBy(Person.class, "name", "Bob").size());
    }

    @Test
    void deleteRemovesEverything() {
        em.delete(Person.class, "http://ex.org/dave");
        assertNull(em.find(Person.class, "http://ex.org/dave"));
        assertEquals(3, em.count(Person.class));
        em.deleteAll(Person.class);
        assertEquals(0, em.count(Person.class));
        assertTrue(em.findAll(Company.class).stream().anyMatch(c -> c.name.equals("Acme")));
    }
}
