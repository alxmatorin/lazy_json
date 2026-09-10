package ru.sber.lazyjson.impl;

import ru.sber.lazyjson.impl.PathTrie.IndexChild;
import ru.sber.lazyjson.impl.PathTrie.KeyChild;
import ru.sber.lazyjson.impl.PathTrie.Node;

import java.util.Arrays;
import java.util.List;

/**
 * Один линейный проход по байтам документа вдоль дерева путей.
 * Ключи без escape-последовательностей сравниваются как сырые байты, чужие значения
 * пропускаются {@link ByteScanner}-ом. Ключом считается только строка в позиции ключа объекта —
 * текст внутри значений никогда не матчится.
 * <p>
 * Корневой объект идёт через {@link RootIndex}: члены, пройденные раньше, берутся по смещению, остальные
 * досканируются от границы (по хинту {@code $<key} — с конца: значение встречается раньше ключа,
 * поэтому оно пропускается назад, затем читается ключ). Конец корня никому не нужен, поэтому обход
 * прекращается, как только все ключи дерева встречены.
 */
public final class TrieWalker {

    public interface Sink {

        /** Нужен ли учёт отсутствующих ключей и несовместимых контейнеров. */
        default boolean needsMissing() {
            return true;
        }

        /** Значение по пути найдено: {@code doc[start, end)}. Вернуть {@code false}, чтобы остановить обход. */
        boolean found(Node node, int start, int end);

        /** В объекте нет ключа {@code child}; вставлять можно сразу после {@code '{'}, в позицию {@code insertAt}. */
        boolean missing(KeyChild child, int insertAt, boolean emptyParent);

        /** Путь не может продолжиться: значение в {@code at} — не объект/массив нужного вида. */
        boolean blocked(Node child, int at);

        /** Индекс {@code child} за пределами массива из {@code size} элементов, начинающегося в {@code at}. */
        boolean outOfRange(Node child, int size, int at);
    }

    private final byte[] doc;
    private final ByteScanner in;
    private final Sink sink;
    private final RootIndex index;
    private boolean stopped;

    private TrieWalker(byte[] doc, Sink sink, RootIndex index) {
        this.doc = doc;
        this.in = new ByteScanner(doc);
        this.sink = sink;
        this.index = index;
    }

    /** @param index смещения членов корня от предыдущих обходов этого документа; пополняется по ходу */
    public static void walk(byte[] doc, PathTrie trie, Sink sink, RootIndex index) {
        new TrieWalker(doc, sink, index).walkRoot(trie);
    }

    private void walkRoot(PathTrie trie) {
        int start = in.skipWhitespace(0);
        Node root = trie.root();
        if (doc[start] == '{' && !root.keys().isEmpty()) {
            walkRootIndexed(start, root, trie.rootFromEnd());
        } else {
            walk(start, root);
        }
    }

    // --- корень с индексом ---------------------------------------------------------------------------

    /**
     * Известные ключи берутся из индекса без скана; остальные досканируются от границы непросканированной
     * области (вперёд или, по хинту, назад), и каждый пройденный член попадает в индекс.
     * Конец корня никому не нужен, поэтому обход прекращается, как только все ключи дерева встречены.
     */
    private void walkRootIndexed(int start, Node node, boolean fromEnd) {
        index.init(doc, in, start);
        List<KeyChild> keys = node.keys();
        boolean[] seen = new boolean[keys.size()];
        int remaining = walkIndexedKeys(keys, seen);
        if (stopped) {
            return;
        }
        if (remaining > 0 && !index.complete()) {
            remaining = fromEnd ? scanRootBackward(keys, seen, remaining) : scanRootForward(keys, seen, remaining);
        }
        if (!stopped && remaining > 0 && index.complete()) {
            reportMissing(keys, sink.needsMissing() ? seen : null, start + 1, index.size() == 0);
        }
    }

