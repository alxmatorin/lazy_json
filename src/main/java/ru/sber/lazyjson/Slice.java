package ru.sber.lazyjson;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Найденное значение любого типа — диапазон байт внутри исходного документа, без копирования.
 * Для строк диапазон включает кавычки, для объектов и массивов — скобки.
 */
public record Slice(byte[] doc, int offset, int length) {

    public int end() {
        return offset + length;
    }

    /** Копия байт значения. */
    public byte[] copy() {
        return Arrays.copyOfRange(doc, offset, end());
    }

    /** Сырой JSON-текст значения. */
    public String text() {
        return new String(doc, offset, length, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Slice slice && slice.doc == doc && slice.offset == offset && slice.length == length;
    }

    @Override
    public int hashCode() {
        return 31 * offset + length;
    }

    @Override
    public String toString() {
        return "Slice[" + offset + ", " + length + "]";
    }
}
