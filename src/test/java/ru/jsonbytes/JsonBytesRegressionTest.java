package ru.jsonbytes;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonBytesRegressionTest {

    @Test
    void rejectsAncestorEditsEvenWhenParentIsMissing() {
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(bytes("{}")).apply(List.of(Edit.raw("$a", "1"), Edit.raw("$a.b", "2"))));
    }

    @Test
    void rejectsArrayPathsAlongsideObjectPaths() {
        for (String doc : List.of("{}", "{\"a\":{}}", "{\"a\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(bytes(doc)).apply(List.of(Edit.raw("$a.b", "1"), Edit.raw("$a[0]", "2"))), doc);
        }
    }

    @Test
    void rejectsWildcardPathsAlongsideObjectPaths() {
        for (String doc : List.of("{}", "{\"a\":{}}", "{\"a\":[]}")) {
            assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(bytes(doc)).apply(List.of(Edit.raw("$a.b", "1"), Edit.raw("$a[*].c", "2"))), doc);
        }
    }

    @Test
    void rejectsOverlappingInsertionsFromWildcardAndIndex() {
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(bytes("{\"a\":[{}]}")).apply(List.of(Edit.raw("$a[*].x", "1"), Edit.raw("$a[0].x", "2"))));
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(bytes("{\"a\":[{}]}")).apply(List.of(Edit.raw("$a[*].x.y", "1"), Edit.raw("$a[0].x.z", "2"))));
    }

    @Test
    void isolatedSurrogateKeysDoNotBecomeQuestionMarks() {
        byte[] doc = bytes("{\"\\ud800\":1,\"?\":2,\"\\udc00\":3,\"\\ud83d\\ude00\":4}");
        for (String prefix : List.of("$", "$<")) {
            assertEquals("2", JsonBytes.of(doc).find(ElementPath.compile(prefix + "['?']")).text());
            assertEquals("1", JsonBytes.of(doc).find(ElementPath.compile(prefix + "['\ud800']")).text());
            assertEquals("3", JsonBytes.of(doc).find(ElementPath.compile(prefix + "['\udc00']")).text());
            assertEquals("4", JsonBytes.of(doc).find(ElementPath.compile(prefix + "['😀']")).text());
            assertNull(JsonBytes.of(bytes("{\"?\":2}")).find(ElementPath.compile(prefix + "['\ud800']")));
        }
        assertEquals("{\"\\ud800\":1}", new String(JsonBytes.of(bytes("{}")).set(ElementPath.compile("$['\ud800']"), bytes("1")), StandardCharsets.UTF_8));
    }

    @Test
    void missingValueDoesNotProduceEmptySlice() {
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(bytes("{\"a\":}")).find(ElementPath.compile("$a")));
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(bytes("[1,]")).find(ElementPath.compile("$[1]")));
    }

    @Test
    void repeatedRootKeysFirstInScanDirectionWins() {
        // ключи корня считаются уникальными: встретив все нужные, обход останавливается,
        // поэтому при дублях видно первое вхождение по направлению обхода
        byte[] doc = bytes("{\"a\":[1,2],\"a\":[3,4]}");
        assertEquals(List.of("1", "2"), JsonBytes.of(doc).findAll(ElementPath.compile("$a[*]"))
                .stream().map(Slice::text).toList());
        assertEquals(List.of("3", "4"), JsonBytes.of(doc).findAll(ElementPath.compile("$<a[*]"))
                .stream().map(Slice::text).toList());
        assertEquals(1, JsonBytes.of(doc).findAll(ElementPath.compile("$a")).size());
        // во вложенном объекте конец нужен родителю, там дубли по-прежнему видны все
        byte[] nested = bytes("{\"o\":{\"a\":1,\"a\":2}}");
        assertEquals(2, JsonBytes.of(nested).findAll(ElementPath.compile("$o.a")).size());
    }

    @Test
    void wildcardAndIndexCanInsertDifferentKeysIntoSameObject() {
        byte[] result = JsonBytes.of(bytes("{\"a\":[{},{}]}")).apply(List.of(Edit.raw("$a[*].x", "1"), Edit.raw("$a[0].y", "2")));
        assertEquals("{\"a\":[{\"x\":1,\"y\":2},{\"x\":1}]}", new String(result, StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
