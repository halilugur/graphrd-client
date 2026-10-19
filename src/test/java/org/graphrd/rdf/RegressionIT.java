package org.graphrd.rdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.graphrd.RdfClient;
import org.graphrd.TestServer;

/** Regressions: each test pins one bug that once slipped through. */
class RegressionIT {

    private static RdfClient client;
    private static RdfEntityManager em;

    /** Fields inherited from a mapped superclass. */
    abstract static class Base {
        @RdfId(generated = true)
        String id;

        @RdfProperty("http://ex.org/name")
        String name;
    }

    @RdfType("http://ex.org/Item")
    static class Item extends Base {

        @RdfProperty("http://ex.org/code")
        Long code;

        @RdfProperty("http://ex.org/minIncome")
        Integer minIncome;

        @RdfProperty(value = "http://ex.org/label", lang = "en-US")
        String label;

        Item() {
        }

        Item(String id, String name, Long code, Integer minIncome) {
            this.id = id;
            this.name = name;
            this.code = code;
            this.minIncome = minIncome;
        }
    }

    /** Two classes whose inverse fields point at each other. */
    @RdfType("http://ex.org/Node")
    static class Node {
        @RdfId
        String id;

        @RdfProperty("http://ex.org/next")
        Node next;

        @RdfInverse("http://ex.org/next")
        List<Node> previous;

        Node() {
        }
    }

    interface Items extends RdfRepositories.RdfRepository<Item> {

        Item findByName(String name);

        List<Item> findByNameIn(List<String> names);

        List<Item> findByMinIncome(int minIncome);

        int countByName(String name);
    }

    @BeforeAll
    static void up() throws Exception {
        client = new RdfClient(TestServer.start());
        client.createRepository("regress");
        em = new RdfEntityManager(client, "regress");
    }

    @AfterAll
    static void down() {
        TestServer.stop();
    }

    @BeforeEach
    void fresh() {
        em.deleteAll(Item.class);
        em.deleteAll(Node.class);
        em.saveAll(List.of(
                new Item("http://ex.org/i1", "Alice", 1L, 10),
                new Item("http://ex.org/i2", "Malice", 2L, 20),
                new Item("http://ex.org/i3", "A.B. Smith", 3L, 30),
                new Item("http://ex.org/i4", "Zed", null, 40)));
    }

    @Test
    void notNullFollowedByAnotherCondition() {
        assertEquals(3, em.query(Item.class).whereNotNull("code").where("minIncome", EntityQuery.Op.GE, 10).count());
        assertEquals(3, em.query(Item.class).whereNotNull("code").where("minIncome", EntityQuery.Op.GE, 10).list().size());
    }

    @Test
    void likeIsAnchoredLikeSql() {
        // "Al%" is a prefix match: "Malice" must not match
        assertEquals(List.of("Alice"), names(em.query(Item.class).whereLike("name", "Al%").list()));
        assertEquals(List.of("Alice", "Malice"),
                names(em.query(Item.class).whereLike("name", "%lice").orderBy("name", false).list()));
        assertEquals(List.of("Alice"), names(em.query(Item.class).whereILike("name", "aL%").list()));
    }

    @Test
    void likeEscapesRegexAndStringSpecials() {
        assertEquals(List.of("A.B. Smith"), names(em.query(Item.class).whereLike("name", "A.B.%").list()));
        assertEquals(0, em.query(Item.class).whereLike("name", "A\"B%").count());
        assertEquals(0, em.query(Item.class).whereLike("name", "{x}|y\\%").count());
    }

    @Test
    void listKeepsTheRequestedOrder() {
        List<String> desc = names(em.query(Item.class).orderBy("minIncome", true).list());
        assertEquals(List.of("Zed", "A.B. Smith", "Malice", "Alice"), desc);
        List<String> asc = names(em.query(Item.class).orderBy("minIncome", false).list());
        assertEquals(List.of("Alice", "Malice", "A.B. Smith", "Zed"), asc);
        assertEquals(List.of("Zed", "Alice"),
                names(em.findAllByIds(Item.class, List.of("http://ex.org/i4", "http://ex.org/i1"))));
    }

    @Test
    void inheritedFieldsAreMapped() {
        Item i = em.find(Item.class, "http://ex.org/i1");
        assertEquals("Alice", i.name);
        Item fresh = new Item(null, "Generated", 9L, 1);
        em.save(fresh);
        assertNotNull(fresh.id);
        assertEquals("Generated", em.find(Item.class, fresh.id).name);
    }

    @Test
    void equalityIgnoringCase() {
        assertEquals(List.of("Alice"), names(em.query(Item.class).whereIgnoreCase("name", "aLiCe").list()));
        assertEquals(0, em.query(Item.class).whereIgnoreCase("name", "alic").count());
    }

    @Test
    void longsKeepFullPrecision() {
        Item big = new Item("http://ex.org/big", "Big", 9007199254740993L, 1);
        em.save(big);
        assertEquals(9007199254740993L, em.find(Item.class, big.id).code);
    }

    @Test
    void saveSmartOnANewEntityWritesItsType() {
        Item n = new Item("http://ex.org/new", "New", 5L, 5);
        em.saveSmart(n);
        assertEquals(5, em.count(Item.class));
        assertEquals("New", em.find(Item.class, n.id).name);
    }

    @Test
    void jsonLdWithRegionLanguageTag() {
        Item i = new Item("http://ex.org/x", "X", 1L, 1);
        i.label = "say \"hi\"";
        String json = em.toRdf(i, org.graphrd.RdfFormat.JSONLD);
        assertTrue(json.contains("{\"@value\":\"say \\\"hi\\\"\",\"@language\":\"en-US\"}"), json);
    }

    @Test
    void derivedQueriesEdgeCases() {
        Items items = em.repository(Items.class);
        assertEquals("Alice", items.findByName("Alice").name);
        assertNull(items.findByName("Nobody"));
        assertEquals(List.of("Alice", "Zed"),
                names(items.findByNameIn(List.of("Alice", "Zed"))).stream().sorted().toList());
        assertEquals(List.of("Malice"), names(items.findByMinIncome(20)));
        assertEquals(1, items.countByName("Alice"));
    }

    @Test
    void cyclicInversesTerminate() {
        client.update("regress", "INSERT DATA { "
                + "<http://ex.org/n1> a <http://ex.org/Node> ; <http://ex.org/next> <http://ex.org/n2> . "
                + "<http://ex.org/n2> a <http://ex.org/Node> ; <http://ex.org/next> <http://ex.org/n1> . }");
        List<Node> nodes = em.findAll(Node.class);
        assertEquals(2, nodes.size());
        Node n1 = em.find(Node.class, "http://ex.org/n1");
        assertEquals("http://ex.org/n2", n1.previous.get(0).id);
    }

    private static List<String> names(List<Item> items) {
        return items.stream().map(i -> i.name).toList();
    }
}
