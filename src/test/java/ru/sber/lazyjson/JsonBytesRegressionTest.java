package ru.sber.lazyjson;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonBytesRegressionTest {

    @Test
    void rejectsAncestorEditsEvenWhenParentIsMissing() {
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(bytes("{}")).apply(List.of(Edit.raw("$a", "1"), Edit.raw("$a.b", "2"))));
    }

    @Test
    void rejectsArrayPathsAlongsideObjectPaths() {
        for (String doc : List.of("{}", "{\"a\":{}}", "{\"a\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> LazyJson.of(bytes(doc)).apply(List.of(Edit.raw("$a.b", "1"), Edit.raw("$a[0]", "2"))), doc);
        }
    }

    @Test
    void rejectsWildcardPathsAlongsideObjectPaths() {
        for (String doc : List.of("{}", "{\"a\":{}}", "{\"a\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> LazyJson.of(bytes(doc)).apply(List.of(Edit.raw("$a.b", "1"), Edit.raw("$a[*].c", "2"))), doc);
        }
    }

    @Test
    void rejectsOverlappingInsertionsFromWildcardAndIndex() {
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(bytes("{\"a\":[{}]}")).apply(List.of(Edit.raw("$a[*].x", "1"), Edit.raw("$a[0].x", "2"))));
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(bytes("{\"a\":[{}]}")).apply(List.of(Edit.raw("$a[*].x.y", "1"), Edit.raw("$a[0].x.z", "2"))));
    }

    @Test
    void isolatedSurrogateKeysDoNotBecomeQuestionMarks() {
        byte[] doc = bytes("{\"\\ud800\":1,\"?\":2,\"\\udc00\":3,\"\\ud83d\\ude00\":4}");
        for (String prefix : List.of("$", "<$")) {
            assertEquals("2", LazyJson.of(doc).find(JsonPath.compile(prefix + "['?']")).text());
            assertEquals("1", LazyJson.of(doc).find(JsonPath.compile(prefix + "['\ud800']")).text());
            assertEquals("3", LazyJson.of(doc).find(JsonPath.compile(prefix + "['\udc00']")).text());
            assertEquals("4", LazyJson.of(doc).find(JsonPath.compile(prefix + "['😀']")).text());
            assertNull(LazyJson.of(bytes("{\"?\":2}")).find(JsonPath.compile(prefix + "['\ud800']")));
        }
        assertEquals("{\"\\ud800\":1}", new String(LazyJson.of(bytes("{}")).set(JsonPath.compile("$['\ud800']"), bytes("1")), StandardCharsets.UTF_8));
    }

    @Test
    void missingValueDoesNotProduceEmptySlice() {
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(bytes("{\"a\":}")).find(JsonPath.compile("$a")));
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(bytes("[1,]")).find(JsonPath.compile("$[1]")));
    }

    @Test
    void backwardFindAllPreservesDocumentOrderWithRepeatedKeys() {
        byte[] doc = bytes("{\"a\":[1,2],\"a\":[3,4]}");
        assertEquals(List.of("1", "2", "3", "4"), LazyJson.of(doc).findAll(JsonPath.compile("<$a[*]"))
                .stream().map(slice -> slice.text()).toList());
        assertEquals(2, LazyJson.of(doc).findAll(JsonPath.compile("$a")).size());
    }

    @Test
    void wildcardAndIndexCanInsertDifferentKeysIntoSameObject() {
        byte[] result = LazyJson.of(bytes("{\"a\":[{},{}]}")).apply(List.of(Edit.raw("$a[*].x", "1"), Edit.raw("$a[0].y", "2")));
        assertEquals("{\"a\":[{\"x\":1,\"y\":2},{\"x\":1}]}", new String(result, StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
