package org.example.json.bench;

import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.util.RawValue;
import ru.sber.lazyjson.JsonPath;
import ru.sber.lazyjson.JsonPath.Segment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Полный цикл через дерево: byte[] → {@code Map<String, Object>} → навигация по мапе → byte[].
 * Обе конвертации выполняются в каждой операции — это и есть измеряемая стоимость подхода.
 * {@code Map}/{@code List} кладётся в дерево как есть, {@code byte[]} — как {@link RawValue},
 * который сериализатор пишет без разбора.
 */
public final class JacksonStrategy implements Strategy {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String name() {
        return "jackson";
    }

    @Override
    public int run(Op op, byte[] doc, int iteration) {
        return switch (op) {
            case Op.Find find -> find(doc, JsonPath.compile(find.path()).segments());
            case Op.FindMany many -> findMany(doc, many.paths());
            case Op.Replace replace -> replace(doc, replace.replacements(), iteration);
        };
    }

    private static int findMany(byte[] doc, List<String> paths) {
        Map<String, Object> root = parse(doc);
        int matches = 0;
        for (String path : paths) {
            matches += countMatches(root, JsonPath.compile(path).segments(), 0);
        }
        root.put("touched", true);
        return matches + serialize(root).length;
    }

    private static int find(byte[] doc, List<Segment> segments) {
        Map<String, Object> root = parse(doc);
        int matches = countMatches(root, segments, 0);
        root.put("touched", true);
        return matches + serialize(root).length;
    }

    private static int replace(byte[] doc, List<Op.Replacement> replacements, int iteration) {
        Map<String, Object> root = parse(doc);
        for (Op.Replacement replacement : replacements) {
            put(root, JsonPath.compile(replacement.path()).segments(), treeValue(replacement.valueAt().apply(iteration)));
        }
        return serialize(root).length;
    }

    private static Object treeValue(Object value) {
        return value instanceof byte[] bytes
                ? new RawValue(new SerializedString(new String(bytes, StandardCharsets.UTF_8)))
                : value;
    }

    private static Map<String, Object> parse(byte[] doc) {
        try {
            return Json.MAPPER.readValue(doc, MAP_TYPE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] serialize(Map<String, Object> root) {
        try {
            return Json.MAPPER.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int countMatches(Object node, List<Segment> segments, int from) {
        if (node == null) {
            return 0;
        }
        if (from == segments.size()) {
            return 1;
        }
        return switch (segments.get(from)) {
            case JsonPath.Key key -> node instanceof Map<?, ?> map ? countMatches(map.get(key.name()), segments, from + 1) : 0;
            case JsonPath.Index index -> node instanceof List<?> list && index.index() < list.size()
                    ? countMatches(list.get(index.index()), segments, from + 1) : 0;
            case JsonPath.Wildcard w -> {
                int count = 0;
                if (node instanceof List<?> list) {
                    for (Object element : list) {
                        count += countMatches(element, segments, from + 1);
                    }
                }
                yield count;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, List<Segment> segments, Object value) {
        Object parent = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            parent = switch (segments.get(i)) {
                case JsonPath.Key key -> ((Map<String, Object>) parent).computeIfAbsent(key.name(), k -> new LinkedHashMap<>());
                case JsonPath.Index index -> ((List<Object>) parent).get(index.index());
                case JsonPath.Wildcard w -> throw new IllegalArgumentException("wildcard put is not supported");
            };
        }
        switch (segments.getLast()) {
            case JsonPath.Key key -> ((Map<String, Object>) parent).put(key.name(), value);
            case JsonPath.Index index -> ((List<Object>) parent).set(index.index(), value);
            case JsonPath.Wildcard w -> throw new IllegalArgumentException("wildcard put is not supported");
        }
    }
}
