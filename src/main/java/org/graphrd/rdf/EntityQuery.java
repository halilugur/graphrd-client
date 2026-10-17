package org.graphrd.rdf;

import java.util.ArrayList;
import java.util.List;

import org.graphrd.RdfClient;

/**
 * A fluent query: {@code em.query(Person.class).where("age", Op.GT, 30)
 * .orderBy("salary", false).limit(10).list()}. Field names are Java field
 * names; every condition becomes a SPARQL filter on the mapped predicate.
 */
public final class EntityQuery<T> {

    /** The comparisons a {@link #where} can ask for. */
    public enum Op {
        /** equal */
        EQ("="),
        /** not equal */
        NE("!="),
        /** greater than */
        GT(">"),
        /** greater or equal */
        GE(">="),
        /** less than */
        LT("<"),
        /** less or equal */
        LE("<=");

        final String sparql;

        Op(String sparql) {
            this.sparql = sparql;
        }
    }

    private final RdfClient client;
    private final RdfEntityManager em;
    private final String repo;
    private final RdfEntityManager.Mapping mapping;
    private final Class<T> clazz;
    private final List<String> patterns = new ArrayList<>();
    private final List<String> orderPatterns = new ArrayList<>();
    private final List<String> orderKeys = new ArrayList<>();
    private long limit = -1;
    private long offset;

    EntityQuery(RdfClient client, String repo, RdfEntityManager em, Class<T> clazz) {
        this.client = client;
        this.em = em;
        this.repo = repo;
        this.clazz = clazz;
        this.mapping = em.mapping(clazz);
    }

    /** {@code where("age", Op.GT, 30)} — the field's predicate is compared to the value. */
    public EntityQuery<T> where(String field, Op op, Object value) {
        String p = predicate(field);
        String v = "?f" + patterns.size();
        patterns.add("?s <" + p + "> " + v + " . FILTER(" + v + " " + op.sparql + " " + RdfEntityManager.literal(value) + ")");
        return this;
    }

    /** Equal, the common case: {@code where("name", "Alice")}. */
    public EntityQuery<T> where(String field, Object value) {
        return where(field, Op.EQ, value);
    }

    /**
     * The field is an IRI reference to the given resource — a triple pattern,
     * not a literal comparison: {@code whereRef("worksFor", "http://…/acme")}.
     */
    public EntityQuery<T> whereRef(String field, String iri) {
        patterns.add("?s <" + predicate(field) + "> <" + iri + ">");
        return this;
    }

    /** Type-safe {@link #where}: {@code where(Person::getAge, Op.GT, 30)} — the
     *  field name comes from the getter, so a typo cannot compile. */
    public <V> EntityQuery<T> where(Getter<T, V> field, Op op, V value) {
        return where(field.fieldName(), op, value);
    }

    /** Type-safe {@link #whereLike}. */
    public EntityQuery<T> whereLike(Getter<T, String> field, String pattern) {
        return whereLike(field.fieldName(), pattern);
    }

    /** The field has no value. */
    public EntityQuery<T> whereNull(String field) {
        patterns.add("FILTER(NOT EXISTS { ?s <" + predicate(field) + "> ?fv" + patterns.size() + " })");
        return this;
    }

    /** The field has a value. */
    public EntityQuery<T> whereNotNull(String field) {
        patterns.add("?s <" + predicate(field) + "> ?fv" + patterns.size() + " .");
        return this;
    }

    /** A SQL-LIKE pattern, case-insensitively. */
    public EntityQuery<T> whereILike(String field, String pattern) {
        return whereLike(field, pattern, true);
    }

    /** Type-safe {@link #whereILike}. */
    public EntityQuery<T> whereILike(Getter<T, String> field, String pattern) {
        return whereILike(field.fieldName(), pattern);
    }

    /** A SQL-LIKE pattern with {@code %} and {@code _}, optionally ignoring case. */
    public EntityQuery<T> whereLike(String field, String pattern, boolean ignoreCase) {
        String p = predicate(field);
        String v = "?f" + patterns.size();
        String rx = likeRegex(pattern);
        String flags = ignoreCase ? ", \"i\"" : "";
        patterns.add("?s <" + p + "> " + v + " . FILTER(REGEX(STR(" + v + "), \"" + rx + "\"" + flags + "))");
        return this;
    }

