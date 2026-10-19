package org.graphrd.rdf;

import java.io.Serializable;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

/**
 * A getter reference for type-safe queries: {@code where(Person::getAge, Op.GT, 30)}.
 * The field name comes from the compiled method reference — a typo is a
 * compile error, not a runtime one.
 */
@FunctionalInterface
public interface Getter<T, V> extends Serializable {

    V apply(T entity);

    /** The entity field this getter reads: {@code Person::getName} → {@code name}. */
    default String fieldName() {
        try {
            Method replace = getClass().getDeclaredMethod("writeReplace");
            replace.setAccessible(true);
            SerializedLambda lambda = (SerializedLambda) replace.invoke(this);
            String name = lambda.getImplMethodName();
            // only a bean prefix strips: getAge → age, isActive → active, but issue stays issue
            if (name.length() > 3 && name.startsWith("get") && Character.isUpperCase(name.charAt(3))) {
                return Character.toLowerCase(name.charAt(3)) + name.substring(4);
            }
            if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
                return Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }
            return name;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("could not read the getter name", e);
        }
    }
}
