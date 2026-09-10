package ru.sber.lazyjson;

import org.example.json.bench.KeysScenario;

import java.util.List;

/** The actual KeysScenario workload and the values skipped before its last field. */
public final class KeysFindBenchmark {

    public static void main(String[] args) {
        byte[] doc = new KeysScenario().documents().getFirst();
        measureScenario(doc);
        measureSkippedValues(doc);
    }

    private static void measureScenario(byte[] doc) {
        System.out.println("document-bytes=" + doc.length);
        JsonBytesBenchmark.measureFind("keys/forward", doc, "$key1.key2");
        JsonBytesBenchmark.measureFind("keys/backward", doc, "$<key1.key2");
    }

    private static void measureSkippedValues(byte[] doc) {
        for (String key : List.of("key3", "key5", "filler1", "key", "filler2")) {
            byte[] value = LazyJson.of(doc).find(JsonPath.compile("$" + key)).copy();
            System.out.println(key + "-bytes=" + value.length);
            JsonBytesBenchmark.measureFind("skip/" + key, value, "$");
        }
    }
}
