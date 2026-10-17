package org.graphrd.rdf;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.graphrd.RdfClient;

/**
 * The object mapper over SPARQL: annotated entities become resources,
 * {@code find}/{@code findAll}/{@code query} become SELECTs, and
 * {@code save}/{@code delete} become SPARQL updates. Every operation rides
 * the indexed query path of the server; no SQL is involved.
 *
 * <p>{@code save} is one request — a {@code ;}-chained delete-then-insert,
 * one transaction, idempotent. {@code saveSmart} compares the entity with
 * what is stored and touches only the predicates that differ. Lists are read
 * in bulk: one query for the rows, one per referenced type (no per-row
 * follow-up queries).
 */
public final class RdfEntityManager implements AutoCloseable {

    /** One annotated class: its type IRI, id field, predicate fields and inverse references. */
    record Mapping(String type, Field id, boolean generatedIds, List<Prop> props, List<Inv> inverses) {
    }

    /** An {@link RdfInverse @RdfInverse} field: the predicate pointing back at us. */
    record Inv(String predicate, Field field) {
    }

    record Prop(String predicate, Field field, String lang, String datatype) {
    }

    /** An eager reference waiting for the bulk fetch (a list item sets {@code asList}). */
    private record PendingRef(Object entity, Prop prop, String iri, boolean asList) {
    }

    private final RdfClient client;
    private final String repo;
    private final String namespace;
    private final Map<Class<?>, Mapping> mappings = new ConcurrentHashMap<>();

    /** Manager without a namespace: full IRIs in the annotations. */
    public RdfEntityManager(RdfClient client, String repo) {
        this(client, repo, "");
    }

    /**
     * Manager with a namespace: {@code @RdfProperty("salary")} and
     * {@code @RdfType("Employee")} short names resolve against it (full IRIs
     * still work unchanged), and {@code {ns}} in a @Query stands for it —
     * one property to move the whole vocabulary.
     */
    public RdfEntityManager(RdfClient client, String repo, String namespace) {
        this.client = client;
        this.repo = repo;
        this.namespace = namespace == null ? "" : namespace;
    }

    /** The namespace short names resolve against ("" = full IRIs expected). */
    public String namespace() {
        return namespace;
    }

    // ------------------------------------------------------------ queries

    /** One entity by its IRI, or null when absent. */
    public <T> T find(Class<T> clazz, String id) {
        Map<String, Map<String, List<String>>> bySubject = bySubject(
                "SELECT ?s ?p ?o WHERE { VALUES ?s { <" + id + "> } ?s ?p ?o }");
        return bySubject.isEmpty() ? null : materializeAll(clazz, bySubject).get(0);
    }

    /** Every entity of the class — two queries total: the rows, then the references. */
    public <T> List<T> findAll(Class<T> clazz) {
        return materializeAll(clazz, bySubject(
                "SELECT ?s ?p ?o WHERE { ?s a <" + mapping(clazz).type() + "> ; ?p ?o }"));
    }

    /** How many resources the class has. */
    public long count(Class<?> clazz) {
        return query(clazz).count();
    }

    /** A raw SELECT over this manager's repository — rows as variable name to
     *  value. {@code {ns}} stands for the namespace, so @Query strings need
     *  not hard-code it. */
    public List<Map<String, String>> select(String sparql) {
        return client.query(repo, sparql.replace("{ns}", namespace));
    }

    /** A fluent query: {@code query(Person.class).where(Person::getAge, Op.GT, 30).limit(10).list()}. */
    public <T> EntityQuery<T> query(Class<T> clazz) {
        return new EntityQuery<>(client, repo, this, clazz);
    }

    /** Entities whose field equals a value. */
    public <T> List<T> findBy(Class<T> clazz, String field, Object value) {
        return query(clazz).where(field, EntityQuery.Op.EQ, value).list();
    }

    /** Entities for a known list of IRIs, references included, in bulk. */
    public <T> List<T> findAllByIds(Class<T> clazz, List<String> iris) {
        return materializeByIris(clazz, iris);
    }

    <T> List<T> materializeByIris(Class<T> clazz, List<String> iris) {
        if (iris.isEmpty()) {
            return List.of();
        }
        StringBuilder values = new StringBuilder();
        for (String iri : iris) {
            values.append(" <").append(iri).append(">");
        }
        return materializeAll(clazz, bySubject(
                "SELECT ?s ?p ?o WHERE { VALUES ?s {" + values + " } ?s ?p ?o }"));
    }