    private int walkIndexedKeys(List<KeyChild> keys, boolean[] seen) {
        int remaining = keys.size();
        for (int i = 0; i < keys.size() && !stopped; i++) {
            int entry = index.lookup(keys.get(i));
            if (entry >= 0) {
                seen[i] = true;
                remaining--;
                Node target = keys.get(i).node();
                if (target.hasChildren()) {
                    walk(index.valueStart(entry), target);
                } else {
                    reportFound(target, index.valueStart(entry), index.valueEnd(entry));
                }
            }
        }
        return remaining;
    }

    private int scanRootForward(List<KeyChild> keys, boolean[] seen, int remaining) {
        int p = index.forward();
        while (doc[p] != '}') {
            int keyStart = in.expect(p, '"');
            int keyEnd = in.skipString(keyStart);
            boolean escaped = in.lastStringEscaped();
            int valueStart = in.skipWhitespace(in.expect(in.skipWhitespace(keyEnd), ':') + 1);
            int child = matchKey(keys, keyStart + 1, keyEnd - 1);
            int valueEnd;
            if (child >= 0) {
                seen[child] = true;
                remaining--;
                valueEnd = walk(valueStart, keys.get(child).node());
            } else {
                valueEnd = in.skipValue(valueStart);
            }
            if (valueEnd < 0) {
                return remaining; // остановлены внутри значения, его конец неизвестен
            }
            index.add(keyStart + 1, keyEnd - 1, escaped, valueStart, valueEnd);
            p = in.skipWhitespace(valueEnd);
            if (doc[p] == ',') {
                p = in.skipWhitespace(p + 1);
            } else if (doc[p] != '}') {
                throw in.malformed("expected ',' or '}'", p);
            }
            index.forward(p);
            if (stopped || remaining == 0 || index.complete()) {
                return remaining;
            }
        }
        index.markComplete();
        return remaining;
    }

    private int scanRootBackward(List<KeyChild> keys, boolean[] seen, int remaining) {
        int p = index.backward();
        while (p != index.rootStart()) {
            int valueStart = in.skipValueBack(p);
            int colon = in.expect(in.skipWhitespaceBack(valueStart - 1), ':');
            int keyClose = in.expect(in.skipWhitespaceBack(colon - 1), '"');
            int keyStart = in.skipStringBack(keyClose);
            boolean escaped = in.lastStringEscaped();
            int child = matchKey(keys, keyStart + 1, keyClose);
            if (child >= 0) {
                seen[child] = true;
                remaining--;
                Node target = keys.get(child).node();
                if (target.hasChildren()) {
                    walk(valueStart, target);
                } else {
                    reportFound(target, valueStart, p + 1);
                }
            }
            index.add(keyStart + 1, keyClose, escaped, valueStart, p + 1);
            p = in.skipWhitespaceBack(keyStart - 1);
            if (doc[p] == ',') {
                p = in.skipWhitespaceBack(p - 1);
            } else if (doc[p] != '{') {
                throw in.malformed("expected ',' or '{'", p);
            }
            index.backward(p);
            if (stopped || remaining == 0 || index.complete()) {
                return remaining;
            }
        }
        index.markComplete();
        return remaining;
    }

    /** @return конец значения (исключительно), либо любое значение, если обход остановлен */
    private int walk(int start, Node node) {
        int end = descend(start, node);
        reportFound(node, start, end);
        return end;
    }

    private void reportFound(Node node, int start, int end) {
        if (!stopped && !node.pathIds().isEmpty()) {
            report(sink.found(node, start, end));
        }
    }

    private int descend(int start, Node node) {
        byte first = doc[start];
        if (first == '{' && !node.keys().isEmpty()) {
            return walkObject(start, node);
        }
        if (first == '[' && (node.wildcard() != null || !node.indices().isEmpty())) {
            return walkArray(start, node);
        }
        if (node.hasChildren()) {
            reportBlockedChildren(node, start);
        }
        return in.skipValue(start);
    }

