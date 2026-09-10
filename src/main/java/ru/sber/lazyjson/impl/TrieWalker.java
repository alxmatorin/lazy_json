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
 * Корневой объект по хинту пути ({@code <$key}) обходится с конца: значение встречается раньше ключа,
 * поэтому каждое значение пропускается назад, затем читается его ключ.
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
    private boolean stopped;

    private TrieWalker(byte[] doc, Sink sink) {
        this.doc = doc;
        this.in = new ByteScanner(doc);
        this.sink = sink;
    }

    public static void walk(byte[] doc, PathTrie trie, Sink sink) {
        new TrieWalker(doc, sink).walkRoot(trie);
    }

    private void walkRoot(PathTrie trie) {
        int start = in.skipWhitespace(0);
        Node root = trie.root();
        if (trie.rootFromEnd() && doc[start] == '{' && !root.keys().isEmpty()) {
            int end = walkRootObjectBackward(start, root);
            reportFound(root, start, end);
        } else {
            walk(start, root);
        }
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

    /** Корневой объект с конца: конец документа известен, поэтому его хвост достижим без прохода по началу. */
    private int walkRootObjectBackward(int start, Node node) {
        List<KeyChild> keys = node.keys();
        boolean[] seen = sink.needsMissing() ? new boolean[keys.size()] : null;
        int close = in.expect(in.skipWhitespaceBack(doc.length - 1), '}');
        int p = in.skipWhitespaceBack(close - 1);
        if (p == start) {
            reportMissing(keys, seen, start + 1, true);
            return close + 1;
        }
        while (true) {
            int valueStart = in.skipValueBack(p);
            int colon = in.expect(in.skipWhitespaceBack(valueStart - 1), ':');
            int keyClose = in.expect(in.skipWhitespaceBack(colon - 1), '"');
            int keyStart = in.skipStringBack(keyClose);
            int child = matchKey(keys, keyStart + 1, keyClose);
            if (child >= 0) {
                if (seen != null) {
                    seen[child] = true;
                }
                Node target = keys.get(child).node();
                if (target.hasChildren()) {
                    walk(valueStart, target);
                } else {
                    reportFound(target, valueStart, p + 1);
                }
                if (stopped) {
                    return -1;
                }
            }
            p = in.skipWhitespaceBack(keyStart - 1);
            if (doc[p] == ',') {
                p = in.skipWhitespaceBack(p - 1);
            } else if (doc[p] == '{' && p == start) {
                break;
            } else {
                throw in.malformed("expected ',' or '{'", p);
            }
        }
        reportMissing(keys, seen, start + 1, false);
        return close + 1;
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
