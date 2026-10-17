package org.graphrd.rdf;

/**
 * A lazy IRI reference: the field stores the IRI, the entity arrives only
 * when {@link #get()} is called (one query, cached for the lifetime of the Ref).
 */
public final class Ref<T> {

    private final String iri;
    private final Class<T> type;
    private final Resolver resolver;
    private T cached;

    Ref(String iri, Class<T> type, Resolver resolver) {
        this.iri = iri;
        this.type = type;
        this.resolver = resolver;
    }

    public String iri() {
        return iri;
    }

    /** The referenced entity, fetched on first call. */
    @SuppressWarnings("unchecked")
    public T get() {
        if (cached == null && !iri.isEmpty()) {
            cached = (T) resolver.resolve(type, iri);
        }
        return cached;
    }

    @FunctionalInterface
    interface Resolver {
        Object resolve(Class<?> type, String iri);
    }
}
