package io.modsync.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON parser/writer.
 *
 * <p>Parsed values map to {@code Map<String,Object>}, {@code List<Object>}, {@code String},
 * {@code Long}/{@code Double}, {@code Boolean} and {@code null}. No external libraries are used on
 * purpose: both the server side and the client agent must run from a single jar with no classpath
 * requirements, including as a {@code -javaagent}.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.src.length()) {
            throw new IllegalArgumentException("trailing content at offset " + p.pos);
        }
        return v;
    }

    /** Pretty printer (2-space indent); deterministic for maps built in insertion order. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    /** 单行紧凑输出（JSONL 审计日志用）：对象与数组不换行、不加缩进。 */
    public static String writeCompact(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCompactValue(sb, value);
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return null;
    }

    public static Map<String, Object> object(Object value, String what) {
        Map<String, Object> m = asObject(value);
        if (m == null) {
            throw new IllegalArgumentException(what + " is not a JSON object");
        }
        return m;
    }

    public static List<Object> array(Object value, String what) {
        List<Object> l = asArray(value);
        if (l == null) {
            throw new IllegalArgumentException(what + " is not a JSON array");
        }
        return l;
    }

    public static String str(Map<String, Object> o, String key, String def) {
        Object v = o.get(key);
        return v instanceof String ? (String) v : def;
    }

    public static long num(Map<String, Object> o, String key, long def) {
        Object v = o.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return def;
    }

    public static boolean bool(Map<String, Object> o, String key, boolean def) {
        Object v = o.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    public static List<Object> list() {
        return new ArrayList<>();
    }

    // ---------------------------------------------------------------- parsing

    private void ws() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("unexpected end of JSON input");
        }
        return src.charAt(pos);
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return number();
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("expected " + literal + " at offset " + pos);
        }
        pos += literal.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw new IllegalArgumentException("expected object key at offset " + pos);
            }
            String key = string();
            ws();
            if (peek() != ':') {
                throw new IllegalArgumentException("expected ':' at offset " + pos);
            }
            pos++;
            ws();
            out.put(key, value());
            ws();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return out;
            }
            throw new IllegalArgumentException("expected ',' or '}' at offset " + pos);
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++; // [
        ws();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            out.add(value());
            ws();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return out;
            }
            throw new IllegalArgumentException("expected ',' or ']' at offset " + pos);
        }
    }

    private String string() {
        if (peek() != '"') {
            throw new IllegalArgumentException("expected string at offset " + pos);
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new IllegalArgumentException("unterminated escape");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new IllegalArgumentException("bad unicode escape");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("bad escape \\" + e);
            }
        }
    }

    private Object number() {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        String n = src.substring(start, pos);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("expected number at offset " + start);
        }
        if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
            try {
                return Long.valueOf(Long.parseLong(n));
            } catch (NumberFormatException ignored) {
                // fall through to double
            }
        }
        return Double.valueOf(Double.parseDouble(n));
    }

    // ---------------------------------------------------------------- writing

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map) {
            writeObject(sb, (Map<String, Object>) v, indent);
        } else if (v instanceof List) {
            writeArray(sb, (List<Object>) v, indent);
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    private static void writeObject(StringBuilder sb, Map<String, Object> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            pad(sb, indent + 1);
            quote(sb, e.getKey());
            sb.append(": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<Object> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            pad(sb, indent + 1);
            writeValue(sb, list.get(i), indent + 1);
            if (i + 1 < list.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        pad(sb, indent);
        sb.append(']');
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeCompactValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map) {
            writeCompactObject(sb, (Map<String, Object>) v);
        } else if (v instanceof List) {
            writeCompactArray(sb, (List<Object>) v);
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    private static void writeCompactObject(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        int i = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            quote(sb, e.getKey());
            sb.append(':');
            writeCompactValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeCompactArray(StringBuilder sb, List<Object> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeCompactValue(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

}
