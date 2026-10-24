package org.unioncraft.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small dependency-free JSON reader/writer. Keeps the launcher self contained:
 * one javac invocation is all that is needed to build Union Craft Launcher.
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.value();
        p.ws();
        if (!p.done()) throw p.error("Trailing characters");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(sb, s);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        } else if (value.getClass().isArray()) {
            sb.append('[');
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, java.lang.reflect.Array.get(value, i));
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(value));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---- convenient builders / accessors ---------------------------------

    public static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    public static List<Object> arr(Object... values) {
        List<Object> list = new ArrayList<>(values.length);
        for (Object value : values) list.add(value);
        return list;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    public static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    public static String str(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public static int integer(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    public static int integer(Map<?, ?> map, String key, int fallback) {
        return integer(map.get(key), fallback);
    }

    public static long longValue(Object value, long fallback) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    public static long longValue(Map<?, ?> map, String key, long fallback) {
        return longValue(map.get(key), fallback);
    }

    public static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }

    public static boolean bool(Map<?, ?> map, String key, boolean fallback) {
        return bool(map.get(key), fallback);
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        boolean done() { return pos >= text.length(); }

        void ws() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\uFEFF') pos++;
                else break;
            }
        }

        IllegalArgumentException error(String message) {
            int line = 1, col = 1;
            for (int i = 0; i < Math.min(pos, text.length()); i++) {
                if (text.charAt(i) == '\n') { line++; col = 1; } else col++;
            }
            return new IllegalArgumentException(message + " at line " + line + ", column " + col);
        }

        Object value() {
            ws();
            if (done()) throw error("Unexpected end of JSON");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object value) {
            if (!text.startsWith(word, pos)) throw error("Expected " + word);
            pos += word.length();
            return value;
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            ws();
            if (!done() && text.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                ws();
                if (done() || text.charAt(pos) != '"') throw error("Expected object key");
                String key = string();
                ws();
                if (done() || text.charAt(pos) != ':') throw error("Expected ':'");
                pos++;
                map.put(key, value());
                ws();
                if (done()) throw error("Unterminated object");
                char c = text.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<>();
            pos++;
            ws();
            if (!done() && text.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(value());
                ws();
                if (done()) throw error("Unterminated array");
                char c = text.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        String string() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (done()) throw error("Unterminated escape");
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > text.length()) throw error("Invalid unicode escape");
                            try {
                                sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("Invalid unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("Invalid escape \\" + e);
                    }
                } else {
                    if (c < 0x20) throw error("Control character in string");
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        Number number() {
            int start = pos;
            if (!done() && text.charAt(pos) == '-') pos++;
            while (!done() && Character.isDigit(text.charAt(pos))) pos++;
            boolean decimal = false;
            if (!done() && text.charAt(pos) == '.') {
                decimal = true;
                pos++;
                while (!done() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (!done() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                decimal = true;
                pos++;
                if (!done() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (!done() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (start == pos) throw error("Expected value");
            String raw = text.substring(start, pos);
            try {
                return decimal ? Double.parseDouble(raw) : Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                throw error("Invalid number " + raw);
            }
        }
    }
}

