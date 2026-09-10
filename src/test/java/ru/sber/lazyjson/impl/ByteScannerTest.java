package ru.sber.lazyjson.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ByteScannerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void skipsJacksonValuesInBothDirectionsAtEveryWordAlignment() throws Exception {
        Random random = new Random(519_273);
        for (int round = 0; round < 500; round++) {
            Object value = randomValue(random, 4);
            String json = round % 2 == 0 ? JSON.writeValueAsString(value)
                    : JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            for (int offset = 0; offset < 16; offset++) {
                byte[] doc = bytes(" ".repeat(offset) + json + " ".repeat(15 - offset));
                ByteScanner scanner = new ByteScanner(doc);
                int end = offset + bytes(json).length;
                assertEquals(end, scanner.skipValue(offset), "forward: " + json);
                assertEquals(offset, scanner.skipValueBack(end - 1), "backward: " + json);
            }
        }
    }

    @Test
    void handlesEscapedQuotesAcrossEveryWordBoundary() throws Exception {
        for (int padding = 0; padding < 32; padding++) {
            for (int slashes = 0; slashes < 20; slashes++) {
                String value = "x".repeat(padding) + "\\".repeat(slashes) + "\"}][{😀ключ";
                String json = JSON.writeValueAsString(List.of(value, Map.of("x", value)));
                byte[] doc = bytes(json);
                assertEquals(doc.length, new ByteScanner(doc).skipValue(0));
                assertEquals(0, new ByteScanner(doc).skipValueBack(doc.length - 1));
            }
        }
    }

    @Test
    void backwardLongStringsPreserveExactHitsAndEscapeParity() throws Exception {
        for (int padding = 0; padding < 64; padding++) {
            for (int slashes = 0; slashes < 10; slashes++) {
                String value = "#" + "\\".repeat(slashes) + "]\"[{😀" + "x".repeat(128 + padding);
                byte[] string = bytes(JSON.writeValueAsString(value));
                assertEquals(0, new ByteScanner(string).skipStringBack(string.length - 1));
                byte[] container = bytes(JSON.writeValueAsString(List.of(value, Map.of("key", value))));
                assertEquals(0, new ByteScanner(container).skipContainerBack(container.length - 1));
                assertEquals(container.length, new ByteScanner(container).skipContainer(0));
            }
        }
    }

    @Test
    void rejectsUnterminatedBackwardContainerIncludingExhaustedFallback() {
        for (String json : List.of("]", "1,2]", "\"\\\\\"]", "\"\\\"\"]", " ".repeat(20) + "]")) {
            byte[] doc = bytes(json);
            assertThrows(IllegalArgumentException.class, () -> new ByteScanner(doc).skipValueBack(doc.length - 1), json);
        }
    }

    private static Object randomValue(Random random, int depth) {
        return switch (random.nextInt(depth == 0 ? 5 : 7)) {
            case 0 -> randomText(random);
            case 1 -> random.nextLong();
            case 2 -> random.nextBoolean();
            case 3 -> null;
            case 4 -> random.nextDouble();
            case 5 -> randomObject(random, depth - 1);
            default -> randomArray(random, depth - 1);
        };
    }

    private static String randomText(Random random) {
        String[] pieces = {"x", "\\", "\"", "{", "}", "[", "]", "ключ", "😀", "\n", "\u0001"};
        StringBuilder text = new StringBuilder();
        for (int i = random.nextInt(60); i > 0; i--) {
            text.append(pieces[random.nextInt(pieces.length)]);
        }
        return text.toString();
    }

    private static Map<String, Object> randomObject(Random random, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = random.nextInt(5); i > 0; i--) {
            result.put(i + randomText(random), randomValue(random, depth));
        }
        return result;
    }

    private static List<Object> randomArray(Random random, int depth) {
        List<Object> result = new ArrayList<>();
        for (int i = random.nextInt(5); i > 0; i--) {
            result.add(randomValue(random, depth));
        }
        return result;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
