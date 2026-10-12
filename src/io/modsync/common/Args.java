package io.modsync.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny command-line parser shared by both entry points (standalone {@code java -jar} and
 * {@code -javaagent} arguments).
 *
 * <p>Accepted forms: {@code --key value}, {@code --key=value}, {@code --flag},
 * {@code key=value} and bare positional tokens. Quotes are honoured when splitting an agent
 * argument string produced by the JVM.
 */
public final class Args {

    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private final List<String> positional = new ArrayList<>();

    public static Args parse(String[] argv) {
        Args a = new Args();
        for (int i = 0; i < argv.length; i++) {
            String token = argv[i];
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (token.startsWith("--")) {
                String body = token.substring(2);
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    a.add(body.substring(0, eq), body.substring(eq + 1));
                } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    a.add(body, argv[++i]);
                } else {
                    a.add(body, "true");
                }
            } else if (token.indexOf('=') > 0) {
                int eq = token.indexOf('=');
                a.add(token.substring(0, eq), token.substring(eq + 1));
            } else {
                a.positional.add(token);
            }
        }
        return a;
    }

    /** Parses the agent argument string, honouring double quotes and backslash escapes. */
    public static Args parse(String line) {
        if (line == null || line.isBlank()) {
            return new Args();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean has = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                cur.append(line.charAt(++i));
                has = true;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                has = true;
                continue;
            }
            // Whitespace *and* commas separate options: the JVM passes the whole
            // "-javaagent:...=a=1,b=2" tail as one string, and commas are the only separator
            // that survives launcher JVM-argument parsing.
            if ((Character.isWhitespace(c) || c == ',') && !inQuotes) {
                if (has) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    has = false;
                }
                continue;
            }
            cur.append(c);
            has = true;
        }
        if (has) {
            tokens.add(cur.toString());
        }
        return parse(tokens.toArray(new String[0]));
    }

    private void add(String key, String value) {
        values.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String get(String key) {
        List<String> l = values.get(key);
        return (l == null || l.isEmpty()) ? null : l.get(l.size() - 1);
    }

    public String get(String key, String def) {
        String v = get(key);
        return v == null ? def : v;
    }

    public boolean flag(String key) {
        String v = get(key);
        return v != null && !"false".equalsIgnoreCase(v) && !"0".equals(v);
    }

    public List<String> list(String key) {
        return values.getOrDefault(key, List.of());
    }

    public List<String> positional() {
        return positional;
    }
}
