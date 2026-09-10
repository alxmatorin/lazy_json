package ru.sber.lazyjson;

import org.example.json.bench.KeysScenario;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Backward searches that traverse large containers, plus the short tail control. */
public final class BackwardFindBenchmark {

    public static void main(String[] args) {
        byte[] doc = new KeysScenario().documents().getFirst();
        measureScenario(doc);
        measureLargeValues(doc);
        measureLongString();
    }

    private static void measureScenario(byte[] doc) {
        JsonBytesBenchmark.measureFind("backward/short-tail", doc, "$<key1.key2");
        JsonBytesBenchmark.measureFind("backward/across-document", doc, "$<key3.key4");
        JsonBytesBenchmark.measureFind("forward/control", doc, "$key1.key2");
    }

    private static void measureLargeValues(byte[] doc) {
        for (String key : List.of("key5", "key", "filler2")) {
            byte[] value = LazyJson.of(doc).find(JsonPath.compile("$" + key)).copy();
            byte[] wrapped = ("{\"value\":" + new String(value, StandardCharsets.UTF_8) + "}")
                    .getBytes(StandardCharsets.UTF_8);
            JsonBytesBenchmark.measureFind("backward/" + key, wrapped, "$<value");
        }
    }

    private static void measureLongString() {
        byte[] doc = ("{\"value\":\"" + "x".repeat(500_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        JsonBytesBenchmark.measureFind("backward/long-string", doc, "$<value");
    }
}
