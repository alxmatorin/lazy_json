package ru.sber.lazyjson;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Индекс членов корня: повторные операции на одном экземпляре не сканируют уже пройденное. */
class RootIndexTest {

    // мусор в середине — маркер «сюда сканер не заходил»: зайдёт с любой стороны — упадёт
    private static final String DOC = "{\"a\":1,\"b\":{\"c\":2},\"x\":[1,,,\"d\":\"dd\",\"e\":{\"f\":3}}";

    @Test
    void knownKeysAreServedFromIndexWithoutRescan() {
        LazyJson json = LazyJson.of(doc(DOC));
        assertEquals("2", json.find("$b.c").text());          // просканированы a, b; фронт перед x
        assertEquals("1", json.find("$a").text());            // из индекса
        assertEquals("{\"c\":2}", json.find("$b").text());    // из индекса, конец известен без пересканирования
        assertEquals("{\"a\":10,\"b\":{\"c\":20},\"x\":[1,,,\"d\":\"dd\",\"e\":{\"f\":3}}",
                text(json.edit().setRaw("$a", "10").setRaw("$b.c", "20").apply()));
    }

    @Test
    void forwardAndBackwardFrontsCoverTheRootTogether() {
        LazyJson json = LazyJson.of(doc("{\"a\":1,\"b\":2,\"c\":3}"));
        assertEquals("1", json.find("$a").text());            // вперёд: a
        assertEquals("3", json.find("$<c").text());           // назад: c
        assertEquals("1", json.find("$<a").text());           // из индекса, направление хинта неважно
        assertEquals("2", json.find("$b").text());            // единственный неизвестный — дочитан, фронты сошлись
        assertNull(json.find("$d"));                          // корень известен целиком: без скана
        assertEquals("{\"d\":4,\"a\":1,\"b\":2,\"c\":3}", text(json.set("$d", 4)));
    }

    @Test
    void hintedLookupSkipsGarbageAtTheFront() {
        LazyJson json = LazyJson.of(doc(DOC));
        assertEquals("{\"f\":3}", json.find("$<e").text());
        assertEquals("\"dd\"", json.find("$<d").text());       // назад: следующий член
        assertEquals("3", json.find("$e.f").text());          // из индекса
    }

    @Test
    void unknownKeyResumesFromFrontier() {
        byte[] doc = doc("{\"a\":1,\"b\":2,\"c\":3,\"d\":[4,,}");
        LazyJson json = LazyJson.of(doc);
        assertEquals("1", json.find("$a").text());
        assertEquals("2", json.find("$b").text());            // продолжил с фронта, не с '{'
        assertEquals("3", json.find("$c").text());
        assertEquals("{\"a\":1,\"b\":2,\"c\":33,\"d\":[4,,}", text(json.set("$c", 33)));
    }

    @Test
    void absentKeyIsKnownOnceRootIsComplete() {
        byte[] doc = doc("{\"a\":1,\"b\":2}");
        LazyJson json = LazyJson.of(doc);
        assertNull(json.find("$zzz"));                        // полный скан, корень известен целиком
        assertNull(json.find("$yyy"));                        // без скана
        assertEquals("{\"n\":0,\"a\":1,\"b\":2}", text(json.set("$n", 0)));
        assertEquals("{\"a\":1,\"b\":2}", text(json.set("$b", 2)));
        assertEquals("{\"a\":1,\"b\":2}", text(LazyJson.of(doc("{\"a\":1,\"b\":2}")).set("$b", 2)));
    }

    @Test
    void emptyRootAndInsertion() {
        LazyJson json = LazyJson.of(doc("{ }"));
        assertNull(json.find("$a"));
        assertEquals("{\"a\":1 }", text(json.set("$a", 1)));
        assertEquals("{\"a\":1,\"b\":2 }", text(json.edit().set("$a", 1).set("$b", 2).apply()));
    }

    @Test
    void escapedKeysInIndex() {
        LazyJson json = LazyJson.of(doc("{\"cl\\u0069ent\":{\"id\":5},\"z\":0}"));
        assertEquals("0", json.find("$z").text());
        assertEquals("5", json.find("$client.id").text());    // ключ с escape найден в индексе по декодированному имени
        assertEquals("5", json.find(List.of("client", "id")).text());
    }

    private static byte[] doc(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
