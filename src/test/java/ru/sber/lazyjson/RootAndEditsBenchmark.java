package ru.sber.lazyjson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;
import org.example.json.bench.KeysScenario;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;

/** Identical workload for old and new library classes; run each in a separate Java 21 JVM. */
public final class RootAndEditsBenchmark {

    private static final ThreadMXBean MEMORY = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static volatile long blackhole;

    public static void main(String[] args) throws Exception {
        List<byte[]> documents = new KeysScenario().documents();
        verifyDocuments(documents);
        measureFinds(documents);
        measureEdits(documents);
        System.out.println("checksum=" + blackhole);
    }

    private static void verifyDocuments(List<byte[]> documents) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (byte[] doc : documents) {
            if (mapper.readTree(doc).size() != 10 || doc.length < 490_000 || doc.length > 520_000) {
                throw new IllegalStateException("Expected 10 root fields and about 500 KB");
            }
        }
        System.out.printf("# documents=%d, root_fields=10, bytes=%d%n", documents.size(), documents.getFirst().length);
        System.out.println("case,ns/op,bytes/op");
    }

    private static void measureFinds(List<byte[]> documents) throws Exception {
        JsonPath start = JsonPath.compile("$key3.key4");
        JsonPath tail = JsonPath.compile("$key1.key2");
        JsonPath hinted = JsonPath.compile("$<key1.key2");
        JsonPath root = JsonPath.compile("$key1");
        measure("find/cold-start", i -> LazyJson.of(documents.get(i)).find(start).length());
        measure("find/cold-tail", i -> LazyJson.of(documents.get(i)).find(tail).length());
        measure("find/cold-tail-hint", i -> LazyJson.of(documents.get(i)).find(hinted).length());
        LazyJson[] cached = indexed(documents);
        measure("find/cached-root", i -> cached[i].find(root).length());
        measure("find/cached-tail", i -> cached[i].find(tail).length());
        LazyJson[] escaped = indexed(escapedDocuments(documents));
        measure("find/cached-escaped-root", i -> escaped[i].find(root).length());
    }

    private static LazyJson[] indexed(List<byte[]> documents) {
        return documents.stream().map(doc -> {
            LazyJson json = LazyJson.of(doc);
            json.find("$absent");
            return json;
        }).toArray(LazyJson[]::new);
    }

    private static List<byte[]> escapedDocuments(List<byte[]> documents) throws Exception {
        var names = new ObjectMapper().readTree(documents.getFirst()).fieldNames();
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        names.forEachRemaining(keys::add);
        return documents.stream().map(doc -> {
            String text = new String(doc, StandardCharsets.UTF_8);
            for (String key : keys) {
                String escaped = String.format(Locale.ROOT, "\\u%04x", (int) key.charAt(0)) + key.substring(1);
                text = text.replace("\"" + key + "\":", "\"" + escaped + "\":");
            }
            return text.getBytes(StandardCharsets.UTF_8);
        }).toList();
    }

    private static void measureEdits(List<byte[]> documents) {
        LazyJson[] cached = indexed(documents);
        for (int count : new int[]{2, 10}) {
            LazyJson.Edits[] edits = Arrays.stream(cached).map(json -> batch(json, count)).toArray(LazyJson.Edits[]::new);
            measure("batch/reused/" + count, i -> edits[i].apply().length);
            measure("batch/fresh/" + count, i -> batch(cached[i], count).apply().length);
        }
        byte[] large = ("\"" + "x".repeat(500_000) + "\"").getBytes(StandardCharsets.UTF_8);
        JsonPath replacement = JsonPath.compile("$<key1.key2");
        JsonPath insertion = JsonPath.compile("$<key1.payload");
        measure("large/replace", i -> cached[i].set(replacement, large).length);
        measure("large/insert", i -> cached[i].set(insertion, large).length);
    }

    private static LazyJson.Edits batch(LazyJson json, int count) {
        LazyJson.Edits edits = json.edit().setRaw("$<key1.key2", "[7]");
        for (int i = 1; i < count; i++) {
            edits.setRaw("$<key1.extra" + i, "42");
        }
        return edits;
    }

    private static void measure(String name, IntUnaryOperator action) {
        for (int i = 0; i < 3; i++) {
            window(action);
        }
        double[] timings = new double[5];
        double[] allocations = new double[5];
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < timings.length; i++) {
            long allocated = MEMORY.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            long operations = window(action);
            timings[i] = (System.nanoTime() - start) / (double) operations;
            allocations[i] = (MEMORY.getThreadAllocatedBytes(thread) - allocated) / (double) operations;
        }
        Arrays.sort(timings);
        Arrays.sort(allocations);
        System.out.printf(Locale.ROOT, "%s,%.1f,%.1f%n", name, timings[2], allocations[2]);
    }

    private static long window(IntUnaryOperator action) {
        long deadline = System.nanoTime() + 200_000_000L;
        long count = 0;
        long checksum = 0;
        do {
            for (int i = 0; i < 64; i++) {
                checksum += action.applyAsInt(i & 15);
            }
            count += 64;
        } while (System.nanoTime() < deadline);
        blackhole = checksum;
        return count;
    }
}
