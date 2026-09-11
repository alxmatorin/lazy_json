package ru.sber.jsonbytes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonBytesPutTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void replaceScalarWithLongerAndShorterValue() {
        assertEquals("{\"a\":12345,\"b\":2}", put("{\"a\":1,\"b\":2}", "$a", "12345"));
        assertEquals("{\"a\":1,\"b\":0}", put("{\"a\":1,\"b\":123456}", "$b", "0"));
    }

    @Test
    void replaceValueOfAnyTypeWithAnyType() {
        String doc = "{\"o\":{\"x\":[1,2]},\"s\":\"str\",\"n\":null}";
        assertEquals("{\"o\":7,\"s\":\"str\",\"n\":null}", put(doc, "$o", "7"));
        assertEquals("{\"o\":{\"x\":[1,2]},\"s\":{\"k\":[]},\"n\":null}", put(doc, "$s", "{\"k\":[]}"));
        assertEquals("{\"o\":{\"x\":[1,2]},\"s\":\"str\",\"n\":\"x\"}", put(doc, "$n", "\"x\""));
    }

    @Test
    void replaceDeepAndInsideArray() {
        String doc = "{\"key1\":{\"key2\":{\"a\":{\"b\":1}}},\"arr\":[{\"id\":1},{\"id\":2}]}";
        assertEquals("{\"key1\":{\"key2\":{\"a\":{\"b\":\"new\"}}},\"arr\":[{\"id\":1},{\"id\":2}]}",
                put(doc, "$key1.key2.a.b", "\"new\""));
        assertEquals("{\"key1\":{\"key2\":{\"a\":{\"b\":1}}},\"arr\":[{\"id\":1},{\"id\":20}]}",
                put(doc, "$arr[1].id", "20"));
    }

    @Test
    void replaceRoot() {
        assertEquals("  [1]  ", put("  {\"a\":1}  ", "$", "[1]"));
    }

    @Test
    void keyTextInsideStringValueIsNotReplaced() {
        assertEquals("{\"note\":\"\\\"id\\\": 999\",\"id\":2}", put("{\"note\":\"\\\"id\\\": 999\",\"id\":1}", "$id", "2"));
    }

    @Test
    void insertMissingKeyIntoNonEmptyObject() {
        assertEquals("{\"new\":true,\"a\":1}", put("{\"a\":1}", "$new", "true"));
        assertEquals("{\"a\":{\"c\":3,\"b\":2}}", put("{\"a\":{\"b\":2}}", "$a.c", "3"));
    }

    @Test
    void insertMissingKeyIntoEmptyObject() {
        assertEquals("{\"new\":1}", put("{}", "$new", "1"));
        assertEquals("{\"new\":1 }", put("{ }", "$new", "1"));
        assertEquals("{\"a\":{\"b\":1}}", put("{\"a\":{}}", "$a.b", "1"));
    }

    @Test
    void insertCreatesMissingIntermediateObjects() {
        assertEquals("{\"a\":{\"b\":{\"c\":1},\"x\":0}}", put("{\"a\":{\"x\":0}}", "$a.b.c", "1"));
        assertEquals("{\"a\":{\"b\":{\"c\":1}},\"x\":0}", put("{\"x\":0}", "$a.b.c", "1"));
    }

    @Test
    void insertedKeyIsEscaped() {
        assertEquals("{\"a\\\"b\":1}", put("{}", "$['a\"b']", "1"));
    }

    @Test
    void wildcardPutSetsFlagOnEveryElement() {
        String doc = "{\"items\":[{\"id\":1},{\"id\":2,\"flag\":false},{}]}";
        assertEquals("{\"items\":[{\"flag\":true,\"id\":1},{\"id\":2,\"flag\":true},{\"flag\":true}]}",
                put(doc, "$items[*].flag", "true"));
    }

    @Test
    void batchAppliesAllEditsAtOnce() {
        byte[] doc = doc("{\"a\":1,\"b\":{\"c\":2},\"d\":[1,2,3]}");
        byte[] result = JsonBytes.of(doc).apply(List.of(
                Edit.raw("$a", "10"),
                Edit.raw("$b.c", "20"),
                Edit.raw("$b.x", "\"new\""),
                Edit.raw("$b.y", "null"),
                Edit.raw("$d[2]", "30"),
                Edit.raw("$z.q", "[]")));
        assertEquals("{\"z\":{\"q\":[]},\"a\":10,\"b\":{\"x\":\"new\",\"y\":null,\"c\":20},\"d\":[1,2,30]}", text(result));
    }

    @Test
    void batchWithSamePathTwiceLastWins() {
        byte[] result = JsonBytes.of(doc("{\"a\":1}")).apply(List.of(Edit.raw("$a", "2"), Edit.raw("$a", "3")));
        assertEquals("{\"a\":3}", text(result));
    }

    @Test
    void batchRejectsNestedEdits() {
        assertThrows(IllegalArgumentException.class, () ->
                JsonBytes.of(doc("{\"a\":{\"b\":1}}")).apply(List.of(Edit.raw("$a", "1"), Edit.raw("$a.b", "2"))));
    }

    @Test
    void putThroughNonContainerFails() {
        assertThrows(IllegalArgumentException.class, () -> put("{\"a\":5}", "$a.b", "1"));
        assertThrows(IllegalArgumentException.class, () -> put("{\"a\":\"s\"}", "$a[0]", "1"));
        assertThrows(IllegalArgumentException.class, () -> put("{\"a\":{}}", "$a[0]", "1"));
    }

    @Test
    void rootScanStopsOnceAllBatchKeysAreSeen() {
        // область корня после последнего нужного ключа не читается — иначе мусор в ней был бы ошибкой
        byte[] forward = doc("{\"a\":1,\"b\":{\"c\":2},\"tail\":[1,,}");
        assertEquals("{\"a\":10,\"b\":{\"x\":0,\"c\":20},\"tail\":[1,,}",
                text(JsonBytes.of(forward).edit().setRaw("$a", "10").setRaw("$b.c", "20").setRaw("$b.x", "0").apply()));
        byte[] backward = doc("{\"junk\":[1,,,\"a\":1,\"b\":{\"c\":2}}");
        assertEquals("{\"junk\":[1,,,\"a\":10,\"b\":{\"x\":0,\"c\":20}}",
                text(JsonBytes.of(backward).edit().setRaw("$<a", "10").setRaw("$<b.c", "20").setRaw("$b.x", "0").apply()));
        assertEquals(List.of("2"), JsonBytes.of(forward).findAll("$b.c").stream().map(Slice::text).toList());
    }

    @Test
    void outOfRangeIndexIsReportedAsSuch() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> put("{\"a\":[1]}", "$a[5]", "1"));
        assertTrue(e.getMessage().contains("beyond the array of 1 elements"), e.getMessage());
    }

    @Test
    void putCannotCreateArrayElements() {
        assertThrows(IllegalArgumentException.class, () -> put("{\"a\":[1]}", "$a[5]", "1"));
        assertThrows(IllegalArgumentException.class, () -> put("{}", "$a[0]", "1"));
    }

    @Test
    void resultStaysValidJson() throws Exception {
        String doc = "{\"a\":1,\"b\":{},\"c\":[{\"x\":1},{}]}";
        byte[] result = JsonBytes.of(doc(doc)).apply(List.of(
                Edit.raw("$b.n", "1"), Edit.raw("$b.m", "2"), Edit.raw("$c[*].y", "\"v\""), Edit.raw("$d.e", "3")));
        assertEquals(JSON.readTree("{\"d\":{\"e\":3},\"a\":1,\"b\":{\"n\":1,\"m\":2},\"c\":[{\"y\":\"v\",\"x\":1},{\"y\":\"v\"}]}"),
                JSON.readTree(result));
    }

    @Test
    void inputIsNotMutated() {
        byte[] doc = doc("{\"a\":1}");
        byte[] result = JsonBytes.of(doc).set(ElementPath.compile("$a"), doc("2"));
        assertEquals("{\"a\":1}", text(doc));
        assertEquals("{\"a\":2}", text(result));
        assertTrue(doc != result);
    }

    private static String put(String doc, String path, String value) {
        return text(JsonBytes.of(doc(doc)).set(ElementPath.compile(path), doc(value)));
    }

    private static byte[] doc(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
