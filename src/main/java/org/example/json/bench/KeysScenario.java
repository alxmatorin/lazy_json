package org.example.json.bench;

import org.example.json.bench.Op.Find;
import org.example.json.bench.Op.Replace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

/**
 * Компактный документ ~500 КБ:
 * <pre>
 *   $key3.key4  — объект из 5 строковых полей, в начале
 *   $key5       — большой объект (80 вложенных групп, ~20 КБ), в начале
 *   $key.key5   — большой объект (120 групп, ~30 КБ), на 20 % от начала
 *   $mid.tag    — один маленький тег на 50 %; заменяется объектом из 80 групп (~17 КБ)
 *   $key1.key2  — список из одного значения, в самом конце
 * </pre>
 * Документов и значений для замены по {@value #POOL_SIZE} разных экземпляров одной формы; итерация {@code i}
 * берёт {@code i % n}-й документ и {@code i % n}-е значение, эмулируя поток входных сообщений.
 * Для каждой замены три строки: тот же объект из пула (попадание в кэш энкодера), свежая копия
 * (промах — полная сериализация) и заранее сериализованные байты. Для каждой замены есть вторая строка
 * с тем же пулом, заранее сериализованным в {@code byte[]}.
 */
public final class KeysScenario implements Scenario {

    private static final int TOTAL_BYTES = 500 * 1024;
    private static final double KEY_KEY5_POSITION = 0.20;
    private static final double MID_POSITION = 0.50;
    /** {@code "xxx…",} — 100 символов, кавычки и запятая. */
    private static final int FILLER_ITEM_BYTES = 103;

    private static final int POOL_SIZE = 16;

    private final Object[] key4Pool = pool(i -> record5("new" + i));
    private final Object[] key5Pool = pool(i -> bigElement(80, 1000 + i));
    private final Object[] keyKey5Pool = pool(i -> bigElement(120, 2000 + i));
    private final Object[] midPool = pool(i -> bigElement(80, 3000 + i));
    private final Object[] key4Bytes = serialized(key4Pool);
    private final Object[] key5Bytes = serialized(key5Pool);
    private final Object[] keyKey5Bytes = serialized(keyKey5Pool);
    private final Object[] midBytes = serialized(midPool);

    @Override
    public String name() {
        return "keys";
    }

    @Override
    public List<byte[]> documents() {
        return IntStream.range(0, POOL_SIZE).mapToObj(KeysScenario::document).toList();
    }

    private static byte[] document(int seed) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("key3", linked("key4", record5("old" + seed), "extra", record5("extra")));
        root.put("key5", bigElement(80, 100 * seed + 1));
        root.put("filler1", fillerUpTo(root, (int) (TOTAL_BYTES * KEY_KEY5_POSITION)));
        root.put("key", linked("key5", bigElement(120, 100 * seed + 2)));
        root.put("filler2", fillerUpTo(root, (int) (TOTAL_BYTES * MID_POSITION)));
        root.put("mid", linked("tag", "small-" + seed));
        root.put("filler3", fillerUpTo(root, TOTAL_BYTES));
        root.put("key1", linked("key2", List.of(42 + seed)));
        return Json.compact(root);
    }

    @Override
    public List<Op> ops() {
        return List.of(
                new Find("find $key1.key2 (list of 1, at end)", "$key1.key2"),
                new Find("find $<key1.key2 (hint: scan from end)", "$<key1.key2"),
                new Op.FindMany("find $key1.key2, then $mid.tag, $key.key5 (same instance)",
                        List.of("$key1.key2", "$mid.tag", "$key.key5")),
                new Op.FindMany("find $mid.tag, then $key1.key2 (resumes from 50%)",
                        List.of("$mid.tag", "$key1.key2")),
                new Replace("edit $<key1.key2 + $<key1.key3 (batch at tail)", List.of(
                        new Op.Replacement("$<key1.key2", i -> 7), new Op.Replacement("$<key1.key3", i -> "new"))),
                Replace.of("replace $key3.key4 (5 strings) Map, cache hit", "$key3.key4", cycling(key4Pool)),
                Replace.of("replace $key3.key4 (5 strings) Map, fresh object", "$key3.key4", fresh(key4Pool)),
                Replace.of("replace $key3.key4 (5 strings) prepared bytes", "$key3.key4", cycling(key4Bytes)),
                Replace.of("replace $key5 (80 groups) Map, cache hit", "$key5", cycling(key5Pool)),
                Replace.of("replace $key5 (80 groups) Map, fresh object", "$key5", fresh(key5Pool)),
                Replace.of("replace $key5 (80 groups) prepared bytes", "$key5", cycling(key5Bytes)),
                Replace.of("replace $key.key5 (120 groups) Map, cache hit", "$key.key5", cycling(keyKey5Pool)),
                Replace.of("replace $key.key5 (120 groups) Map, fresh object", "$key.key5", fresh(keyKey5Pool)),
                Replace.of("replace $key.key5 (120 groups) prepared bytes", "$key.key5", cycling(keyKey5Bytes)),
                Replace.of("replace $mid.tag -> 80 groups (at 50%) Map, cache hit", "$mid.tag", cycling(midPool)),
                Replace.of("replace $mid.tag -> 80 groups (at 50%) Map, fresh object", "$mid.tag", fresh(midPool)),
                Replace.of("replace $mid.tag -> 80 groups (at 50%) prepared bytes", "$mid.tag", cycling(midBytes))
        );
    }

    private static Object[] pool(IntFunction<Object> factory) {
        return IntStream.range(0, POOL_SIZE).mapToObj(factory).toArray();
    }

    private static Object[] serialized(Object[] pool) {
        return java.util.Arrays.stream(pool).map(Json::compact).toArray();
    }

    /** Тот же объект из пула — второй раз попадает в кэш энкодера. */
    private static IntFunction<Object> cycling(Object[] pool) {
        return i -> pool[i % pool.length];
    }

    /** Новый экземпляр на каждой итерации (поверхностная копия) — кэш по идентичности всегда промахивается. */
    @SuppressWarnings("unchecked")
    private static IntFunction<Object> fresh(Object[] pool) {
        return i -> new LinkedHashMap<>((Map<String, Object>) pool[i % pool.length]);
    }

    private static Map<String, Object> record5(String prefix) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            record.put("field" + i, prefix + "-value-" + i);
        }
        return record;
    }

    private static Map<String, Object> bigElement(int groupCount, int seed) {
        List<Object> groups = new ArrayList<>();
        Map<String, Object> element = linked("id", seed, "groups", groups);
        for (int i = 0; i < groupCount; i++) {
            groups.add(group(seed, i));
        }
        return element;
    }

    private static Map<String, Object> group(int seed, int index) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", seed * 100_000 + index);
        group.put("name", "group-" + seed + "-" + index);
        group.put("active", index % 2 == 0);
        group.put("attrs", linked("color", "c" + index, "size", index * 7 % 50, "unit", "kg", "note", "n" + seed));
        group.put("values", List.of(index, index + 1, index + 2, index + 3, index + 4));
        group.put("tags", List.of("t" + index, "s" + seed, "common"));
        group.put("nested", linked("child", linked("x", index * 1.5, "y", -index), "leaf", "leaf-" + index));
        return group;
    }

    private static List<String> fillerUpTo(Map<String, Object> root, int targetBytes) {
        int missing = targetBytes - Json.compact(root).length;
        List<String> filler = new ArrayList<>();
        for (int i = 0; i < missing / FILLER_ITEM_BYTES; i++) {
            filler.add("x".repeat(100));
        }
        return filler;
    }

    private static Map<String, Object> linked(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
