package ru.sber.lazyjson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.json.bench.KeysScenario;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Test
    void scenarioHasTenRootFieldsAndNestedPayload() throws Exception {
        for (byte[] doc : new KeysScenario().documents()) {
            JsonNode root = JSON.readTree(doc);
            assertEquals(10, root.size());
            assertTrue(doc.length > 490 * 1024 && doc.length < 510 * 1024);
            assertTrue(root.get("key5").get("groups").size() >= 80);
            assertTrue(root.get("filler3").isArray());
            assertTrue(root.get("filler3").size() > 1000);
        }
    }

    @Test
    void indexedLookupsSurviveGrowthCollisionsAndBothDirections() {
        StringBuilder text = new StringBuilder("{");
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            if (i > 0) text.append(',');
            String key = (i % 2 == 0 ? "Aa" : "BB") + i / 2;
            keys.add(key);
            text.append('"').append(i % 3 == 0 ? key.replace("A", "\\u0041").replace("B", "\\u0042") : key)
                    .append("\":").append(i);
        }
        LazyJson json = LazyJson.of(bytes(text.append('}').toString()));
        for (int i = 0; i < keys.size() / 2; i++) {
            assertEquals(Integer.toString(i), json.find("$" + keys.get(i)).text());
            int back = keys.size() - i - 1;
            assertEquals(Integer.toString(back), json.find("$<" + keys.get(back)).text());
        }
        Collections.shuffle(keys, new Random(71));
        for (String key : keys) {
            int value = Integer.parseInt(key.substring(2)) * 2 + (key.startsWith("BB") ? 1 : 0);
            assertEquals(Integer.toString(value), json.find("$" + key).text());
        }
        assertNull(json.find("$absent"));
    }

    @Test
    void indexedUnicodeNamesStayDistinctAfterHashPromotion() {
        StringBuilder doc = new StringBuilder("{\"\\ud800\":1,\"?\":2,\"\\ud83d\\ude00\":3,\"ключ\":4");
        for (int i = 0; i < 40; i++) doc.append(",\"k").append(i).append("\":0");
        LazyJson json = LazyJson.of(bytes(doc.append('}').toString()));
        assertNull(json.find("$absent"));
        assertEquals("1", json.find(JsonPath.of("\ud800")).text());
        assertEquals("2", json.find(JsonPath.of("?")).text());
        assertEquals("3", json.find(JsonPath.of("😀")).text());
        assertEquals("4", json.find(JsonPath.of("ключ")).text());
    }

    @Test
    void reusedBatchRebuildsAfterAppendingAndKeepsLastValue() {
        LazyJson json = LazyJson.of(bytes("{\"a\":1,\"b\":2}"));
        LazyJson.Edits edits = json.edit().setRaw("$a", "3").setRaw("$b", "4");
        assertEquals("{\"a\":3,\"b\":4}", text(edits.apply()));
        assertEquals("{\"a\":3,\"b\":4}", text(edits.apply()));
        edits.setRaw("$a", "5");
        assertEquals("{\"a\":5,\"b\":4}", text(edits.apply()));
        edits.setRaw("$a.child", "6");
        assertThrows(IllegalArgumentException.class, edits::apply);
        assertEquals("{\"a\":1,\"b\":2}", text(json.bytes()));
    }

    @Test
    void preparedBatchUsesTargetDocumentOffsets() throws Exception {
        LazyJson first = LazyJson.of(bytes("{\"a\":1,\"b\":{}}"));
        LazyJson second = LazyJson.of(bytes("{\"padding\":[1,2,3],\"b\":{\"x\":0},\"a\":999}"));
        LazyJson.Edits edits = first.edit().setRaw("$a", "3").setRaw("$b.x", "4");
        edits.apply();
        second.find("$a");
        assertEquals(JSON.readTree("{\"padding\":[1,2,3],\"b\":{\"x\":4},\"a\":3}"),
                JSON.readTree(edits.applyTo(second)));
        assertEquals(JSON.readTree("{\"a\":3,\"b\":{\"x\":4}}"), JSON.readTree(edits.apply()));
    }

    @Test
    void largeInsertionsAndReplacementsProduceExactlyOneValidDocument() throws Exception {
        byte[] large = bytes("\"" + "x".repeat(500_000) + "\"");
        LazyJson json = LazyJson.of(bytes("{\"items\":[{},{}],\"a\":0}"));
        byte[] result = json.edit().set("$items[*].nested.value", large)
                .set("$new", large).setRaw("$a", "42").apply();
        JsonNode tree = JSON.readTree(result);
        assertEquals(500_000, tree.get("new").asText().length());
        for (JsonNode item : tree.get("items")) {
            assertEquals(tree.get("new"), item.get("nested").get("value"));
        }
        assertEquals(42, tree.get("a").asInt());
        assertEquals("{\"items\":[{},{}],\"a\":0}", text(json.bytes()));
        assertEquals('"', large[0]);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
