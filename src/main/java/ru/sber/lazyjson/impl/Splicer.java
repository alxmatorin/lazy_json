package ru.sber.lazyjson.impl;

import ru.sber.lazyjson.impl.PathTrie.KeyChild;
import ru.sber.lazyjson.impl.PathTrie.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Собирает результат правок одной склейкой: нетронутые куски исходного документа копируются
 * как есть, найденные значения заменяются, недостающие ключи вставляются сразу после {@code '{'} родителя.
 * Несколько недостающих ключей одного объекта сливаются в одну вставку.
 */
public final class Splicer implements TrieWalker.Sink {

    private sealed interface Patch permits Splice, Insert {
        int start();
        int end();
        int length();
        int copyTo(byte[] result, int at);
    }

    private record Splice(int start, int end, byte[] bytes) implements Patch {
        @Override
        public int length() {
            return bytes.length;
        }

        @Override
        public int copyTo(byte[] result, int at) {
            System.arraycopy(bytes, 0, result, at, bytes.length);
            return at + bytes.length;
        }
    }

    private record Insert(int start, Parts parts) implements Patch {
        @Override
        public int end() {
            return start;
        }

        @Override
        public int length() {
            return parts.length;
        }

        @Override
        public int copyTo(byte[] result, int at) {
            return parts.copyTo(result, at);
        }
    }

    private static final class Insertion {

        private final boolean emptyParent;
        private final Map<String, Parts> members = new LinkedHashMap<>(1);

        private Insertion(boolean emptyParent) {
            this.emptyParent = emptyParent;
        }
    }

    /** Куски будущего массива: значения правок лежат по ссылке и копируются один раз, в итоговый массив. */
    private static final class Parts {

        private final List<byte[]> chunks = new ArrayList<>(4);
        private int length;

        private Parts add(byte[] chunk) {
            chunks.add(chunk);
            length = Math.addExact(length, chunk.length);
            return this;
        }

