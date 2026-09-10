package ru.sber.lazyjson;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Скомпилированный линейный путь, всегда от корня {@code $}: {@code $key1.key2.a.b},
 * элемент массива {@code $items[0].id}, все элементы {@code $items[*].id},
 * ключ с точкой {@code $['a.b'].c}. Сам по себе {@code $} — весь документ.
 * Тот же путь из ключей без синтаксиса: {@link #of(List)} — {@code JsonPath.of(List.of("key1", "key2", "a", "b"))}.
 * <p>
 * Хинт {@code $<key1.key2}: ключ корневого объекта ближе к концу — корень обходится с конца.
 * Выгоден, когда значения хвостовых ключей корня малы: чтобы прочитать ключ, его значение
 * приходится пропустить назад целиком. Документ должен состоять ровно из одного корневого значения
 * (после {@code }} — только пробелы); при повторяющихся ключах корня с конца находится последний.
 * <p>
 * Неизменяем; {@link #compile} кэширует результат по тексту пути, так что повторные вызовы
 * с одной строкой возвращают один и тот же экземпляр без разбора, пока в кэше есть место.
 */
public final class JsonPath {

    public sealed interface Segment permits Key, Index, Wildcard {}

    public record Key(String name) implements Segment {}

    public record Index(int index) implements Segment {}

    public record Wildcard() implements Segment {}

    private static final int CACHE_LIMIT = 10_000;
    private static final ConcurrentHashMap<String, JsonPath> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<List<String>, JsonPath> KEYS_CACHE = new ConcurrentHashMap<>();

    private final String text;
    private final List<Segment> segments;
    private final boolean hasWildcard;
    private final boolean rootFromEnd;
    /** Дерево одиночного пути, построенное {@code LazyJson} при первом использовании; тип скрыт от этого пакета. */
    private volatile Object trie;

    private JsonPath(String text, List<Segment> segments, boolean rootFromEnd) {
        this.text = text;
        this.segments = List.copyOf(segments);
        this.hasWildcard = segments.stream().anyMatch(Wildcard.class::isInstance);
        this.rootFromEnd = rootFromEnd;
    }

    public static JsonPath compile(String text) {
        JsonPath cached = CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        boolean rootFromEnd = text.startsWith("$<");
        JsonPath path = new JsonPath(text, parse(text, rootFromEnd), rootFromEnd);
        if (CACHE.size() < CACHE_LIMIT) {
            cached = CACHE.putIfAbsent(text, path);
        }
        return cached != null ? cached : path;
    }

    /**
     * Путь из ключей объектов (без индексов и {@code [*]}); кэшируется по списку.
     * Если первый ключ начинается с {@code <}, это хинт «обходить корень с конца», сам ключ — без {@code <}:
     * {@code of(List.of("<key1", "key2"))} ≡ {@code compile("$<key1.key2")}.
     */
    public static JsonPath of(List<String> keys) {
        JsonPath cached = KEYS_CACHE.get(keys);
        if (cached != null) {
            return cached;
        }
        List<String> frozen = List.copyOf(keys);
        boolean rootFromEnd = !frozen.isEmpty() && frozen.getFirst().startsWith("<");
        List<String> names = rootFromEnd ? withFirstUnmarked(frozen) : frozen;
        JsonPath path = new JsonPath(textOf(names, rootFromEnd), names.stream().<Segment>map(Key::new).toList(), rootFromEnd);
        if (KEYS_CACHE.size() < CACHE_LIMIT) {
            cached = KEYS_CACHE.putIfAbsent(frozen, path);
        }
        return cached != null ? cached : path;
    }

    private static List<String> withFirstUnmarked(List<String> keys) {
        List<String> names = new ArrayList<>(keys);
        names.set(0, keys.getFirst().substring(1));
        return names;
    }

    public static JsonPath of(String... keys) {
        return of(List.of(keys));
    }

    /** Ключи из {@code keys[from..]} — хвост массива, например после префикса, который уже разобран; хинт {@code <} — у {@code keys[from]}. */
    public static JsonPath of(String[] keys, int from) {
        if (from < 0 || from > keys.length) {
            throw new IndexOutOfBoundsException("from " + from + " is outside of " + keys.length + " keys");
        }
        return of(java.util.Arrays.asList(keys).subList(from, keys.length));
    }

    public List<Segment> segments() {
        return segments;
    }

    public boolean hasWildcard() {
        return hasWildcard;
    }

    /** Корневой объект обходить с конца ({@code $<...}). */
    public boolean rootFromEnd() {
        return rootFromEnd;
    }

    @Override
    public String toString() {
        return text;
    }

    /** Кэш-слот для скомпилированного дерева: строится один раз, гонка при первом обращении безвредна. */
    <T> T trie(java.util.function.Function<JsonPath, T> builder) {
        @SuppressWarnings("unchecked")
        T built = (T) trie;
        if (built == null) {
            built = builder.apply(this);
            trie = built;
        }
        return built;
    }

    /** Текстовая форма пути из ключей: {@code $a.b}, ключ с {@code .}/{@code [}/{@code ']} — в {@code ['…']}. */
    private static String textOf(List<String> keys, boolean rootFromEnd) {
        StringBuilder text = new StringBuilder(rootFromEnd ? "$<" : "$");
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (key.isEmpty() || key.contains(".") || key.contains("[") || key.contains("']")) {
                text.append("['").append(key).append("']");
            } else {
                text.append(i == 0 ? "" : ".").append(key);
            }
        }
        return text.toString();
    }

    private static List<Segment> parse(String text, boolean rootFromEnd) {
        if (!text.startsWith("$")) {
            throw invalid(text, 0);
        }
        List<Segment> segments = new ArrayList<>();
        int p = rootFromEnd ? 2 : 1;
        while (p < text.length()) {
            char c = text.charAt(p);
            if (c == '.') {
                p = parseDotKey(text, p + 1, segments);
            } else if (c == '[') {
                p = parseBracket(text, p + 1, segments);
            } else if (segments.isEmpty()) {
                p = parseDotKey(text, p, segments);
            } else {
                throw invalid(text, p);
            }
        }
        return segments;
    }

    private static int parseDotKey(String text, int from, List<Segment> segments) {
        int to = from;
        while (to < text.length() && text.charAt(to) != '.' && text.charAt(to) != '[') {
            to++;
        }
        if (to == from) {
            throw invalid(text, from);
        }
        segments.add(new Key(text.substring(from, to)));
        return to;
    }

    private static int parseBracket(String text, int from, List<Segment> segments) {
        if (text.startsWith("'", from)) {
            return parseQuotedKey(text, from + 1, segments);
        }
        int close = text.indexOf(']', from);
        if (close < 0) {
            throw invalid(text, from);
        }
        String inside = text.substring(from, close);
        if (inside.equals("*")) {
            segments.add(new Wildcard());
        } else {
            segments.add(new Index(parseIndex(text, inside, from)));
        }
        return close + 1;
    }

    /** {@code ['имя']} — имя с любыми символами, кроме последовательности {@code ']}. */
    private static int parseQuotedKey(String text, int from, List<Segment> segments) {
        int close = text.indexOf("']", from);
        if (close < 0) {
            throw invalid(text, from);
        }
        segments.add(new Key(text.substring(from, close)));
        return close + 2;
    }

    /** Только цифры без знака и ведущих нулей: {@code 0}, {@code 12}; {@code +1}, {@code 01} — ошибка. */
    private static int parseIndex(String text, String digits, int at) {
        boolean plain = !digits.isEmpty() && digits.chars().allMatch(c -> c >= '0' && c <= '9')
                && (digits.length() == 1 || digits.charAt(0) != '0');
        if (!plain) {
            throw invalid(text, at);
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw invalid(text, at);
        }
    }

    private static IllegalArgumentException invalid(String text, int at) {
        return new IllegalArgumentException("invalid path '" + text + "' at " + at);
    }
}
