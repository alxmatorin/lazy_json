package ru.sber.jsonbytes.impl;

import ru.sber.jsonbytes.ElementPath;

import java.util.ArrayList;
import java.util.List;

/**
 * Несколько путей, слитых в дерево: общий префикс обходится один раз.
 * Один путь — вырожденное дерево-цепочка. Идентификатор пути = его индекс в списке.
 */
public final class PathTrie {

    public static final class Node {

        private final String path;
        private final List<Integer> pathIds = new ArrayList<>(1);
        private final List<KeyChild> keys = new ArrayList<>(1);
        private final List<IndexChild> indices = new ArrayList<>(0);
        private Node wildcard;

        private Node(String path) {
            this.path = path;
        }

        /** Путь до этого узла, для сообщений об ошибках. */
        public String path() {
            return path;
        }

        /** Пути, которые заканчиваются в этом узле (пусто — узел промежуточный). */
        public List<Integer> pathIds() {
            return pathIds;
        }

        public List<KeyChild> keys() {
            return keys;
        }

        public List<IndexChild> indices() {
            return indices;
        }

        public Node wildcard() {
            return wildcard;
        }

        public boolean hasChildren() {
            return !keys.isEmpty() || !indices.isEmpty() || wildcard != null;
        }

        public IndexChild indexChild(int index) {
            for (IndexChild child : indices) {
                if (child.index == index) {
                    return child;
                }
            }
            return null;
        }

        private Node child(ElementPath.Segment segment) {
            return switch (segment) {
                case ElementPath.Key key -> keyChild(key.name()).node;
                case ElementPath.Index index -> indexChild(index.index(), true).node;
                case ElementPath.Wildcard w -> wildcardChild();
            };
        }

        private KeyChild keyChild(String name) {
            for (KeyChild child : keys) {
                if (child.name.equals(name)) {
                    return child;
                }
            }
            KeyChild child = new KeyChild(name, new Node(path + "." + name));
            keys.add(child);
            return child;
        }

        private IndexChild indexChild(int index, boolean create) {
            IndexChild existing = indexChild(index);
            if (existing != null || !create) {
                return existing;
            }
            IndexChild child = new IndexChild(index, new Node(path + "[" + index + "]"));
            indices.add(child);
            return child;
        }

        private Node wildcardChild() {
            if (wildcard == null) {
                wildcard = new Node(path + "[*]");
            }
            return wildcard;
        }
    }

    /** Переход по ключу: raw — UTF-8 имя, либо null для имени с одиночным суррогатом. */
    public record KeyChild(String name, byte[] raw, Node node) {

        private KeyChild(String name, Node node) {
            this(name, JsonStrings.rawKey(name), node);
        }
    }

    /** Переход по индексу массива. */
    public record IndexChild(int index, Node node) {
    }

    private final Node root = new Node("$");
    private boolean rootFromEnd;

    public static PathTrie of(List<ElementPath> paths) {
        PathTrie trie = new PathTrie();
        for (int id = 0; id < paths.size(); id++) {
            trie.add(paths.get(id), id);
        }
        return trie;
    }

    public Node root() {
        return root;
    }

    /** Хотя бы один путь просит обходить корневой объект с конца. */
    public boolean rootFromEnd() {
        return rootFromEnd;
    }

    private void add(ElementPath path, int id) {
        rootFromEnd |= path.rootFromEnd();
        Node node = root;
        for (ElementPath.Segment segment : path.segments()) {
            if (!node.pathIds.isEmpty()) {
                throw new IllegalArgumentException("conflicting edits at " + node.path);
            }
            rejectMixedContainers(node, segment);
            node = node.child(segment);
        }
        if (node.hasChildren()) {
            throw new IllegalArgumentException("conflicting edits at " + node.path);
        }
        node.pathIds.add(id);
    }

    private static void rejectMixedContainers(Node node, ElementPath.Segment segment) {
        boolean key = segment instanceof ElementPath.Key;
        if (key ? !node.indices.isEmpty() || node.wildcard != null : !node.keys.isEmpty()) {
            throw new IllegalArgumentException("incompatible object and array paths at " + node.path);
        }
    }
}
