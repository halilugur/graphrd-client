package org.graphrd.rdf;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** The RDF class this entity maps to: {@code @RdfType("http://example.org/Person")}. */
@Retention(RUNTIME)
@Target(TYPE)
public @interface RdfType {
    String value();
}