    private int walkObject(int start, Node node) {
        List<KeyChild> keys = node.keys();
        boolean[] seen = sink.needsMissing() ? new boolean[keys.size()] : null;
        int p = in.skipWhitespace(start + 1);
        if (doc[p] == '}') {
            reportMissing(keys, seen, start + 1, true);
            return p + 1;
        }
        while (true) {
            int keyStart = in.expect(p, '"');
            int keyEnd = in.skipString(keyStart);
            p = in.skipWhitespace(in.expect(in.skipWhitespace(keyEnd), ':') + 1);
            int child = matchKey(keys, keyStart + 1, keyEnd - 1);
            if (child >= 0) {
                if (seen != null) {
                    seen[child] = true;
                }
                p = walk(p, keys.get(child).node());
                if (stopped) {
                    return -1;
                }
            } else {
                p = in.skipValue(p);
            }
            p = in.skipWhitespace(p);
            if (doc[p] == ',') {
                p = in.skipWhitespace(p + 1);
            } else if (doc[p] == '}') {
                break;
            } else {
                throw in.malformed("expected ',' or '}'", p);
            }
        }
        reportMissing(keys, seen, start + 1, false);
        return p + 1;
    }

    private int matchKey(List<KeyChild> keys, int from, int to) {
        String decoded = null;
        for (int i = 0; i < keys.size(); i++) {
            KeyChild key = keys.get(i);
            byte[] raw = key.raw();
            if (!in.lastStringEscaped()) {
                if (raw != null && to - from == raw.length && Arrays.equals(doc, from, to, raw, 0, raw.length)) {
                    return i;
                }
            } else if (raw == null || to - from >= raw.length) {
                // escape только удлиняет запись: диапазон короче имени — совпадения нет, декодировать незачем
                if (decoded == null) {
                    decoded = JsonStrings.unescape(doc, from, to);
                }
                if (decoded.equals(key.name())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void reportMissing(List<KeyChild> keys, boolean[] seen, int insertAt, boolean emptyParent) {
        if (seen == null) {
            return;
        }
        for (int i = 0; i < keys.size() && !stopped; i++) {
            if (!seen[i]) {
                report(sink.missing(keys.get(i), insertAt, emptyParent));
            }
        }
    }

    private int walkArray(int start, Node node) {
        int p = in.skipWhitespace(start + 1);
        int index = 0;
        if (doc[p] != ']') {
            while (true) {
                p = walkElement(p, index, node);
                if (stopped) {
                    return -1;
                }
                p = in.skipWhitespace(p);
                if (doc[p] == ',') {
                    p = in.skipWhitespace(p + 1);
                    index++;
                } else if (doc[p] == ']') {
                    break;
                } else {
                    throw in.malformed("expected ',' or ']'", p);
                }
            }
            index++;
        }
        reportOutOfRange(node, index, start);
        return p + 1;
    }

    private int walkElement(int start, int index, Node node) {
        Node wildcard = node.wildcard();
        IndexChild byIndex = node.indexChild(index);
        if (wildcard == null) {
            return byIndex == null ? in.skipValue(start) : walk(start, byIndex.node());
        }
        int end = walk(start, wildcard);
        return byIndex != null && !stopped ? walk(start, byIndex.node()) : end;
    }

    private void reportOutOfRange(Node node, int size, int at) {
        if (!sink.needsMissing()) {
            return;
        }
        for (IndexChild child : node.indices()) {
            if (child.index() >= size && !stopped) {
                report(sink.outOfRange(child.node(), size, at));
            }
        }
    }

    private void reportBlockedChildren(Node node, int at) {
        if (!sink.needsMissing()) {
            return;
        }
        for (KeyChild child : node.keys()) {
            if (!stopped) {
                report(sink.blocked(child.node(), at));
            }
        }
        for (IndexChild child : node.indices()) {
            if (!stopped) {
                report(sink.blocked(child.node(), at));
            }
        }
        if (node.wildcard() != null && !stopped) {
            report(sink.blocked(node.wildcard(), at));
        }
    }

    private void report(boolean proceed) {
        if (!proceed) {
            stopped = true;
        }
    }
}
