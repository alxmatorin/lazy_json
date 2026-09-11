package ru.jsonbytes.impl;

import ru.jsonbytes.impl.PathTrie.KeyChild;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Смещения членов корневого объекта, собранные попутно предыдущими обходами того же документа.
 * <p>
 * Хранит для каждого пройденного члена ключ и границы значения, а также границы ещё не просканированной
 * области {@code [forward, backward]}: прямой обход продолжается с {@code forward}, обратный — с {@code backward}.
 * Когда область пуста, корень известен целиком и отсутствие ключа определяется без скана.
 * Не потокобезопасен — как и сам документ-обёртка.
 */
public final class RootIndex {

    private static final int FIELDS = 4;
    private static final int LINEAR_LIMIT = 16;

    private byte[] doc;
    private int[] entries;
    private String[] decodedNames;
    private Map<String, Integer> byName;
    private int size;
    private boolean initialized;
    private int rootStart;
    private int rootClose;
    /** Первый байт следующего непросканированного члена (его кавычка) либо {@code '}'}. */
    private int forward;
    /** Последний байт значения последнего непросканированного члена либо {@code '{'}. */
    private int backward;

    void init(byte[] doc, ByteScanner in, int start) {
        if (initialized) {
            return;
        }
        this.doc = doc;
        rootStart = start;
        rootClose = in.expect(in.skipWhitespaceBack(doc.length - 1), '}');
        forward = in.skipWhitespace(start + 1);
        backward = in.skipWhitespaceBack(rootClose - 1);
        initialized = true;
    }

    int rootStart() {
        return rootStart;
    }

    int rootClose() {
        return rootClose;
    }

    int forward() {
        return forward;
    }

    int backward() {
        return backward;
    }

    void forward(int position) {
        forward = position;
    }

    void backward(int position) {
        backward = position;
    }

    /** Непросканированной области не осталось. */
    boolean complete() {
        return forward > backward;
    }

    void markComplete() {
        forward = backward + 1;
    }

    void add(int keyStart, int keyEnd, boolean keyEscaped, int valueStart, int valueEnd) {
        ensureCapacity();
        storeEntry(keyStart, keyEnd, keyEscaped, valueStart, valueEnd);
        updateLookup();
    }

    private void ensureCapacity() {
        if (entries == null) {
            entries = new int[FIELDS * 8];
        } else if (size * FIELDS == entries.length) {
            entries = Arrays.copyOf(entries, entries.length * 2);
            if (decodedNames != null) {
                decodedNames = Arrays.copyOf(decodedNames, entries.length / FIELDS);
            }
        }
    }

    private void storeEntry(int keyStart, int keyEnd, boolean keyEscaped, int valueStart, int valueEnd) {
        int at = size * FIELDS;
        entries[at] = keyStart;
        entries[at + 1] = keyEnd;
        entries[at + 2] = valueStart;
        entries[at + 3] = valueEnd;
        if (keyEscaped) {
            if (decodedNames == null) {
                decodedNames = new String[entries.length / FIELDS];
            }
            decodedNames[size] = JsonStrings.unescape(doc, keyStart, keyEnd);
        }
        size++;
    }

    private void updateLookup() {
        if (byName != null) {
            byName.putIfAbsent(nameAt(size - 1), size - 1);
        } else if (size > LINEAR_LIMIT) {
            byName = new HashMap<>(size * 2);
            for (int i = 0; i < size; i++) {
                byName.putIfAbsent(nameAt(i), i);
            }
        }
    }

    private String nameAt(int entry) {
        if (decodedNames != null && decodedNames[entry] != null) {
            return decodedNames[entry];
        }
        int at = entry * FIELDS;
        return new String(doc, entries[at], entries[at + 1] - entries[at], StandardCharsets.UTF_8);
    }

    /** @return номер записи с этим ключом или {@code -1} */
    int lookup(KeyChild key) {
        if (byName != null) {
            return byName.getOrDefault(key.name(), -1);
        }
        byte[] raw = key.raw();
        for (int i = 0; i < size; i++) {
            int keyStart = entries[i * FIELDS];
            int keyEnd = entries[i * FIELDS + 1];
            String decoded = decodedNames == null ? null : decodedNames[i];
            if (decoded == null) {
                if (raw != null && keyEnd - keyStart == raw.length && Arrays.equals(doc, keyStart, keyEnd, raw, 0, raw.length)) {
                    return i;
                }
            } else if (decoded.equals(key.name())) {
                return i;
            }
        }
        return -1;
    }

    int valueStart(int entry) {
        return entries[entry * FIELDS + 2];
    }

    int valueEnd(int entry) {
        return entries[entry * FIELDS + 3];
    }

    public int size() {
        return size;
    }
}
