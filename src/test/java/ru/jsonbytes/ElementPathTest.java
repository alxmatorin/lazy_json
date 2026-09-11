package ru.jsonbytes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementPathTest {

    @Test
    void keysAfterDollarWithoutDot() {
        assertEquals(
                List.of(key("key1"), key("key2"), key("a"), key("b")),
                ElementPath.compile("$key1.key2.a.b").segments());
    }

    @Test
    void dotAfterDollarIsAlsoAccepted() {
        assertEquals(List.of(key("a"), key("b")), ElementPath.compile("$.a.b").segments());
    }

    @Test
    void indexAndWildcard() {
        assertEquals(
                List.of(key("items"), new ElementPath.Index(3), key("id")),
                ElementPath.compile("$items[3].id").segments());
        assertEquals(
                List.of(key("items"), new ElementPath.Wildcard(), key("id")),
                ElementPath.compile("$items[*].id").segments());
        assertEquals(List.of(new ElementPath.Index(0), new ElementPath.Index(1)), ElementPath.compile("$[0][1]").segments());
    }

    @Test
    void quotedKeyMayContainDots() {
        assertEquals(List.of(key("a.b"), key("c")), ElementPath.compile("$['a.b'].c").segments());
    }

    @Test
    void rootAlone() {
        assertTrue(ElementPath.compile("$").segments().isEmpty());
    }

    @Test
    void hasWildcard() {
        assertTrue(ElementPath.compile("$a[*].b").hasWildcard());
        assertFalse(ElementPath.compile("$a[0].b").hasWildcard());
    }

    @Test
    void rejectsPathWithoutDollar() {
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile("key1.key2"));
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile(""));
    }

    @Test
    void rejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile("$a..b"));
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile("$a["));
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile("$a[x]"));
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile("$a[-1]"));
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile("$a[+1]"));
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile("$a[01]"));
        assertThrows(IllegalArgumentException.class, () -> ElementPath.compile("$a[]"));
        assertEquals(List.of(new ElementPath.Index(0)), ElementPath.compile("$[0]").segments());
    }

    @Test
    void compileIsCachedByText() {
        assertSame(ElementPath.compile("$cached.a.b"), ElementPath.compile("$cached.a.b"));
        assertNotSame(ElementPath.compile("$cached.a.b"), ElementPath.compile("$cached.a.c"));
    }

    private static ElementPath.Key key(String name) {
        return new ElementPath.Key(name);
    }
}