    /** A Spring-Data-style derived-query interface over this manager. */
    public <I> I repository(Class<I> iface) {
        return RdfRepositories.proxy(iface, this);
    }

    // ------------------------------------------------------------ changes

    /**
     * Writes the whole entity in one request: the resource's present
     * predicates go, then the current field values arrive — a {@code ;}-chained
     * update, so one transaction, and idempotent.
     */
    public void save(Object entity) {
        client.update(repo, "DELETE WHERE { <" + idOf(entity) + "> ?p ?o } ;\n"
                + "INSERT DATA { " + insertBlock(entity) + " }");
    }

    /** Saves every entity — one chained request, one transaction. */
    public void saveAll(Iterable<?> entities) {
        StringBuilder update = new StringBuilder();
        for (Object entity : entities) {
            if (!update.isEmpty()) {
                update.append(" ;\n");
            }
            update.append("DELETE WHERE { <").append(idOf(entity)).append("> ?p ?o } ;\n")
                    .append("INSERT DATA { ").append(insertBlock(entity)).append(" }");
        }
        if (!update.isEmpty()) {
            client.update(repo, update.toString());
        }
    }

    /**
     * Writes only the difference: predicates whose value set already matches
     * are not touched, which keeps the write-ahead log and the inference work
     * small when most of an entity is unchanged. Also one request.
     */
    public void saveSmart(Object entity) {
        Mapping m = mapping(entity.getClass());
        String id = idOf(entity);
        Map<String, List<String>> stored = new LinkedHashMap<>();
        for (Map<String, String> row : client.query(repo,
                "SELECT ?p ?o WHERE { <" + id + "> ?p ?o }")) {
            stored.computeIfAbsent(row.get("p"), k -> new ArrayList<>()).add(row.get("o"));
        }
        StringBuilder update = new StringBuilder();
        boolean chained = false;
        for (Prop p : m.props()) {
            List<String> want = targetValues(entity, p);
            if (want == null) {
                want = List.of();
            }
            List<String> have = stored.getOrDefault(p.predicate(), List.of());
            if (have.size() == want.size() && new java.util.HashSet<>(have).containsAll(want)) {
                continue; // unchanged predicate: no write at all
            }
            if (!have.isEmpty()) {
                update.append(chained ? " ;\n" : "").append("DELETE WHERE { <").append(id)
                        .append("> <").append(p.predicate()).append("> ?o }");
                chained = true;
            }
            if (!want.isEmpty()) {
                update.append(chained ? " ;\n" : "").append("INSERT DATA { <").append(id).append("> ");
                for (String v : want) {
                    update.append("<").append(p.predicate()).append("> ").append(v).append(" ");
                }
                update.append("}");
                chained = true;
            }
        }
        if (!chained) {
            return; // nothing differs
        }
        client.update(repo, update.toString());
    }

    /** Removes the resource with all its predicates. */
    public void delete(Class<?> clazz, String id) {
        client.update(repo, "DELETE WHERE { <" + id + "> ?p ?o }");
    }

    /** Removes every resource of the class. */
    public void deleteAll(Class<?> clazz) {
        client.update(repo, "DELETE WHERE { ?s a <" + mapping(clazz).type() + "> ; ?p ?o }");
    }

    @Override
    public void close() {
    }

    // ------------------------------------------------------------ writing out

    /** The entity as RDF in the asked format (inverse fields are not written). */
    public String toRdf(Object entity, org.graphrd.RdfFormat format) {
        return switch (format) {
            case TURTLE -> turtle(entity, true);
            case NTRIPLES -> ntriples(entity);
            case JSONLD -> jsonld(entity);
        };
    }

