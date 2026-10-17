package org.graphrd;

/** The RDF wire formats an entity (or a list) can be written out as. */
public enum RdfFormat {
    /** Turtle, with a PREFIX line for the manager's namespace. */
    TURTLE("text/turtle"),
    /** N-Triples: one triple per line. */
    NTRIPLES("application/n-triples"),
    /** Expanded JSON-LD: full IRIs, typed values, references as @id. */
    JSONLD("application/ld+json");

    /** The canonical media type of the format. */
    public final String mediaType;

    RdfFormat(String mediaType) {
        this.mediaType = mediaType;
    }

    /** The format a media type asks for, or null when it is not RDF. */
    public static RdfFormat of(String mediaType) {
        if (mediaType == null) {
            return null;
        }
        String m = mediaType.toLowerCase();
        if (m.contains("turtle")) {
            return TURTLE;
        }
        if (m.contains("n-triples") || m.contains("ntriples")) {
            return NTRIPLES;
        }
        if (m.contains("ld+json") || m.contains("json")) {
            return JSONLD; // any JSON answers as JSON-LD on the RDF endpoints
        }
        return null;
    }
}
