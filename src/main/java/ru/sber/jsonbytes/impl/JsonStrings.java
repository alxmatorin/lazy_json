package ru.sber.jsonbytes.impl;

import java.nio.charset.StandardCharsets;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Декодирование и кодирование JSON-строк — только для редкого пути:
 * ключ в документе записан с escape-последовательностями, либо вставляемый ключ требует экранирования.
 */
final class JsonStrings {

    private JsonStrings() {
    }

    /** Декодирует содержимое строки (без кавычек) из {@code doc[from, to)}. */
    static String unescape(byte[] doc, int from, int to) {
        StringBuilder out = new StringBuilder(to - from);
        int p = from;
        while (p < to) {
            int start = p;
            while (p < to && doc[p] != '\\') {
                p++;
            }
            out.append(new String(doc, start, p - start, UTF_8));
            if (p < to) {
                p = unescapeSequence(doc, p, to, out);
            }
        }
        return out.toString();
    }

    private static int unescapeSequence(byte[] doc, int p, int to, StringBuilder out) {
        if (p + 1 >= to) {
            throw new IllegalArgumentException("malformed escape at offset " + p);
        }
        switch (doc[p + 1]) {
            case '"', '\\', '/' -> out.append((char) doc[p + 1]);
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case 'r' -> out.append('\r');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case 'u' -> {
                out.append((char) hex4(doc, p + 2, to));
                return p + 6;
            }
            default -> throw new IllegalArgumentException("malformed escape at offset " + p);
        }
        return p + 2;
    }

    private static int hex4(byte[] doc, int from, int to) {
        if (from + 4 > to) {
            throw new IllegalArgumentException("malformed \\u escape at offset " + from);
        }
        int value = 0;
        for (int i = from; i < from + 4; i++) {
            int digit = Character.digit(doc[i], 16);
            if (digit < 0) {
                throw new IllegalArgumentException("malformed \\u escape at offset " + from);
            }
            value = value << 4 | digit;
        }
        return value;
    }

    /** Кодирует строку в JSON-литерал с кавычками. */
    static byte[] quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || Character.isSurrogate(c)) {
                        appendUnicode(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString().getBytes(UTF_8);
    }

    private static void appendUnicode(StringBuilder out, char c) {
        out.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            out.append("0123456789abcdef".charAt((c >>> shift) & 15));
        }
    }

    /** null if UTF-8 encoding would replace an isolated surrogate with '?'. */
    static byte[] rawKey(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == name.length() || !Character.isLowSurrogate(name.charAt(i))) {
                    return null;
                }
            } else if (Character.isLowSurrogate(c)) {
                return null;
            }
        }
        return name.getBytes(UTF_8);
    }
}