    /** Several entities as one RDF document (a @graph in JSON-LD). */
    public String toRdf(Iterable<?> entities, org.graphrd.RdfFormat format) {
        return switch (format) {
            case TURTLE -> {
                StringBuilder out = prefixLine();
                for (Object e : entities) {
                    out.append('\n').append(turtle(e, false));
                }
                yield out.toString();
            }
            case NTRIPLES -> {
                StringBuilder out = new StringBuilder();
                for (Object e : entities) {
                    out.append(ntriples(e));
                }
                yield out.toString();
            }
            case JSONLD -> {
                StringBuilder out = new StringBuilder("[");
                boolean first = true;
                for (Object e : entities) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(jsonld(e));
                }
                yield out.append(']').toString();
            }
        };
    }

    private StringBuilder prefixLine() {
        return namespace.isEmpty() ? new StringBuilder()
                : new StringBuilder("@prefix ex: <").append(namespace).append("> .\n\n");
    }

    /** Turtle with the prefixed predicate form (single) or the bare form (in a list). */
    private String turtle(Object entity, boolean withPrefix) {
        Mapping m = mapping(entity.getClass());
        String id = idOf(entity);
        StringBuilder out = withPrefix ? prefixLine() : new StringBuilder();
        out.append("<").append(id).append("> a ").append(turtleTerm(m.type())).append(';');
        for (Prop p : m.props()) {
            java.util.List<String> vals = targetValues(entity, p);
            if (vals == null) {
                continue;
            }
            out.append("\n    ").append(turtleTerm(p.predicate())).append(' ');
            out.append(String.join(", ", vals));
            out.append(';');
        }
        return out.deleteCharAt(out.length() - 1).append(" .").toString();
    }

    private String turtleTerm(String iri) {
        if (!namespace.isEmpty() && iri.startsWith(namespace)) {
            String local = iri.substring(namespace.length());
            if (local.indexOf('/') < 0 && local.indexOf('#') < 0) {
                return "ex:" + local;
            }
        }
        return "<" + iri + ">";
    }

    private String ntriples(Object entity) {
        Mapping m = mapping(entity.getClass());
        String id = idOf(entity);
        StringBuilder out = new StringBuilder();
        out.append("<").append(id).append("> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <").append(m.type()).append("> .\n");
        for (Prop p : m.props()) {
            java.util.List<String> vals = targetValues(entity, p);
            if (vals == null) {
                continue;
            }
            for (String v : vals) {
                out.append("<").append(id).append("> <").append(p.predicate()).append("> ")
                        .append(v).append(" .\n");
            }
        }
        return out.toString();
    }

    /** Expanded JSON-LD node object for one entity. */
    private String jsonld(Object entity) {
        Mapping m = mapping(entity.getClass());
        String id = idOf(entity);
        StringBuilder out = new StringBuilder("{\"@id\":\"").append(escape(id)).append("\",\"@type\":\"")
                .append(m.type()).append("\"");
        for (Prop p : m.props()) {
            java.util.List<String> vals = targetValues(entity, p);
            if (vals == null) {
                continue;
            }
            out.append(",\"").append(p.predicate()).append("\":[");
            out.append(String.join(",", vals.stream().map(this::jsonldTerm).toList()));
            out.append(']');
        }
        return out.append('}').toString();
    }

    private String jsonldTerm(String term) {
        if (term.startsWith("<")) { // an IRI reference
            return "{\"@id\":\"" + escape(term.substring(1, term.length() - 1)) + "\"}";
        }
        if (term.matches("\"[^\"]*\"@\\w+")) { // lang-tagged
            int at = term.lastIndexOf('@');
            return "{\"@value\":" + jsonQuoted(term.substring(0, at))
                    + ",\"@language\":\"" + term.substring(at + 1) + "\"}";
        }
        int hat = term.indexOf("^^");
        if (hat > 0 && term.endsWith(">")) { // datatype
            return "{\"@value\":" + jsonQuoted(term.substring(0, hat))
                    + ",\"@type\":\"" + escape(term.substring(hat + 3, term.length() - 1)) + "\"}";
        }
        if (term.startsWith("\"")) { // plain string literal
            return jsonQuoted(term);
        }
        return term; // a number or boolean
    }

    private static String jsonQuoted(String literal) {
        return literal.startsWith("\"") && literal.endsWith("\"") && literal.length() >= 2
                ? literal
                : "\"" + escape(literal) + "\"";
    }

    // ------------------------------------------------------------ mapping

    Mapping mapping(Class<?> clazz) {
        return mappings.computeIfAbsent(clazz, this::buildMapping);
    }

    /** The IRI of an entity (used by the derived-query proxy). */
    public String idOfPublic(Object entity) {
        return idOf(entity);
    }

    /** Whether the named field holds an IRI reference (an entity or a {@link Ref}). */
    public boolean isReferenceField(Class<?> clazz, String field) {
        return mapping(clazz).props().stream()
                .filter(p -> p.field().getName().equals(field))
                .findFirst()
                .map(p -> p.field().getType().isAnnotationPresent(RdfType.class)
                        || p.field().getType() == Ref.class
                        || (p.field().getType() == List.class && listItemIsEntity(p.field())))
                .orElse(false);
    }

    private static boolean listItemIsEntity(Field f) {
        return ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0] instanceof Class<?> c
                && c.isAnnotationPresent(RdfType.class);
    }

    /** A short name gets the namespace; a full IRI (or an empty namespace) passes unchanged. */
    private String resolve(String name) {
        if (name.contains("://") || name.startsWith("urn:") || namespace.isEmpty()) {
            return name;
        }
        return namespace.endsWith("/") || namespace.endsWith("#") ? namespace + name : namespace + "/" + name;
    }

    private Mapping buildMapping(Class<?> c) {
        RdfType type = c.getAnnotation(RdfType.class);
        if (type == null) {
            throw new IllegalArgumentException(c + " has no @RdfType");
        }
        String typeIri = resolve(type.value());
        Field id = null;
        boolean generated = false;
        List<Prop> props = new ArrayList<>();
        List<Inv> inverses = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.isAnnotationPresent(RdfId.class)) {
                id = f;
                generated = f.getAnnotation(RdfId.class).generated();
            } else if (f.isAnnotationPresent(RdfProperty.class)) {
                RdfProperty a = f.getAnnotation(RdfProperty.class);
                props.add(new Prop(resolve(a.value()), f, a.lang(), a.datatype()));
            } else if (f.isAnnotationPresent(RdfInverse.class)) {
                inverses.add(new Inv(resolve(f.getAnnotation(RdfInverse.class).value()), f));
            }
        }
        if (id == null) {
            throw new IllegalArgumentException(c + " has no @RdfId field");
        }
        return new Mapping(typeIri, id, generated, props, inverses);
    }

    // ------------------------------------------------------------ materialization

    /** ?s ?p ?o rows grouped as subject → predicate → values. */
    private Map<String, Map<String, List<String>>> bySubject(String sparql) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (Map<String, String> row : client.query(repo, sparql)) {
            out.computeIfAbsent(row.get("s"), k -> new LinkedHashMap<>())
                    .computeIfAbsent(row.get("p"), k -> new ArrayList<>())
                    .add(row.get("o"));
        }
        return out;
    }

    /** Entities with their eager references resolved in bulk (one query per
     *  reference type), then the inverse references filled in. */
    private <T> List<T> materializeAll(Class<T> clazz, Map<String, Map<String, List<String>>> bySubject) {
        Mapping m = mapping(clazz);
        List<PendingRef> pending = new ArrayList<>();
        Map<String, T> byId = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> e : bySubject.entrySet()) {
            byId.put(e.getKey(), materialize(clazz, m, e.getKey(), e.getValue(), pending));
        }
        resolveInBulk(pending);
        fillInverses(clazz, m, byId);
        return new ArrayList<>(byId.values());
    }

    /** @RdfInverse fields: everything that points at each entity, in bulk. */
    private <T> void fillInverses(Class<T> clazz, Mapping m, Map<String, T> byId) {
        if (m.inverses().isEmpty() || byId.isEmpty()) {
            return;
        }
        for (Inv inv : m.inverses()) {
            Class<?> target = (Class<?>) ((ParameterizedType) inv.field().getGenericType()).getActualTypeArguments()[0];
            StringBuilder values = new StringBuilder();
            for (String id : byId.keySet()) {
                values.append(" <").append(id).append(">");
            }
            // one query per inverse: who points at us through this predicate?
            Map<String, List<String>> pointing = new LinkedHashMap<>();
            for (Map<String, String> row : client.query(repo,
                    "SELECT ?s ?o WHERE { VALUES ?o {" + values + " } ?s <" + inv.predicate() + "> ?o }")) {
                pointing.computeIfAbsent(row.get("o"), k -> new ArrayList<>()).add(row.get("s"));
            }
            if (pointing.isEmpty()) {
                continue;
            }
            List<String> iris = pointing.values().stream().flatMap(List::stream).distinct().toList();
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Object t : materializeByIris(target, iris)) {
                try {
                    resolved.put(mapping(target).id().get(t).toString(), t);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            for (Map.Entry<String, T> e : byId.entrySet()) {
                List<Object> refs = new ArrayList<>();
                for (String iri : pointing.getOrDefault(e.getKey(), List.of())) {
                    Object r = resolved.get(iri);
                    if (r != null) {
                        refs.add(r);
                    }
                }
                setField(e.getValue(), inv.field(), refs);
            }
        }
    }

    private void resolveInBulk(List<PendingRef> pending) {
        if (pending.isEmpty()) {
            return;
        }
        // one query per referenced type: VALUES lists every IRI of that type
        Map<Class<?>, List<PendingRef>> byType = new LinkedHashMap<>();
        pending.forEach(p -> byType.computeIfAbsent(typeOf(p.prop()), k -> new ArrayList<>()).add(p));
        for (Map.Entry<Class<?>, List<PendingRef>> e : byType.entrySet()) {
            Class<?> type = e.getKey();
            StringBuilder iris = new StringBuilder();
            for (PendingRef p : e.getValue()) {
                iris.append(" <").append(p.iri()).append(">");
            }
            Map<String, Map<String, List<String>>> bySubject = bySubject(
                    "SELECT ?s ?p ?o WHERE { VALUES ?s {" + iris + " } ?s ?p ?o }");
            Mapping rm = mapping(type);
            // materialize each referenced resource once
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (PendingRef p : e.getValue()) {
                if (resolved.containsKey(p.iri())) {
                    continue;
                }
                List<PendingRef> nested = new ArrayList<>(); // one level, as with find
                resolved.put(p.iri(), bySubject.containsKey(p.iri())
                        ? materialize(type, rm, p.iri(), bySubject.get(p.iri()), nested)
                        : null);
            }
            // group by (entity, field): a single-valued field takes one, a list field collects all
            record Key(Object entity, Field field) {
            }
            Map<Key, List<PendingRef>> groups = new LinkedHashMap<>();
            for (PendingRef p : e.getValue()) {
                groups.computeIfAbsent(new Key(p.entity(), p.prop().field()), k -> new ArrayList<>()).add(p);
            }
            for (Map.Entry<Key, List<PendingRef>> g : groups.entrySet()) {
                boolean asList = g.getValue().get(0).asList();
                if (asList) {
                    List<Object> refs = new ArrayList<>();
                    for (PendingRef p : g.getValue()) {
                        Object r = resolved.get(p.iri());
                        if (r != null) {
                            refs.add(r);
                        }
                    }
                    setField(g.getKey().entity(), g.getKey().field(), refs);
                } else {
                    setField(g.getKey().entity(), g.getKey().field(), resolved.get(g.getValue().get(0).iri()));
                }
            }
        }
    }

    private Class<?> typeOf(Prop p) {
        if (p.field().getType() == Ref.class || p.field().getType() == List.class) {
            return (Class<?>) ((ParameterizedType) p.field().getGenericType()).getActualTypeArguments()[0];
        }
        return p.field().getType();
    }

    private <T> T materialize(Class<T> clazz, Mapping m, String id,
                              Map<String, List<String>> byPred, List<PendingRef> pending) {
        try {
            var ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            T entity = clazz.cast(ctor.newInstance());
            m.id().set(entity, id);
            for (Prop p : m.props()) {
                List<String> vals = byPred.get(p.predicate());
                if (vals == null || vals.isEmpty()) {
                    continue;
                }
                Class<?> type = p.field().getType();
                if (type == List.class) {
                    Class<?> item = (Class<?>) ((ParameterizedType) p.field().getGenericType()).getActualTypeArguments()[0];
                    List<Object> out = new ArrayList<>();
                    for (String v : vals) {
                        Object r = read(item, v, p, pending, entity);
                        if (r != null || item.isAnnotationPresent(RdfType.class)) {
                            out.add(r); // a pending reference lands via the bulk pass
                        }
                    }
                    p.field().set(entity, out);
                } else {
                    p.field().set(entity, read(type, vals.get(0), p, pending, entity));
                }
            }
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object read(Class<?> type, String value, Prop p, List<PendingRef> pending, Object entity) {
        if (type == String.class) {
            return value;
        }
        if (type == Integer.class || type == int.class) {
            return (int) Math.round(Double.parseDouble(value));
        }
        if (type == Long.class || type == long.class) {
            return (long) Double.parseDouble(value);
        }
        if (type == Double.class || type == double.class) {
            return Double.parseDouble(value);
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.parseBoolean(value);
        }
        if (type == Ref.class) {
            @SuppressWarnings("unchecked")
            Ref<Object> ref = new Ref<>(value, (Class<Object>) typeOf(p),
                    (t, iri) -> find(t, iri));
            return ref;
        }
        if (type.isAnnotationPresent(RdfType.class)) {
            boolean asList = p.field().getType() == List.class;
            pending.add(new PendingRef(entity, p, value, asList)); // resolved in bulk
            return null;
        }
        throw new IllegalStateException("cannot map " + type + " from " + value);
    }

    private static void setField(Object entity, Field f, Object value) {
        try {
            f.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ writing

    private String idOf(Object entity) {
        Mapping m = mapping(entity.getClass());
        try {
            Object v = m.id().get(entity);
            if (v == null) {
                if (!m.generatedIds()) {
                    throw new IllegalStateException("the @RdfId field is null (mark it generated = true to have one minted)");
                }
                // the type's namespace with the class's local name: …/person/a1b2c3
                String type = m.type();
                int slash = type.lastIndexOf('/');
                String ns = slash < 0 ? type + "/" : type.substring(0, slash + 1);
                String local = slash < 0 ? type : type.substring(slash + 1);
                v = ns + local.toLowerCase() + "/" + java.util.UUID.randomUUID().toString().substring(0, 8);
                m.id().set(entity, v);
            }
            return v.toString();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The IRI an annotated entity or a {@link Ref} stands for. */
    static String iriOf(Object v) {
        if (v instanceof Ref<?> ref) {
            return ref.iri();
        }
        if (v.getClass().isAnnotationPresent(RdfType.class)) {
            return idOfAnnotated(v);
        }
        return null;
    }

    /** The INSERT DATA body for the entity, with lang/datatype shaping. */
    private String insertBlock(Object entity) {
        Mapping m = mapping(entity.getClass());
        StringBuilder out = new StringBuilder("<").append(idOf(entity)).append("> a <").append(m.type()).append(">");
        for (Prop p : m.props()) {
            List<String> vals = targetValues(entity, p);
            if (vals == null) {
                continue;
            }
            for (String v : vals) {
                out.append(" ;\n  <").append(p.predicate()).append("> ").append(v);
            }
        }
        return out.toString();
    }

    /** The entity's written values for a predicate, as SPARQL terms — or null when the field is null. */
    private List<String> targetValues(Object entity, Prop p) {
        Object v;
        try {
            v = p.field().get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> values) {
            List<String> out = new ArrayList<>();
            for (Object value : values) {
                out.add(term(value, p));
            }
            return out;
        }
        return List.of(term(v, p));
    }

    /** One field value as a SPARQL term: IRI for references, literal with lang/datatype otherwise. */
    private static String term(Object v, Prop p) {
        if (v instanceof Ref<?> ref) {
            return "<" + ref.iri() + ">";
        }
        if (v.getClass().isAnnotationPresent(RdfType.class)) {
            return "<" + idOfAnnotated(v) + ">";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        String text = "\"" + escape(v.toString()) + "\"";
        if (p != null && !p.lang().isEmpty()) {
            return text + "@" + p.lang();
        }
        if (p != null && !p.datatype().isEmpty()) {
            return text + "^^<" + p.datatype() + ">";
        }
        return text;
    }

    /** A literal term for queries and annotations (`@Query` parameters use it). */
    public static String literal(Object v) {
        return term(v, null);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String idOfAnnotated(Object v) {
        for (Field f : v.getClass().getDeclaredFields()) {
            if (f.isAnnotationPresent(RdfId.class)) {
                f.setAccessible(true);
                try {
                    return f.get(v).toString();
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException(v.getClass() + " has no @RdfId field");
    }
}
