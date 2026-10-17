package org.graphrd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader — enough for SPARQL-Results and the management API —
 * so the client carries no dependencies. Objects become {@code Map<String,
 * Object>}, arrays {@code List<Object>}, numbers {@code Double}, everything
 * else String/Boolean/null.
 */
final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new GraphRdException("trailing JSON input at " + p.i);
        }
        return v;
    }

    static Map<String, Object> obj(Object v) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return m;
    }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object v) {
        return (List<Object>) v;
    }

    /** A member as text (numbers formatted plainly, null as empty). */
    static String str(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double d && !d.isInfinite() && d == Math.floor(d)) {
            return String.valueOf(d.longValue()); // 42.0 → "42"
        }
        return v.toString();
    }

    private Object value() {
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        i++; // {
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return out;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            i++;
            ws();
            out.put(key, value());
            ws();
            if (s.charAt(i) == ',') {
                i++;
            } else {
                expect('}');
                i++;
                return out;
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        i++; // [
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return out;
        }
        while (true) {
            ws();
            out.add(value());
            ws();
            if (s.charAt(i) == ',') {
                i++;
            } else {
                expect(']');
                i++;
                return out;
            }
        }
    }

    private String string() {
        StringBuilder out = new StringBuilder();
        i++; // opening quote
        while (s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                char e = s.charAt(i);
                out.append(switch (e) {
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> (char) Integer.parseInt(s.substring(i + 1, i + 5), 16);
                    default -> throw new GraphRdException("bad escape \\" + e);
                });
                i += e == 'u' ? 5 : 1;
            } else {
                out.append(c);
            }
            i++;
        }
        i++; // closing quote
        return out.toString();
    }

    private Object number() {
        int start = i;
        if (s.charAt(i) == '-') {
            i++;
        }
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return Double.parseDouble(s.substring(start, i));
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) {
            throw new GraphRdException("bad JSON at " + i);
        }
        i += w.length();
        return v;
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private void expect(char c) {
        if (s.charAt(i) != c) {
            throw new GraphRdException("expected '" + c + "' at " + i);
        }
    }
}
