package ru.sber.lazyjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.json.generate.JsonPayloadGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сверка с Jackson на сгенерированном документе ~500 КБ. */
class LargeDocumentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static byte[] doc;
    private static JsonNode tree;

    @BeforeAll
    static void generate() throws IOException {
        Path file = Files.createTempFile("lazyjson", ".json");
        new JsonPayloadGenerator(42).generateTo(file, JsonPayloadGenerator.DEFAULT_SIZE_BYTES);
        doc = Files.readAllBytes(file);
        tree = JSON.readTree(doc);
        Files.delete(file);
    }

    @Test
    void findsScalarsAnywhere() {
        int last = tree.get("items").size() - 1;
        assertEquals(tree.get("items").get(0).get("email").toString(), find("$items[0].email"));
        assertEquals(tree.get("items").get(last).get("address").get("zip").toString(), find("$items[" + last + "].address.zip"));
        assertEquals(tree.get("items").get(500).get("history").get(1).get("amount").toString(), find("$items[500].history[1].amount"));
        assertEquals(tree.get("schema").toString(), find("$schema"));
    }

    @Test
    void findsObjectsAndArraysByteExact() throws IOException {
        Slice items = LazyJson.of(doc).find(JsonPath.compile("$items"));
        assertEquals(tree.get("items"), JSON.readTree(items.copy()));
        Slice address = LazyJson.of(doc).find(JsonPath.compile("$items[7].address"));
        assertEquals(tree.get("items").get(7).get("address"), JSON.readTree(address.copy()));
    }

    @Test
    void wildcardCollectsAllIds() {
        List<Slice> ids = LazyJson.of(doc).findAll(JsonPath.compile("$items[*].id"));
        assertEquals(tree.get("items").size(), ids.size());
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(String.valueOf(i + 1), ids.get(i).text());
        }
    }

    @Test
    void wildcardCollectsAllNestedActions() {
        List<Slice> actions = LazyJson.of(doc).findAll(JsonPath.compile("$items[*].history[*].action"));
        int expected = 0;
        for (JsonNode item : tree.get("items")) {
            expected += item.get("history").size();
        }
        assertEquals(expected, actions.size());
        assertTrue(actions.stream().allMatch(a -> a.text().startsWith("\"")));
    }

    @Test
    void editsProduceSameTreeAsJackson() throws IOException {
        byte[] result = LazyJson.of(doc).apply(List.of(
                Edit.raw("$items[3].score", "0.5"),
                Edit.raw("$items[3].address.country", "\"RU\""),
                Edit.raw("$generatedAt", "0"),
                Edit.raw("$meta.version", "2")));

        JsonNode expected = tree.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("items").get(3)).put("score", 0.5);
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.get("items").get(3).get("address")).put("country", "RU");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected).put("generatedAt", 0);
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected).putObject("meta").put("version", 2);

        assertEquals(expected, JSON.readTree(result));
    }

    private static String find(String path) {
        return LazyJson.of(doc).find(JsonPath.compile(path)).text();
    }
}
