package ru.sber.lazyjson;

import ru.sber.lazyjson.impl.PathTrie;
import ru.sber.lazyjson.impl.Splicer;
import ru.sber.lazyjson.impl.TrieWalker;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * JSON-документ как массив байт: поиск и правка значений по пути без разбора в дерево.
 * <pre>
 *   LazyJson json = LazyJson.of(bytes);
 *   Slice id      = json.find("$client.id");                 // диапазон в исходном массиве, любой тип
 *   Slice id      = json.find(List.of("client", "id"));      // тот же путь списком ключей
 *   List&lt;Slice&gt; ids = json.findAll("$clients[*].id");
 *   byte[] out    = json.set("$client.name", "Ann");           // любой объект → сериализуется (Map, List, POJO…)
 *   byte[] out    = json.set("$client", jsonBytes);            // byte[] — уже готовый JSON, вклеивается как есть
 *   byte[] out    = json.setRaw("$client.age", "42");          // готовый JSON-текст
 *   byte[] out    = json.edit().set("$a", 1).set("$b.c", map).apply();   // несколько правок за один проход
 * </pre>
 * Сериализатор — {@link JsonEncoder}, по умолчанию Jackson; свой — через {@link #of(byte[], JsonEncoder)}.
 * Документ и сырые значения должны быть корректным JSON в UTF-8. Это не валидатор:
 * пропускаемые значения полностью не проверяются. Документ не мутируется — правки возвращают новый массив.
 */
public final class LazyJson {

    private final byte[] doc;
    private final JsonEncoder encoder;

    private LazyJson(byte[] doc, JsonEncoder encoder) {
        this.doc = doc;
        this.encoder = encoder;
    }

    public static LazyJson of(byte[] doc) {
        return new LazyJson(doc, JsonEncoder.DEFAULT);
    }

    public static LazyJson of(byte[] doc, JsonEncoder encoder) {
        return new LazyJson(doc, encoder);
    }

    public byte[] bytes() {
        return doc;
    }

    // --- чтение --------------------------------------------------------------------------------------

    /** Первое найденное значение или {@code null}. */
    public Slice find(String path) {
        return find(JsonPath.compile(path));
    }

    /** Путь как список ключей объектов: {@code find(List.of("client", "id"))}. */
    public Slice find(List<String> keys) {
        return find(JsonPath.of(keys));
    }

    /** Ключи из {@code keys[from..]}. */
    public Slice find(String[] keys, int from) {
        return find(JsonPath.of(keys, from));
    }

    public Slice find(JsonPath path) {
        FirstSliceCollector collector = new FirstSliceCollector();
        TrieWalker.walk(doc, trieOf(path), collector);
        return collector.slice;
    }

    /** Все найденные значения (для путей с {@code [*]}) в порядке следования в документе. */
    public List<Slice> findAll(String path) {
        return findAll(JsonPath.compile(path));
    }

    public List<Slice> findAll(List<String> keys) {
        return findAll(JsonPath.of(keys));
    }

    public List<Slice> findAll(String[] keys, int from) {
        return findAll(JsonPath.of(keys, from));
    }

    public List<Slice> findAll(JsonPath path) {
        SliceCollector collector = new SliceCollector();
        TrieWalker.walk(doc, trieOf(path), collector);
        return collector.inDocumentOrder(path.rootFromEnd());
    }

    // --- правка --------------------------------------------------------------------------------------

    /**
     * Заменяет значение по пути или вставляет его (включая недостающие промежуточные объекты).
     * {@code value} сериализуется энкодером; {@code byte[]} считается готовым JSON и вклеивается как есть.
     * <p>
     * Энкодер по умолчанию кэширует байты по экземпляру объекта (строки — по значению): объект после
     * вставки нельзя мутировать, а массивы, полученные из {@link Edit#value()}, нельзя переписывать.
     * Путь с {@code [*]} над пустым массивом ничего не меняет и не считается ошибкой.
     */
    public byte[] set(String path, Object value) {
        return set(JsonPath.compile(path), value);
    }

    public byte[] set(List<String> keys, Object value) {
        return set(JsonPath.of(keys), value);
    }

    public byte[] set(String[] keys, int from, Object value) {
        return set(JsonPath.of(keys, from), value);
    }

    public byte[] set(JsonPath path, Object value) {
        return Splicer.apply(doc, trieOf(path), new byte[][]{toJson(value)});
    }

    /** {@code json} — готовый JSON-текст, вклеивается как есть. */
    public byte[] setRaw(String path, String json) {
        return set(JsonPath.compile(path), json.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] setRaw(List<String> keys, String json) {
        return set(JsonPath.of(keys), json.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] setRaw(String[] keys, int from, String json) {
        return set(JsonPath.of(keys, from), json.getBytes(StandardCharsets.UTF_8));
    }

    /** Несколько правок за один проход по документу и одну сборку результата. */
    public Edits edit() {
        return new Edits();
    }

    /**
     * Применяет заранее собранные правки общим обходом и одной сборкой результата.
     * Пересекающиеся правки и несовместимые типы контейнеров отвергаются; для одинакового пути действует последняя.
     */
    public byte[] apply(List<Edit> edits) {
        if (edits.size() == 1) {
            Edit edit = edits.getFirst();
            return set(edit.path(), edit.value());
        }
        List<JsonPath> paths = new ArrayList<>(edits.size());
        byte[][] values = new byte[edits.size()][];
        int index = 0;
        for (Edit edit : edits) {
            paths.add(edit.path());
            values[index++] = edit.value();
        }
        return Splicer.apply(doc, PathTrie.of(paths), values);
    }

    /** Билдер батча правок: {@code json.edit().set("$a", 1).set("$b", map).apply()}. */
    public final class Edits {

        private final List<Edit> edits = new ArrayList<>();

        private Edits() {
        }

        public Edits set(String path, Object value) {
            return set(JsonPath.compile(path), value);
        }

        public Edits set(List<String> keys, Object value) {
            return set(JsonPath.of(keys), value);
        }

        public Edits set(String[] keys, int from, Object value) {
            return set(JsonPath.of(keys, from), value);
        }

        public Edits set(JsonPath path, Object value) {
            edits.add(new Edit(path, toJson(value)));
            return this;
        }

        public Edits setRaw(String path, String json) {
            return set(JsonPath.compile(path), json.getBytes(StandardCharsets.UTF_8));
        }

        public Edits setRaw(List<String> keys, String json) {
            return set(JsonPath.of(keys), json.getBytes(StandardCharsets.UTF_8));
        }

        public Edits setRaw(String[] keys, int from, String json) {
            return set(JsonPath.of(keys, from), json.getBytes(StandardCharsets.UTF_8));
        }

        public byte[] apply() {
            return LazyJson.this.apply(edits);
        }
    }

    // --- внутреннее ----------------------------------------------------------------------------------

    /** Дерево одиночного пути живёт в самом {@link JsonPath} и строится один раз. */
    private static PathTrie trieOf(JsonPath path) {
        return path.trie(p -> PathTrie.of(List.of(p)));
    }

    private abstract static class ReadCollector implements TrieWalker.Sink {

        @Override
        public boolean needsMissing() {
            return false;
        }

        @Override
        public boolean missing(PathTrie.KeyChild child, int insertAt, boolean emptyParent) {
            return true;
        }

        @Override
        public boolean blocked(PathTrie.Node child, int at) {
            return true;
        }

        @Override
        public boolean outOfRange(PathTrie.Node child, int size, int at) {
            return true;
        }
    }

    private final class FirstSliceCollector extends ReadCollector {

        private Slice slice;

        @Override
        public boolean found(PathTrie.Node node, int start, int end) {
            slice = new Slice(doc, start, end - start);
            return false;
        }
    }

    private final class SliceCollector extends ReadCollector {

        private final List<Slice> slices = new ArrayList<>();

        private List<Slice> inDocumentOrder(boolean fromEnd) {
            if (fromEnd && slices.size() > 1) {
                slices.sort(Comparator.comparingInt(Slice::offset));
            }
            return slices;
        }

        @Override
        public boolean found(PathTrie.Node node, int start, int end) {
            slices.add(new Slice(doc, start, end - start));
            return true;
        }
    }

    private byte[] toJson(Object value) {
        return value instanceof byte[] json ? json : encoder.encode(value);
    }
}
