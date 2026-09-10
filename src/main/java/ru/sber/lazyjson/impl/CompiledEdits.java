package ru.sber.lazyjson.impl;

import ru.sber.lazyjson.Edit;
import ru.sber.lazyjson.JsonPath;

import java.util.ArrayList;
import java.util.List;

/** Подготовленные пути и значения пакета; смещения документа здесь не сохраняются. */
public final class CompiledEdits {

    private final PathTrie trie;
    private final byte[][] values;

    private CompiledEdits(PathTrie trie, byte[][] values) {
        this.trie = trie;
        this.values = values;
    }

    public static CompiledEdits of(List<Edit> edits) {
        PathTrie trie = PathTrie.of(pathsOf(edits));
        return new CompiledEdits(trie, valuesOf(edits));
    }

    public byte[] apply(byte[] doc, RootIndex index) {
        return Splicer.apply(doc, trie, values, index);
    }

    private static List<JsonPath> pathsOf(List<Edit> edits) {
        List<JsonPath> paths = new ArrayList<>(edits.size());
        for (Edit edit : edits) {
            paths.add(edit.path());
        }
        return paths;
    }

    private static byte[][] valuesOf(List<Edit> edits) {
        byte[][] values = new byte[edits.size()][];
        int index = 0;
        for (Edit edit : edits) {
            values[index++] = edit.value();
        }
        return values;
    }
}
