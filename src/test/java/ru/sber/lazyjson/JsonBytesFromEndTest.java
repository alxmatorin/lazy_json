package ru.sber.lazyjson;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Хинт {@code $<key}: корневой объект обходится с конца. */
class JsonBytesFromEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void lastKeyIsFoundWithoutTouchingTheRest() {
        byte[] doc = doc("{\"big\":\"" + "x".repeat(1000) + "\",\"key1\":{\"key2\":[42]}}");
        assertEquals("[42]", find(doc, "$<key1.key2"));
        assertEquals("{\"key2\":[42]}", find(doc, "$<key1"));
    }

    @Test
    void keysInTheMiddleAndAtTheStart() {
        byte[] doc = doc("{\"first\":1,\"mid\":{\"a\":\"b\"},\"last\":[1,2]}");
        assertEquals("{\"a\":\"b\"}", find(doc, "$<mid"));
        assertEquals("\"b\"", find(doc, "$<mid.a"));
        assertEquals("1", find(doc, "$<first"));
        assertNull(LazyJson.of(doc).find(JsonPath.compile("$<none")));
    }

    @Test
    void valuesOfAnyTypeAtTheEnd() {
        byte[] doc = doc("{\"z\":null,\"s\":\"str\",\"n\":-1.5e3,\"t\":true,\"o\":{\"x\":[{}]},\"a\":[[],{}]}");
        assertEquals("null", find(doc, "$<z"));
        assertEquals("\"str\"", find(doc, "$<s"));
        assertEquals("-1.5e3", find(doc, "$<n"));
        assertEquals("true", find(doc, "$<t"));
        assertEquals("{\"x\":[{}]}", find(doc, "$<o"));
        assertEquals("[[],{}]", find(doc, "$<a"));
    }

    @Test
    void escapedQuotesAndBackslashesScannedBackwards() {
        byte[] doc = doc("{\"id\":7,\"a\":\"\\\\\",\"b\":\"x\\\"y\",\"c\":\"\\\\\\\"\",\"d\":\"\\\"\\\\\",\"e\":\"\\\\\\\\\"}");
        assertEquals("\"\\\\\"", find(doc, "$<a"));
        assertEquals("\"x\\\"y\"", find(doc, "$<b"));
        assertEquals("\"\\\\\\\"\"", find(doc, "$<c"));
        assertEquals("\"\\\"\\\\\"", find(doc, "$<d"));
        assertEquals("\"\\\\\\\\\"", find(doc, "$<e"));
        assertEquals("7", find(doc, "$<id"));
    }

    @Test
    void bracketsAndKeyTextInsideStringsScannedBackwards() {
        String noise = "\"" + "}]{[\\\"id\\\":9,".repeat(5) + "\"";
        byte[] doc = doc("{\"id\":1,\"o\":{\"k\":[" + noise + ",{\"id\":" + noise + "}]},\"t\":" + noise + "}");
        assertEquals("1", find(doc, "$<id"));
        assertEquals(noise, find(doc, "$<t"));
        assertEquals(noise, find(doc, "$<o.k[1].id"));
    }

    @Test
    void escapedKeysScannedBackwards() {
        byte[] doc = doc("{\"x\":1,\"cl\\u0069ent\":{\"id\":5},\"a\\\"b\":2}");
        assertEquals("{\"id\":5}", find(doc, "$<client"));
        assertEquals("2", find(doc, "$<['a\"b']"));
    }

    @Test
    void prettyPrintedAndTrailingWhitespace() {
        byte[] doc = doc("""
                  {
                    "first" : [ 1 , 2 ] ,
                    "key1" : {
                      "key2" : "v"
                    }
                  }

                """);
        assertEquals("\"v\"", find(doc, "$<key1.key2"));
        assertEquals("[ 1 , 2 ]", find(doc, "$<first"));
    }

    @Test
    void emptyAndNonObjectRoots() {
        assertNull(LazyJson.of(doc("{}")).find(JsonPath.compile("$<a")));
        assertNull(LazyJson.of(doc("{ }")).find(JsonPath.compile("$<a")));
        assertEquals("2", find(doc("[1,2]"), "$<[1]"));
        assertEquals("[1,2]", find(doc("[1,2]"), "$<"));
    }

    @Test
    void wildcardBelowHintedKey() {
        byte[] doc = doc("{\"pad\":\"p\",\"items\":[{\"id\":1},{\"id\":2}]}");
        assertEquals(List.of("1", "2"),
                LazyJson.of(doc).findAll(JsonPath.compile("$<items[*].id")).stream().map(s -> s.text()).toList());
    }

    @Test
    void putThroughHintReplacesAndInserts() {
        assertEquals("{\"a\":1,\"key1\":{\"key2\":9}}", put("{\"a\":1,\"key1\":{\"key2\":[42]}}", "$<key1.key2", "9"));
        assertEquals("{\"new\":true,\"a\":1}", put("{\"a\":1}", "$<new", "true"));
        assertEquals("{\"a\":1,\"key1\":{\"x\":0,\"key2\":2}}", put("{\"a\":1,\"key1\":{\"key2\":2}}", "$<key1.x", "0"));
        assertEquals("{\"new\":1}", put("{}", "$<new", "1"));
    }

    @Test
    void malformedTail() {
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(doc("{\"a\":1} x")).find(JsonPath.compile("$<a")));
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(doc("{\"a\":1")).find(JsonPath.compile("$<a")));
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(doc("{\"a\":\"x}")).find(JsonPath.compile("$<a")));
    }

    @Test
    void backwardAgreesWithForwardOnRandomDocuments() throws Exception {
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            Map<String, Object> root = randomObject(random, 3);
            byte[] doc = round % 2 == 0
                    ? JSON.writeValueAsBytes(root)
                    : JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            for (String key : root.keySet()) {
                String path = "$['" + key + "']";
                assertEquals(LazyJson.of(doc).find(JsonPath.compile(path)), LazyJson.of(doc).find(JsonPath.compile("$<" + path.substring(1))), path);
                if (root.get(key) instanceof Map<?, ?> nested) {
                    for (Object sub : nested.keySet()) {
                        String subPath = path + "['" + sub + "']";
                        assertEquals(LazyJson.of(doc).find(JsonPath.compile(subPath)),
                                LazyJson.of(doc).find(JsonPath.compile("$<" + subPath.substring(1))), subPath);
                    }
                }
            }
            assertNull(LazyJson.of(doc).find(JsonPath.compile("$<absent")));
        }
    }

    private static final String[] PIECES = {
            "a", "\\", "\"", "{", "}", "[", "]", ",", ":", " ", "\n", "ключ", "", "id", "\\\""};

    private static Map<String, Object> randomObject(Random random, int depth) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0, n = random.nextInt(6); i < n; i++) {
            object.put(index(i) + randomText(random).replace("'", ""), randomValue(random, depth));
        }
        return object;
    }

    private static String index(int i) {
        return "k" + i;
    }

    private static Object randomValue(Random random, int depth) {
        return switch (random.nextInt(depth > 0 ? 7 : 5)) {
            case 0 -> randomText(random);
            case 1 -> random.nextInt(1000) - 500;
            case 2 -> random.nextBoolean();
            case 3 -> null;
            case 4 -> random.nextDouble();
            case 5 -> randomObject(random, depth - 1);
            default -> {
                List<Object> list = new ArrayList<>();
                for (int i = 0, n = random.nextInt(4); i < n; i++) {
                    list.add(randomValue(random, depth - 1));
                }
                yield list;
            }
        };
    }

    private static String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        for (int i = 0, n = random.nextInt(30); i < n; i++) {
            text.append(PIECES[random.nextInt(PIECES.length)]);
        }
        return text.toString();
    }

    private static byte[] doc(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String find(byte[] doc, String path) {
        Slice slice = LazyJson.of(doc).find(JsonPath.compile(path));
        return slice == null ? null : slice.text();
    }

    private static String put(String doc, String path, String value) {
        return new String(LazyJson.of(doc(doc)).set(JsonPath.compile(path), doc(value)), StandardCharsets.UTF_8);
    }
}
