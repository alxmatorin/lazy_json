package ru.jsonbytes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonBytesDifferentialTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void randomizedReadsAndBatchEditsAgreeWithJackson() throws Exception {
        Random random = new Random(61_029);
        for (int round = 0; round < 300; round++) {
            ObjectNode root = JSON.createObjectNode();
            root.put("padding", "x".repeat(random.nextInt(64)));
            ArrayNode items = root.putArray("items");
            for (int i = random.nextInt(20) + 1; i > 0; i--) {
                ObjectNode item = items.addObject().put("id", i);
                item.put("text", "😀\\\"}][{\nключ".repeat(random.nextInt(20)));
                if (random.nextBoolean()) {
                    item.putObject("meta").put("keep", true);
                }
            }
            root.put("tail", round);
            byte[] doc = round % 2 == 0 ? JSON.writeValueAsBytes(root)
                    : JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            byte[] original = doc.clone();
            int index = random.nextInt(items.size());
            String path = "$items[" + index + "].text";
            assertEquals(items.get(index).get("text"), JSON.readTree(JsonBytes.of(doc).find(ElementPath.compile(path)).copy()));
            assertEquals(items.get(index).get("text"), JSON.readTree(JsonBytes.of(doc).find(ElementPath.compile("$<" + path.substring(1))).copy()));

            List<Edit> edits = List.of(Edit.raw("$items[*].meta.flag", "true"),
                    Edit.raw("$items[" + index + "].id", "42"), Edit.raw("$new.a", "1"),
                    Edit.raw("$new.b", "2"), Edit.raw(round % 2 == 0 ? "$<tail" : "$tail", "null"));
            byte[] result = JsonBytes.of(doc).apply(edits);
            for (var item : items) {
                ((ObjectNode) item).withObject("/meta").put("flag", true);
            }
            ((ObjectNode) items.get(index)).put("id", 42);
            root.putObject("new").put("a", 1).put("b", 2);
            root.putNull("tail");
            assertEquals(root, JSON.readTree(result), "round " + round);
            assertArrayEquals(original, doc);
        }
    }
}
