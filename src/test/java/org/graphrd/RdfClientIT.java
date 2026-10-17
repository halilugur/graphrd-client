package org.graphrd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RdfClientIT {

    private static RdfClient client;

    @BeforeAll
    static void up() throws Exception {
        client = new RdfClient(TestServer.start());
        client.createRepository("it");
    }

    @AfterAll
    static void down() {
        TestServer.stop();
    }

    @Test
    void pingAndRepositories() {
        assertTrue(client.ping());
        client.createRepository("it"); // idempotent
        assertTrue(client.listRepositories().contains("it"));
        client.createRepository("it2");
        assertTrue(client.listRepositories().contains("it2"));
        client.deleteRepository("it2");
        assertFalse(client.listRepositories().contains("it2"));
    }

    @Test
    void queryAskUpdate() {
        client.update("it", "INSERT DATA { <http://ex.org/a> <http://ex.org/name> \"Ann\" ; <http://ex.org/age> 31 }");
        List<java.util.Map<String, String>> rows =
                client.query("it", "SELECT ?n WHERE { <http://ex.org/a> <http://ex.org/name> ?n }");
        assertEquals("Ann", rows.get(0).get("n"));
        assertTrue(client.ask("it", "ASK { <http://ex.org/a> <http://ex.org/age> 31 }"));
        assertFalse(client.ask("it", "ASK { <http://ex.org/a> <http://ex.org/age> 99 }"));
        client.update("it", "DELETE WHERE { <http://ex.org/a> ?p ?o }");
        assertTrue(client.query("it", "SELECT ?s WHERE { ?s ?p ?o }").isEmpty());
    }

    @Test
    void statementsRoundTrip() {
        client.addStatements("it", "@prefix ex: <http://ex.org/> . ex:b ex:name \"Bob\" .", "text/turtle");
        String turtle = client.getStatements("it", "text/turtle");
        assertTrue(turtle.contains("Bob"));
        String jsonLd = client.getStatements("it", "application/ld+json");
        assertTrue(jsonLd.contains("\"Bob\"") || jsonLd.contains("Bob"));
    }
}
