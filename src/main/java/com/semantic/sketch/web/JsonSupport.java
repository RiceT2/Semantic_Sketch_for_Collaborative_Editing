package com.semantic.sketch.web;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonSupport {
    private JsonSupport() {
    }

    static Map<String, String> parseObject(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        if (json == null) {
            return values;
        }
        int index = skipWhitespace(json, 0);
        if (index >= json.length() || json.charAt(index) != '{') {
            return values;
        }
        index++;
        while (index < json.length()) {
            index = skipWhitespace(json, index);
            if (index < json.length() && json.charAt(index) == '}') {
                break;
            }
            ParsedString key = readString(json, index);
            index = skipWhitespace(json, key.nextIndex());
            if (index >= json.length() || json.charAt(index) != ':') {
                break;
            }
            index = skipWhitespace(json, index + 1);
            String value;
            if (index < json.length() && json.charAt(index) == '"') {
                ParsedString parsedValue = readString(json, index);
                value = parsedValue.value();
                index = parsedValue.nextIndex();
            } else {
                int start = index;
                while (index < json.length() && json.charAt(index) != ',' && json.charAt(index) != '}') {
                    index++;
                }
                value = json.substring(start, index).trim();
            }
            values.put(key.value(), value);
            index = skipWhitespace(json, index);
            if (index < json.length() && json.charAt(index) == ',') {
                index++;
            }
        }
        return values;
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

    private static ParsedString readString(String text, int start) {
        StringBuilder builder = new StringBuilder();
        int index = start + 1;
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
        return new ParsedString(builder.toString(), index);
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private record ParsedString(String value, int nextIndex) {
    }
}
