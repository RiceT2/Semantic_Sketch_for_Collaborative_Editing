package com.semantic.sketch.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loader for the Automerge perf {@code edit-by-index/editing-trace.js} data used by crdt-benchmarks B4.
 */
public final class AutomergeEditingTraceLoader {
    private AutomergeEditingTraceLoader() {
    }

    public static List<CrdtBenchmarkOperation> load(Path tracePath, int maxOperations) throws IOException {
        String source = Files.readString(tracePath, StandardCharsets.UTF_8);
        return parse(source, maxOperations);
    }

    static List<CrdtBenchmarkOperation> parse(String source, int maxOperations) {
        int start = findEditsArrayStart(source);
        Parser parser = new Parser(source, start, maxOperations);
        return parser.parseEdits();
    }

    private static int findEditsArrayStart(String source) {
        int edits = source.indexOf("edits");
        while (edits >= 0) {
            int equals = source.indexOf('=', edits);
            int bracket = source.indexOf('[', edits);
            if (bracket >= 0 && (equals < 0 || bracket > equals)) {
                return bracket;
            }
            edits = source.indexOf("edits", edits + 5);
        }
        int bracket = source.indexOf('[');
        if (bracket >= 0) {
            return bracket;
        }
        throw new IllegalArgumentException("Could not locate edits array in trace source");
    }

    private static final class Parser {
        private final String source;
        private final int maxOperations;
        private int pos;

        private Parser(String source, int start, int maxOperations) {
            this.source = source;
            this.pos = start;
            this.maxOperations = maxOperations <= 0 ? Integer.MAX_VALUE : maxOperations;
        }

        private List<CrdtBenchmarkOperation> parseEdits() {
            List<CrdtBenchmarkOperation> operations = new ArrayList<>();
            expect('[');
            skipWhitespaceAndCommas();
            while (!peek(']') && operations.size() < maxOperations) {
                operations.add(parseOperation());
                skipWhitespaceAndCommas();
            }
            return operations;
        }

        private CrdtBenchmarkOperation parseOperation() {
            expect('[');
            int index = parseInteger();
            expect(',');
            int deleteCount = parseInteger();
            String insertedText = "";
            skipWhitespace();
            if (peek(',')) {
                pos++;
                insertedText = parseString();
            }
            skipWhitespace();
            expect(']');
            return new CrdtBenchmarkOperation(index, deleteCount, insertedText);
        }

        private int parseInteger() {
            skipWhitespace();
            int sign = 1;
            if (peek('-')) {
                sign = -1;
                pos++;
            }
            int start = pos;
            while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("Expected integer at offset " + pos);
            }
            return sign * Integer.parseInt(source.substring(start, pos));
        }

        private String parseString() {
            skipWhitespace();
            char quote = source.charAt(pos++);
            if (quote != '\'' && quote != '"') {
                throw new IllegalArgumentException("Expected string at offset " + (pos - 1));
            }
            StringBuilder out = new StringBuilder();
            while (pos < source.length()) {
                char ch = source.charAt(pos++);
                if (ch == quote) {
                    return out.toString();
                }
                if (ch != '\\') {
                    out.append(ch);
                    continue;
                }
                if (pos >= source.length()) {
                    throw new IllegalArgumentException("Unterminated escape sequence");
                }
                char escaped = source.charAt(pos++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case '\\' -> out.append('\\');
                    case '\'' -> out.append('\'');
                    case '"' -> out.append('"');
                    case 'u' -> out.append(parseUnicodeEscape());
                    default -> out.append(escaped);
                }
            }
            throw new IllegalArgumentException("Unterminated string literal");
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > source.length()) {
                throw new IllegalArgumentException("Incomplete unicode escape at offset " + pos);
            }
            int value = Integer.parseInt(source.substring(pos, pos + 4), 16);
            pos += 4;
            return (char) value;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (pos >= source.length() || source.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at offset " + pos);
            }
            pos++;
        }

        private boolean peek(char expected) {
            skipWhitespace();
            return pos < source.length() && source.charAt(pos) == expected;
        }

        private void skipWhitespaceAndCommas() {
            boolean consumed;
            do {
                consumed = false;
                skipWhitespace();
                if (pos < source.length() && source.charAt(pos) == ',') {
                    pos++;
                    consumed = true;
                }
            } while (consumed);
        }

        private void skipWhitespace() {
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
                pos++;
            }
        }
    }
}