        private int copyTo(byte[] result, int at) {
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, result, at, chunk.length);
                at += chunk.length;
            }
            return at;
        }
    }

    private static final byte[] COMMA = {','};
    private static final byte[] COLON = {':'};
    private static final byte[] OPEN = {'{'};
    private static final byte[] CLOSE = {'}'};

    private final byte[] doc;
    private final byte[][] values;
    private final boolean singleTarget;
    private Splice firstReplacement;
    private List<Splice> furtherReplacements;
    private Map<Integer, Insertion> insertions;

    private Splicer(byte[] doc, PathTrie trie, byte[][] values) {
        this.doc = doc;
        this.values = values;
        this.singleTarget = values.length == 1 && isChain(trie.root());
    }

    public static byte[] apply(byte[] doc, PathTrie trie, byte[][] values, RootIndex index) {
        Splicer splicer = new Splicer(doc, trie, values);
        TrieWalker.walk(doc, trie, splicer, index);
        return splicer.build();
    }

    private static boolean isChain(Node node) {
        while (node.hasChildren()) {
            if (node.wildcard() != null || node.keys().size() + node.indices().size() != 1) {
                return false;
            }
            node = node.keys().isEmpty() ? node.indices().getFirst().node() : node.keys().getFirst().node();
        }
        return true;
    }

    @Override
    public boolean found(Node node, int start, int end) {
        addReplacement(new Splice(start, end, valueOf(node)));
        return !singleTarget;
    }

    @Override
    public boolean missing(KeyChild child, int insertAt, boolean emptyParent) {
        addInsertion(child, insertAt, emptyParent);
        return !singleTarget;
    }

    @Override
    public boolean blocked(Node child, int at) {
        throw new IllegalArgumentException(
                "cannot write " + child.path() + ": value at offset " + at + " is not a container of the required kind");
    }

    @Override
    public boolean outOfRange(Node child, int size, int at) {
        throw new IllegalArgumentException(
                "cannot write " + child.path() + ": index is beyond the array of " + size + " elements at offset " + at);
    }

    private void addReplacement(Splice splice) {
        if (firstReplacement == null) {
            firstReplacement = splice;
        } else {
            if (furtherReplacements == null) {
                furtherReplacements = new ArrayList<>();
            }
            furtherReplacements.add(splice);
        }
    }

    private void addInsertion(KeyChild child, int at, boolean emptyParent) {
        if (insertions == null) {
            insertions = new LinkedHashMap<>();
        }
        Insertion insertion = insertions.computeIfAbsent(at, ignored -> new Insertion(emptyParent));
        if (insertion.members.putIfAbsent(child.name(), member(child)) != null) {
            throw new IllegalArgumentException("conflicting insertions for " + child.node().path());
        }
    }

    private Parts member(KeyChild child) {
        return appendValue(child.node(), new Parts().add(JsonStrings.quote(child.name())).add(COLON));
    }

    /** Значение для найденного узла — явная правка. */
    private byte[] valueOf(Node node) {
        return values[node.pathIds().getLast()];
    }

    /** Значение для отсутствующего узла: явная правка, иначе объект из недостающих ключей ниже по дереву. */
    private Parts appendValue(Node node, Parts out) {
        if (!node.pathIds().isEmpty()) {
            return out.add(valueOf(node));
        }
        if (node.keys().isEmpty()) {
            throw new IllegalArgumentException("cannot create array element for " + node.path());
        }
        out.add(OPEN);
        for (int i = 0; i < node.keys().size(); i++) {
            if (i > 0) {
                out.add(COMMA);
            }
            KeyChild child = node.keys().get(i);
            appendValue(child.node(), out.add(JsonStrings.quote(child.name())).add(COLON));
        }
        return out.add(CLOSE);
    }

    private byte[] build() {
        if (firstReplacement != null && furtherReplacements == null && insertions == null) {
            return replaceSingle(firstReplacement);
        }
        List<Patch> splices = orderedSplices();
        byte[] result = new byte[resultLength(splices)];
        int src = 0;
        int dst = 0;
        for (Patch splice : splices) {
            int untouched = splice.start() - src;
            System.arraycopy(doc, src, result, dst, untouched);
            dst += untouched;
            dst = splice.copyTo(result, dst);
            src = splice.end();
        }
        System.arraycopy(doc, src, result, dst, doc.length - src);
        return result;
    }

    private byte[] replaceSingle(Splice splice) {
        byte[] result = new byte[Math.toIntExact((long) doc.length + splice.bytes.length - (splice.end - splice.start))];
        System.arraycopy(doc, 0, result, 0, splice.start);
        System.arraycopy(splice.bytes, 0, result, splice.start, splice.bytes.length);
        System.arraycopy(doc, splice.end, result, splice.start + splice.bytes.length, doc.length - splice.end);
        return result;
    }

    private List<Patch> orderedSplices() {
        int size = (firstReplacement == null ? 0 : 1)
                + (furtherReplacements == null ? 0 : furtherReplacements.size())
                + (insertions == null ? 0 : insertions.size());
        List<Patch> splices = new ArrayList<>(size);
        if (firstReplacement != null) {
            splices.add(firstReplacement);
        }
        if (furtherReplacements != null) {
            splices.addAll(furtherReplacements);
        }
        if (insertions != null) {
            insertions.forEach((at, insertion) -> splices.add(new Insert(at, insertionParts(insertion))));
        }
        splices.sort(Comparator.comparingInt(Patch::start).thenComparingInt(Patch::end));
        rejectOverlaps(splices);
        return splices;
    }

    private static Parts insertionParts(Insertion insertion) {
        Parts out = new Parts();
        boolean first = true;
        for (Parts member : insertion.members.values()) {
            if (!first) {
                out.add(COMMA);
            }
            member.chunks.forEach(out::add);
            first = false;
        }
        if (!insertion.emptyParent) {
            out.add(COMMA);
        }
        return out;
    }

    private static void rejectOverlaps(List<Patch> splices) {
        for (int i = 1; i < splices.size(); i++) {
            Patch previous = splices.get(i - 1);
            Patch current = splices.get(i);
            if (current.start() < previous.end() || (current.start() == previous.start() && previous.end() > previous.start())) {
                throw new IllegalArgumentException(
                        "conflicting edits: [" + previous.start() + "," + previous.end() + ") and ["
                                + current.start() + "," + current.end() + ")");
            }
        }
    }

    private int resultLength(List<Patch> splices) {
        long length = doc.length;
        for (Patch splice : splices) {
            length += splice.length() - (splice.end() - splice.start());
        }
        return Math.toIntExact(length);
    }
}
