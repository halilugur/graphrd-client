package org.graphrd.rdf;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Spring-Data-style derived queries without Spring: hand the mapper an
 * interface, call methods named after what they should do.
 *
 * <pre>{@code
 * interface People extends RdfRepository<Person> {
 *     List<Person>  findByName(String name);
 *     List<Person>  findByAgeGreaterThanAndSalaryLessThan(int age, int salary);
 *     Optional<Person> findByEmail(String email);
 *     long         countByWorksFor(String companyIri);
 *     boolean      existsByEmail(String email);
 *     void         deleteByName(String name);
 * }
 * People people = RdfRepositories.proxy(People.class, em);
 * }</pre>
 *
 * Conditions join with {@code And} ({@code Or} needs parentheses — write a
 * criteria query for that). Operations: {@code Is} (equal, also spelled as
 * just the field), {@code GreaterThan}, {@code GreaterThanEqual}, {@code
 * LessThan}, {@code LessThanEqual}, {@code Like}, {@code In}, {@code Between},
 * {@code IsNull}, {@code IsNotNull}. A {@code By} condition on a reference
 * field compares the IRI itself.
 */
public final class RdfRepositories {

    private RdfRepositories() {
    }

    /** The interface every derived-query repository extends, for the entity type. */
    public interface RdfRepository<T> {
    }

    /** Builds a proxy implementing {@code iface} over the manager. */
    @SuppressWarnings("unchecked")
    public static <I> I proxy(Class<I> iface, RdfEntityManager em) {
        Class<?> entity = entityOf(iface);
        return (I) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                new Derived<>(iface, em, entity));
    }

    private static Class<?> entityOf(Class<?> iface) {
        for (var sup : iface.getGenericInterfaces()) {
            if (sup instanceof ParameterizedType p && p.getRawType() == RdfRepository.class) {
                return (Class<?>) p.getActualTypeArguments()[0];
            }
        }
        throw new IllegalArgumentException(iface + " must extend RdfRepository<Entity>");
    }

    private record Derived<I>(Class<I> iface, RdfEntityManager em, Class<?> entity)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "RdfRepository<" + entity.getSimpleName() + ">(" + iface.getName() + ")";
                };
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            String name = method.getName();
            Class<?> returns = method.getReturnType();
            boolean optional = returns == Optional.class;
            boolean many = Collection.class.isAssignableFrom(returns) || returns == Iterable.class;

            String head;
            Mode mode;
            if (name.startsWith("findFirstBy")) {
                head = name.substring("findFirstBy".length());
                mode = Mode.FIRST;
            } else if (name.startsWith("findBy")) {
                head = name.substring("findBy".length());
                mode = Mode.FIND;
            } else if (name.startsWith("existsBy")) {
                head = name.substring("existsBy".length());
                mode = Mode.EXISTS;
            } else if (name.startsWith("countBy")) {
                head = name.substring("countBy".length());
                mode = Mode.COUNT;
            } else if (name.startsWith("deleteBy")) {
                head = name.substring("deleteBy".length());
                mode = Mode.DELETE;
            } else {
                throw new IllegalArgumentException(
                        "method names start with findBy/existsBy/countBy/deleteBy: " + name);
            }

            EntityQuery<?> q = em.query(entity);
            int arg = 0;
            // "And" joins conditions only before a capital: findByBrandName stays one field
            for (String part : head.split("And(?=[A-Z])")) {
                arg = condition(q, part, args, arg);
            }
            return switch (mode) {
                case FIND, FIRST -> {
                    if (many && mode == Mode.FIND) {
                        yield q.list();
                    }
                    List<?> list = q.limit(1).list();
                    if (many) {
                        yield list;
                    }
                    Object first = list.isEmpty() ? null : list.get(0);
                    yield optional ? Optional.ofNullable(first) : first; // a single entity, or null
                }
                case EXISTS -> q.limit(1).count() > 0;
                case COUNT -> number(q.count(), returns);
                case DELETE -> {
                    List<?> victims = q.list();
                    victims.forEach(e -> em.delete(entity, em.idOfPublic(e)));
                    yield returns == void.class || returns == Void.class ? null : number(victims.size(), returns);
                }
            };
        }

        /** A count in the declared numeric return type (int or long). */
        private static Object number(long n, Class<?> returns) {
            return returns == int.class || returns == Integer.class ? (Object) (int) n : (Object) n;
        }

        /** One `FieldOp` fragment of the method name, consuming its arguments. */
        private int condition(EntityQuery<?> q, String part, Object[] args, int arg) {
            String[][] ops = {
                    {"GreaterThanEqual", "GE"}, {"LessThanEqual", "LE"},
                    {"GreaterThan", "GT"}, {"LessThan", "LT"},
                    {"IsNotNull", "NOTNULL"}, {"IsNull", "NULL"},
                    {"Like", "LIKE"}, {"In", "IN"}, {"Between", "BETWEEN"}, {"Is", "EQ"},
            };
            for (String[] op : ops) {
                // operators are suffixes: findByMinIncome is the field minIncome, not "min In"
                int at = part.length() - op[0].length();
                if (at > 0 && part.endsWith(op[0])) {
                    String field = Character.toLowerCase(part.charAt(0)) + part.substring(1, at);
                    return apply(q, field, op[1], args, arg);
                }
            }
            // no operator suffix: equality on the bare field name
            String field = Character.toLowerCase(part.charAt(0)) + part.substring(1);
            return apply(q, field, "EQ", args, arg);
        }

        private int apply(EntityQuery<?> q, String field, String op, Object[] args, int arg) {
            // a reference field's equality is an IRI pattern, not a literal comparison
            boolean ref = em.isReferenceField(entity, field);
            switch (op) {
                case "NULL" -> q.whereNull(field);
                case "NOTNULL" -> q.whereNotNull(field);
                case "LIKE" -> q.whereLike(field, (String) args[arg++]);
                case "IN" -> q.whereIn(field, values(args[arg++]));
                case "BETWEEN" -> {
                    Object lo = args[arg++];
                    Object hi = args[arg++];
                    q.where(field, EntityQuery.Op.GE, lo).where(field, EntityQuery.Op.LE, hi);
                }
                case "EQ" -> {
                    if (ref && args[arg] instanceof String iri) {
                        q.whereRef(field, iri);
                    } else {
                        q.where(field, EntityQuery.Op.EQ, args[arg]);
                    }
                    arg++;
                }
                default -> q.where(field, EntityQuery.Op.valueOf(op), args[arg++]);
            }
            return arg;
        }

        /** An In argument: a collection, an array, or a single value. */
        private static List<?> values(Object arg) {
            if (arg instanceof Collection<?> c) {
                return new ArrayList<>(c);
            }
            if (arg instanceof Object[] a) {
                return java.util.Arrays.asList(a);
            }
            if (arg != null && arg.getClass().isArray()) {
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < java.lang.reflect.Array.getLength(arg); i++) {
                    out.add(java.lang.reflect.Array.get(arg, i));
                }
                return out;
            }
            return List.of(arg);
        }
    }

    private enum Mode {
        FIND, FIRST, EXISTS, COUNT, DELETE
    }
}
