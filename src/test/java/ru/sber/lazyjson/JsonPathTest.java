package ru.sber.lazyjson;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPathTest {

    @Test
    void keysAfterDollarWithoutDot() {
        assertEquals(
                List.of(key("key1"), key("key2"), key("a"), key("b")),
                JsonPath.compile("$key1.key2.a.b").segments());
    }

    @Test
    void dotAfterDollarIsAlsoAccepted() {
        assertEquals(List.of(key("a"), key("b")), JsonPath.compile("$.a.b").segments());
    }

    @Test
    void indexAndWildcard() {
        assertEquals(
                List.of(key("items"), new JsonPath.Index(3), key("id")),
                JsonPath.compile("$items[3].id").segments());
        assertEquals(
                List.of(key("items"), new JsonPath.Wildcard(), key("id")),
                JsonPath.compile("$items[*].id").segments());
        assertEquals(List.of(new JsonPath.Index(0), new JsonPath.Index(1)), JsonPath.compile("$[0][1]").segments());
    }

    @Test
    void quotedKeyMayContainDots() {
        assertEquals(List.of(key("a.b"), key("c")), JsonPath.compile("$['a.b'].c").segments());
    }

    @Test
    void rootAlone() {
        assertTrue(JsonPath.compile("$").segments().isEmpty());
    }

    @Test
    void hasWildcard() {
        assertTrue(JsonPath.compile("$a[*].b").hasWildcard());
        assertFalse(JsonPath.compile("$a[0].b").hasWildcard());
    }

    @Test
    void rejectsPathWithoutDollar() {
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("key1.key2"));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile(""));
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("$a..b"));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("$a["));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("$a[x]"));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("$a[-1]"));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("$a[+1]"));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("$a[01]"));
        assertThrows(IllegalArgumentException.class, () -> JsonPath.compile("$a[]"));
        assertEquals(List.of(new JsonPath.Index(0)), JsonPath.compile("$[0]").segments());
    }

    @Test
    void compileIsCachedByText() {
        assertSame(JsonPath.compile("$cached.a.b"), JsonPath.compile("$cached.a.b"));
        assertNotSame(JsonPath.compile("$cached.a.b"), JsonPath.compile("$cached.a.c"));
    }

    private static JsonPath.Key key(String name) {
        return new JsonPath.Key(name);
    }
}
