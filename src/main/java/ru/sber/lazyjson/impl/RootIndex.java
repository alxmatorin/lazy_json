package ru.sber.lazyjson.impl;

import ru.sber.lazyjson.impl.PathTrie.KeyChild;

import java.util.Arrays;

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

    private byte[] doc;
    private int[] entries = new int[FIELDS * 8];
    private boolean[] escaped = new boolean[8];
    private int size;
    private boolean initialized;
    private int rootStart;
    private int rootClose;
    /** Первый байт следующего непросканированного члена (его кавычка) либо {@code '}'}. */
    private int forward;
    /** Последний байт значения последнего непросканированного члена либо {@code '{'}. */
    private int backward;

    /** @return {@code false}, если корень — не объект или он пуст (тогда индексировать нечего) */
    boolean init(byte[] doc, ByteScanner in, int start) {
        if (initialized) {
            return true;
        }
        this.doc = doc;
        rootStart = start;
        rootClose = in.expect(in.skipWhitespaceBack(doc.length - 1), '}');
        forward = in.skipWhitespace(start + 1);
        backward = in.skipWhitespaceBack(rootClose - 1);
        initialized = true;
        return true;
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
        if (size * FIELDS == entries.length) {
            entries = Arrays.copyOf(entries, entries.length * 2);
            escaped = Arrays.copyOf(escaped, escaped.length * 2);
        }
        int at = size * FIELDS;
        entries[at] = keyStart;
        entries[at + 1] = keyEnd;
        entries[at + 2] = valueStart;
        entries[at + 3] = valueEnd;
        escaped[size] = keyEscaped;
        size++;
    }

    /** @return номер записи с этим ключом или {@code -1} */
    int lookup(KeyChild key) {
        byte[] raw = key.raw();
        for (int i = 0; i < size; i++) {
            int keyStart = entries[i * FIELDS];
            int keyEnd = entries[i * FIELDS + 1];
            if (!escaped[i]) {
                if (raw != null && keyEnd - keyStart == raw.length && Arrays.equals(doc, keyStart, keyEnd, raw, 0, raw.length)) {
                    return i;
                }
            } else if (raw == null || keyEnd - keyStart >= raw.length) {
                if (JsonStrings.unescape(doc, keyStart, keyEnd).equals(key.name())) {
                    return i;
                }
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
