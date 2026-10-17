package org.graphrd.rdf;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A predicate of the resource. A {@code List<String>} or {@code List<Integer>}
 * field holds every value of the predicate; a field whose type is itself a
 * {@code @RdfType} entity is an IRI reference, read back eagerly; a
 * {@code Ref<ThatEntity>} field is the same reference, resolved only when
 * {@link Ref#get()} is called. {@code lang} and {@code datatype} shape the
 * literal that is written (reading always yields the plain value).
 */
@Retention(RUNTIME)
@Target(FIELD)
public @interface RdfProperty {
    String value();

    /** A language tag for string literals: {@code "…"@lang}. */
    String lang() default "";

    /** A datatype IRI for string literals: {@code "…"^^<datatype>}. */
    String datatype() default "";
}
