package ru.jsonbytes;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonBytesFindTest {

    @Test
    void linearPathFromExample() {
        byte[] doc = doc("{\"key1\":{\"key2\":{\"a\":{\"b\":42}}}}");
        assertEquals("42", find(doc, "$key1.key2.a.b"));
    }

    @Test
    void valuesOfAnyType() {
        byte[] doc = doc("""
                {"s":"text","n":-1.5e3,"t":true,"f":false,"z":null,
                 "o":{"x":[1,2]},"a":[{"y":"}"},"]"],"last":7}""");
        assertEquals("\"text\"", find(doc, "$s"));
        assertEquals("-1.5e3", find(doc, "$n"));
        assertEquals("true", find(doc, "$t"));
        assertEquals("false", find(doc, "$f"));
        assertEquals("null", find(doc, "$z"));
        assertEquals("{\"x\":[1,2]}", find(doc, "$o"));
        assertEquals("[{\"y\":\"}\"},\"]\"]", find(doc, "$a"));
        assertEquals("7", find(doc, "$last"));
    }

    @Test
    void rootIsWholeValue() {
        assertEquals("{\"a\":1}", find(doc("  {\"a\":1}  "), "$"));
        assertEquals("5", find(doc("5"), "$"));
        assertEquals("[1]", find(doc("[1]"), "$"));
    }

    @Test
    void keyTextInsideStringValueIsNotAKey() {
        byte[] doc = doc("{\"note\":\"\\\"id\\\": 999\",\"id\":1}");
        assertEquals("1", find(doc, "$id"));
    }

    @Test
    void keyTextInsideNestedStringValueIsNotAKey() {
        byte[] doc = doc("{\"note\":\"id\",\"client\":{\"note\":\"{\\\"id\\\":7}\",\"id\":2}}");
        assertEquals("2", find(doc, "$client.id"));
    }

    @Test
    void keyTextInsideArrayOfStringsIsNotAKey() {
        byte[] doc = doc("{\"tags\":[\"id\",\"client\"],\"id\":4}");
        assertEquals("4", find(doc, "$id"));
    }

    @Test
    void keyUnderWrongParentIsNotMatched() {
        byte[] doc = doc("{\"client\":{\"meta\":{\"id\":\"decoy\"}},\"id\":1}");
        assertNull(JsonBytes.of(doc).find(ElementPath.compile("$client.id")));
        assertEquals("1", find(doc, "$id"));
        assertEquals("\"decoy\"", find(doc, "$client.meta.id"));
    }

    @Test
    void stringValueEndingWithEscapedBackslash() {
        byte[] doc = doc("{\"s\":\"x\\\\\",\"id\":3}");
        assertEquals("3", find(doc, "$id"));
        assertEquals("\"x\\\\\"", find(doc, "$s"));
    }

    @Test
    void stringValueWithEscapedQuotesAndBraces() {
        byte[] doc = doc("{\"s\":\"a\\\"{\\\"[\\\\\\\"\",\"id\":3}");
        assertEquals("3", find(doc, "$id"));
    }

    @Test
    void longStringsWithEscapesAtEveryOffset() {
        for (int prefix = 0; prefix < 20; prefix++) {
            String pad = "x".repeat(prefix);
            String json = "{\"s\":\"" + pad + "\\\"" + pad + "\\\\\",\"id\":3,\"t\":\"" + pad + "\"}";
            byte[] doc = doc(json);
            assertEquals("3", find(doc, "$id"), json);
            assertEquals("\"" + pad + "\"", find(doc, "$t"), json);
        }
    }

    @Test
    void containersWithBracketsInsideLongStrings() {
        String noise = "\"" + "{[}]".repeat(10) + "\"";
        byte[] doc = doc("{\"a\":{\"b\":[" + noise + ",{\"c\":" + noise + "}]},\"id\":8}");
        assertEquals("8", find(doc, "$id"));
        assertEquals(noise, find(doc, "$a.b[1].c"));
    }

    @Test
    void escapedKeyInDocumentMatchesPlainName() {
        byte[] doc = doc("{\"cl\\u0069ent\":{\"id\":5}}");
        assertEquals("5", find(doc, "$client.id"));
    }

    @Test
    void keyWithQuoteAndUnicode() {
        byte[] doc = doc("{\"a\\\"b\":1,\"ключ\":2,\"\\u043a\\u043b\\u044e\\u0447x\":3}");
        assertEquals("1", find(doc, "$['a\"b']"));
        assertEquals("2", find(doc, "$ключ"));
        assertEquals("3", find(doc, "$ключx"));
    }

    @Test
    void prettyPrintedDocument() {
        byte[] doc = doc("""
                {
                  "client" : {
                    "id" : 10 ,
                    "name" : "x"
                  } ,
                  "id" : 11
                }
                """);
        assertEquals("10", find(doc, "$client.id"));
        assertEquals("11", find(doc, "$id"));
        assertEquals("{\n    \"id\" : 10 ,\n    \"name\" : \"x\"\n  }", find(doc, "$client"));
    }

    @Test
    void arrayIndex() {
        byte[] doc = doc("{\"clients\":[{\"id\":1},{\"id\":2},{\"id\":3}],\"m\":[[1,2],[3,4]]}");
        assertEquals("2", find(doc, "$clients[1].id"));
        assertEquals("{\"id\":3}", find(doc, "$clients[2]"));
        assertEquals("4", find(doc, "$m[1][1]"));
        assertNull(JsonBytes.of(doc).find(ElementPath.compile("$clients[3].id")));
    }

    @Test
    void wildcardCollectsIdsOfAllClients() {
        byte[] doc = doc("{\"clients\":[{\"id\":1,\"x\":\"id\"},{\"name\":\"no id\"},{\"id\":\"3\"},{\"id\":{\"v\":4}}]}");
        assertEquals(List.of("1", "\"3\"", "{\"v\":4}"), findAll(doc, "$clients[*].id"));
    }

    @Test
    void wildcardOverNestedArrays() {
        byte[] doc = doc("{\"a\":[{\"b\":[{\"c\":1},{\"c\":2}]},{\"b\":[{\"c\":3}]}]}");
        assertEquals(List.of("1", "2", "3"), findAll(doc, "$a[*].b[*].c"));
        assertEquals("1", find(doc, "$a[*].b[*].c"));
    }

    @Test
    void findAllWithoutWildcardReturnsSingle() {
        assertEquals(List.of("1"), findAll(doc("{\"a\":1}"), "$a"));
        assertEquals(List.of(), findAll(doc("{\"a\":1}"), "$b"));
    }

    @Test
    void missingOrMistypedPathsReturnNull() {
        byte[] doc = doc("{\"a\":5,\"b\":[1],\"c\":{}}");
        assertNull(JsonBytes.of(doc).find(ElementPath.compile("$x")));
        assertNull(JsonBytes.of(doc).find(ElementPath.compile("$a.b")));
        assertNull(JsonBytes.of(doc).find(ElementPath.compile("$a[0]")));
        assertNull(JsonBytes.of(doc).find(ElementPath.compile("$b.x")));
        assertNull(JsonBytes.of(doc).find(ElementPath.compile("$b[1]")));
        assertNull(JsonBytes.of(doc).find(ElementPath.compile("$c.x")));
    }

    @Test
    void sliceGivesOffsetAndCopy() {
        byte[] doc = doc("{\"a\":\"xy\"}");
        Slice slice = JsonBytes.of(doc).find(ElementPath.compile("$a"));
        assertEquals(5, slice.offset());
        assertEquals(4, slice.length());
        assertEquals("\"xy\"", new String(slice.copy(), StandardCharsets.UTF_8));
    }

    @Test
    void malformedDocumentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(doc("{\"a\":\"unterminated")).find(ElementPath.compile("$b")));
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(doc("{\"a\":1")).find(ElementPath.compile("$b")));
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(doc("{a:1}")).find(ElementPath.compile("$b")));
        assertThrows(IllegalArgumentException.class, () -> JsonBytes.of(doc("   ")).find(ElementPath.compile("$")));
    }

    private static byte[] doc(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String find(byte[] doc, String path) {
        Slice slice = JsonBytes.of(doc).find(ElementPath.compile(path));
        return slice == null ? null : slice.text();
    }

    private static List<String> findAll(byte[] doc, String path) {
        return JsonBytes.of(doc).findAll(ElementPath.compile(path)).stream().map(s -> s.text()).toList();
    }
}
