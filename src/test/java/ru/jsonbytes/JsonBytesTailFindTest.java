package ru.jsonbytes;

import com.fasterxml.jackson.databind.ObjectMapper;
import perfs.json.bench.KeysScenario;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonBytesTailFindTest {

    @Test
    void findsLastListInActualKeysScenario() throws Exception {
        ObjectMapper json = new ObjectMapper();
        byte[] doc = new KeysScenario().documents().getFirst();
        Slice found = JsonBytes.of(doc).find(ElementPath.compile("$key1.key2"));
        assertEquals(json.readTree("[42]"), json.readTree(found.copy()));
    }

    @Test
    void skipsLongStringsWithEscapesAndBracketsAtBlockBoundaries() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (int padding = 0; padding < 128; padding++) {
            for (int slashes = 0; slashes < 10; slashes++) {
                String noise = json.writeValueAsString("x".repeat(padding) + "\\".repeat(slashes)
                        + "\"}][{key1" + "x".repeat(128 - padding));
                byte[] doc = bytes("{\"noise\":[" + noise + ",{\"key1\":{\"key2\":[0]}}," + noise
                        + "],\"key1\":{\"key2\":[42]}}");
                assertEquals("[42]", JsonBytes.of(doc).find(ElementPath.compile("$key1.key2")).text());
                assertEquals(doc.length, JsonBytes.of(doc).find(ElementPath.compile("$")).length());
            }
        }
    }

    @Test
    void forwardSearchStillReturnsFirstRepeatedRootKey() {
        byte[] doc = bytes("{\"key1\":{\"key2\":[1]},\"padding\":[\"" + "x".repeat(500_000)
                + "\"],\"key1\":{\"key2\":[2]}}");
        assertEquals("[1]", JsonBytes.of(doc).find(ElementPath.compile("$key1.key2")).text());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