    /** A SQL-LIKE pattern with {@code %} and {@code _}. */
    public EntityQuery<T> whereLike(String field, String pattern) {
        String p = predicate(field);
        String v = "?f" + patterns.size();
        patterns.add("?s <" + p + "> " + v + " . FILTER(REGEX(STR(" + v + "), \"" + likeRegex(pattern) + "\"))");
        return this;
    }

    private static String likeRegex(String pattern) {
        return pattern.replace("\\", "\\\\").replace(".", "\\.").replace("*", "\\*")
                .replace("+", "\\+").replace("?", "\\?").replace("(", "\\(").replace(")", "\\)")
                .replace("[", "\\[").replace("]", "\\]").replace("^", "\\^").replace("$", "\\$")
                .replace("%", ".*").replace("_", ".");
    }

    /** The field's value is one of the given values. */
    public EntityQuery<T> whereIn(String field, List<?> values) {
        String p = predicate(field);
        String v = "?f" + patterns.size();
        StringBuilder list = new StringBuilder();
        for (Object value : values) {
            if (!list.isEmpty()) {
                list.append(", ");
            }
            list.append(RdfEntityManager.literal(value));
        }
        patterns.add("?s <" + p + "> " + v + " . FILTER(" + v + " IN (" + list + "))");
        return this;
    }

    /** Order by a field, ascending unless {@code desc}; more keys chain with {@link #thenBy}. */
    public EntityQuery<T> orderBy(String field, boolean desc) {
        orderPatterns.clear();
        orderKeys.clear();
        return thenBy(field, desc);
    }

    /** A second (third, …) sort key after an {@link #orderBy}. */
    public EntityQuery<T> thenBy(String field, boolean desc) {
        String v = "?ord" + orderKeys.size();
        orderPatterns.add("OPTIONAL { ?s <" + predicate(field) + "> " + v + " }");
        orderKeys.add(desc ? "DESC(" + v + ")" : v);
        return this;
    }

    /** Type-safe {@link #thenBy}. */
    public <V> EntityQuery<T> thenBy(Getter<T, V> field, boolean desc) {
        return thenBy(field.fieldName(), desc);
    }

    /** Type-safe {@link #orderBy}: {@code orderBy(Person::getSalary, true)}. */
    public <V> EntityQuery<T> orderBy(Getter<T, V> field, boolean desc) {
        return orderBy(field.fieldName(), desc);
    }

    public EntityQuery<T> limit(long n) {
        this.limit = n;
        return this;
    }

    public EntityQuery<T> offset(long n) {
        this.offset = n;
        return this;
    }

    /** How many resources match. */
    public long count() {
        String body = "SELECT (COUNT(DISTINCT ?s) AS ?c) WHERE { ?s a <" + mapping.type() + ">"
                + joinPatterns() + " }";
        return Long.parseLong(client.query(repo, body).get(0).get("c"));
    }

    /** One page of the matching entities, with the total the filters match. */
    public Paged<T> page(int page, int size) {
        long total = count();
        List<T> items = limit(size).offset((long) page * size).list();
        return new Paged<>(items, total, page, size);
    }

    /** The matching entities, each read fully (their references too). */
    public List<T> list() {
        StringBuilder order = new StringBuilder();
        if (!orderKeys.isEmpty()) {
            order.append(" ORDER BY ").append(String.join(" ", orderKeys));
        }
        StringBuilder q = new StringBuilder("SELECT DISTINCT ?s WHERE { ?s a <")
                .append(mapping.type()).append(">").append(joinPatterns());
        for (String op : orderPatterns) {
            q.append(" ").append(op);
        }
        q.append(" }").append(order);
        if (limit >= 0) {
            q.append(" LIMIT ").append(limit);
        }
        if (offset > 0) {
            q.append(" OFFSET ").append(offset);
        }
        List<String> iris = new ArrayList<>();
        for (var row : client.query(repo, q.toString())) {
            iris.add(row.get("s"));
        }
        return em.materializeByIris(clazz, iris); // rows and references in bulk
    }

    private String joinPatterns() {
        StringBuilder out = new StringBuilder();
        for (String pattern : patterns) {
            out.append(" . ").append(pattern);
        }
        return out.toString();
    }

    private String predicate(String field) {
        return mapping.props().stream()
                .filter(p -> p.field().getName().equals(field))
                .map(RdfEntityManager.Prop::predicate)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no mapped field " + field));
    }

    /** A page of results: the items plus the total the filters match. */
    public record Paged<T>(List<T> items, long total, int page, int size) {
    }
}
