package ru.sber.lazyjson;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Скомпилированный линейный путь, всегда от корня {@code $}: {@code $key1.key2.a.b},
 * элемент массива {@code $items[0].id}, все элементы {@code $items[*].id},
 * ключ с точкой {@code $['a.b'].c}. Сам по себе {@code $} — весь документ.
 * <p>
 * Хинт {@code <$key1.key2}: ключ корневого объекта ближе к концу — корень обходится с конца.
 * Выгоден, когда значения хвостовых ключей корня малы: чтобы прочитать ключ, его значение
 * приходится пропустить назад целиком.
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

    private final String text;
    private final List<Segment> segments;
    private final boolean hasWildcard;
    private final boolean rootFromEnd;

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
        boolean rootFromEnd = text.startsWith("<");
        JsonPath path = new JsonPath(text, parse(rootFromEnd ? text.substring(1) : text), rootFromEnd);
        if (CACHE.size() < CACHE_LIMIT) {
            cached = CACHE.putIfAbsent(text, path);
        }
        return cached != null ? cached : path;
    }

    public List<Segment> segments() {
        return segments;
    }

    public boolean hasWildcard() {
        return hasWildcard;
    }

    /** Корневой объект обходить с конца ({@code <$...}). */
    public boolean rootFromEnd() {
        return rootFromEnd;
    }

    @Override
    public String toString() {
        return text;
    }

    private static List<Segment> parse(String text) {
        if (!text.startsWith("$")) {
            throw invalid(text, 0);
        }
        List<Segment> segments = new ArrayList<>();
        int p = 1;
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

    private static int parseIndex(String text, String digits, int at) {
        try {
            int index = Integer.parseInt(digits);
            if (index < 0) {
                throw invalid(text, at);
            }
            return index;
        } catch (NumberFormatException e) {
            throw invalid(text, at);
        }
    }

    private static IllegalArgumentException invalid(String text, int at) {
        return new IllegalArgumentException("invalid path '" + text + "' at " + at);
    }
}
