package org.graphrd.rdf;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * The subject IRI of the resource this entity maps to (a String field). With
 * {@code generated = true} the field may be null on save: an IRI is minted
 * under the type's namespace ({@code …/person/a1b2c3}) and written back into
 * the field.
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface RdfId {

    /** Mint an IRI on save when the field is null. */
    boolean generated() default false;
}
