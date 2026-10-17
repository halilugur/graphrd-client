package org.graphrd.rdf;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The other direction of a reference: everything that points at this entity
 * through the given predicate, read back as a list — the RDF shape of JPA's
 * {@code @OneToMany}. Read-only: writes go through the owning side.
 *
 * <pre>{@code
 * @RdfType("http://ex.org/Company")
 * class Company {
 *     @RdfInverse("http://ex.org/worksFor")   // every employee with worksFor = this company
 *     List<Person> employees;
 * }
 * }</pre>
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface RdfInverse {
    String value();
}
