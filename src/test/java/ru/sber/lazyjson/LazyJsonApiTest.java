package ru.sber.lazyjson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Публичный API {@link LazyJson}: значения любого типа сериализуются, {@code byte[]} и {@code setRaw} — как есть. */
class LazyJsonApiTest {

    record Address(String city, int building) {
    }

    @Test
    void setSerializesAnyObject() {
        LazyJson json = LazyJson.of(doc("{\"a\":1}"));
        assertEquals("{\"a\":\"Ann\"}", text(json.set("$a", "Ann")));
        assertEquals("{\"a\":42}", text(json.set("$a", 42)));
        assertEquals("{\"a\":true}", text(json.set("$a", true)));
        assertEquals("{\"a\":null}", text(json.set("$a", null)));
        assertEquals("{\"a\":[1,2]}", text(json.set("$a", List.of(1, 2))));
        assertEquals("{\"a\":{\"city\":\"Lisbon\",\"building\":7}}", text(json.set("$a", new Address("Lisbon", 7))));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("x", "y");
        assertEquals("{\"a\":{\"x\":\"y\"}}", text(json.set("$a", map)));
    }

    @Test
    void bytesAndRawAreInsertedVerbatim() {
        LazyJson json = LazyJson.of(doc("{\"a\":1}"));
        assertEquals("{\"a\":{\"raw\":true}}", text(json.set("$a", doc("{\"raw\":true}"))));
        assertEquals("{\"a\":[ 1 , 2 ]}", text(json.setRaw("$a", "[ 1 , 2 ]")));
        assertEquals("{\"a\":\"quoted\"}", text(json.setRaw("$a", "\"quoted\"")));
    }

    @Test
    void stringValueIsQuotedNotRaw() {
        assertEquals("{\"a\":\"[1]\"}", text(LazyJson.of(doc("{\"a\":1}")).set("$a", "[1]")));
    }

    @Test
    void editBatchMixesObjectsAndRaw() {
        byte[] result = LazyJson.of(doc("{\"a\":1,\"b\":{}}")).edit()
                .set("$a", "Ann")
                .set("$b.n", 2)
                .setRaw("$b.r", "[]")
                .set("$c", doc("{\"k\":0}"))
                .apply();
        assertEquals("{\"c\":{\"k\":0},\"a\":\"Ann\",\"b\":{\"n\":2,\"r\":[]}}", text(result));
    }

    @Test
    void customEncoder() {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.WRITE_ENUMS_USING_INDEX);
        LazyJson json = LazyJson.of(doc("{\"a\":1}"), JsonEncoder.jackson(mapper));
        assertEquals("{\"a\":1}", text(json.set("$a", Thread.State.RUNNABLE)));
        assertEquals("{\"a\":\"RUNNABLE\"}", text(LazyJson.of(doc("{\"a\":1}")).set("$a", Thread.State.RUNNABLE)));
        assertEquals("{\"a\":\"X\"}", text(LazyJson.of(doc("{\"a\":1}"), v -> doc("\"X\"")).set("$a", "anything")));
    }

    @Test
    void editOfAndRaw() {
        assertEquals("{\"a\":\"s\"}", text(LazyJson.of(doc("{\"a\":1}")).apply(List.of(Edit.of("$a", doc("\"s\""))))));
        assertEquals("{\"a\":s}", text(LazyJson.of(doc("{\"a\":1}")).apply(List.of(Edit.raw("$a", "s")))));
    }

    @Test
    void cachedEncoderKeysObjectsByIdentityAndStringsByEquals() {
        int[] calls = {0};
        JsonEncoder counting = v -> {
            calls[0]++;
            return JsonEncoder.DEFAULT.encode(v);
        };
        JsonEncoder cached = JsonEncoder.cached(counting, 100);
        LazyJson json = LazyJson.of(doc("{\"a\":1}"), cached);
        Map<String, Object> same = Map.of("k", 1);
        assertEquals("{\"a\":{\"k\":1}}", text(json.set("$a", same)));
        assertEquals("{\"a\":{\"k\":1}}", text(json.set("$a", same)));
        assertEquals(1, calls[0], "same instance is a hit");
        json.set("$a", Map.of("k", 1));
        assertEquals(2, calls[0], "equal map but different instance is a miss");
        json.set("$a", new String("text"));
        json.set("$a", new String("text"));
        assertEquals(3, calls[0], "equal strings hit even as different instances");
    }

    @Test
    void hotValuesSurviveStreamOfFreshObjects() {
        int[] calls = {0};
        JsonEncoder cached = JsonEncoder.cached(v -> {
            calls[0]++;
            return JsonEncoder.DEFAULT.encode(v);
        }, 20);
        LazyJson json = LazyJson.of(doc("{\"a\":1}"), cached);
        Map<String, Object> hot = Map.of("hot", true);
        json.set("$a", hot);
        json.set("$a", hot);
        int afterPromotion = calls[0];
        for (int i = 0; i < 1000; i++) {
            json.set("$a", Map.of("fresh", i));
            json.set("$a", hot);
        }
        assertEquals(afterPromotion + 1000, calls[0], "only fresh objects were encoded; hot stayed cached");
    }

    @Test
    void defaultEncoderCachesByIdentity() {
        Map<String, Object> value = Map.of("k", List.of(1, 2, 3));
        LazyJson json = LazyJson.of(doc("{\"a\":1}"));
        byte[] first = json.set("$a", value);
        byte[] second = json.set("$a", value);
        assertEquals(text(first), text(second));
    }

    @Test
    void unserializableValueFails() {
        assertThrows(IllegalArgumentException.class, () -> LazyJson.of(doc("{}")).set("$a", new Object()));
    }

    private static byte[] doc(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
