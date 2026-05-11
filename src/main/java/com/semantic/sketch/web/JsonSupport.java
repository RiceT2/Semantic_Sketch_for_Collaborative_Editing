package com.semantic.sketch.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonSupport {
    private JsonSupport() {
    }

    static Map<String, String> parseObject(String json) {
        Map<String, Object> parsed = parseObjectValues(json);
        Map<String, String> values = new LinkedHashMap<>();
        parsed.forEach((key, value) -> values.put(key, value == null ? null : String.valueOf(value)));
        return values;
    }

    static Map<String, Object> parseObjectValues(String json) {
        Object value = new Parser(json).parseValue();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> values.put(String.valueOf(key), mapValue));
            return values;
        }
        return new LinkedHashMap<>();
    }

    static String stringify(Map<String, ?> object) {
        StringBuilder builder = new StringBuilder("{");
        Iterator<? extends Map.Entry<String, ?>> iterator = object.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ?> entry = iterator.next();
            builder.append('"').append(escape(entry.getKey())).append("\":");
            appendValue(builder, entry.getValue());
            if (iterator.hasNext()) {
                builder.append(',');
            }
        }
        return builder.append('}').toString();
    }

    private static void appendValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<?, ?> entry = iterator.next();
                builder.append('"').append(escape(String.valueOf(entry.getKey()))).append("\":");
                appendValue(builder, entry.getValue());
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append('}');
        } else if (value instanceof Collection<?> collection) {
            builder.append('[');
            Iterator<?> iterator = collection.iterator();
            while (iterator.hasNext()) {
                appendValue(builder, iterator.next());
                if (iterator.hasNext()) {
                    builder.append(',');
                }
            }
            builder.append(']');
        } else {
            builder.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                return null;
            }
            char current = text.charAt(index);
            if (current == '{') {
                return parseObject();
            }
            if (current == '[') {
                return parseArray();
            }
            if (current == '"') {
                return parseString();
            }
            if (text.startsWith("true", index)) {
                index += 4;
                return true;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return false;
            }
            if (text.startsWith("null", index)) {
                index += 4;
                return null;
            }
            return parseLiteral();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> values = new LinkedHashMap<>();
            index++;
            while (index < text.length()) {
                skipWhitespace();
                if (index < text.length() && text.charAt(index) == '}') {
                    index++;
                    break;
                }
                if (index >= text.length() || text.charAt(index) != '"') {
                    break;
                }
                String key = parseString();
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != ':') {
                    break;
                }
                index++;
                values.put(key, parseValue());
                skipWhitespace();
                if (index < text.length() && text.charAt(index) == ',') {
                    index++;
                }
            }
            return values;
        }

        private List<Object> parseArray() {
            List<Object> values = new ArrayList<>();
            index++;
            while (index < text.length()) {
                skipWhitespace();
                if (index < text.length() && text.charAt(index) == ']') {
                    index++;
                    break;
                }
                values.add(parseValue());
                skipWhitespace();
                if (index < text.length() && text.charAt(index) == ',') {
                    index++;
                }
            }
            return values;
        }

        private String parseString() {
            StringBuilder builder = new StringBuilder();
            index++;
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    break;
                }
                if (current == '\\' && index < text.length()) {
                    char escaped = text.charAt(index++);
                    builder.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        default -> escaped;
                    });
                } else {
                    builder.append(current);
                }
            }
            return builder.toString();
        }

        private Object parseLiteral() {
            int start = index;
            while (index < text.length() && ",}]".indexOf(text.charAt(index)) < 0) {
                index++;
            }
            String value = text.substring(start, index).trim();
            if (value.isEmpty()) {
                return null;
            }
            try {
                if (value.contains(".") || value.contains("e") || value.contains("E")) {
                    return Double.parseDouble(value);
                }
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }


    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
