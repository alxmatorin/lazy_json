package ru.jsonbytes;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

/** Standalone before/after benchmark; run in separate JVMs, with the same heap and JDK. */
public final class JsonBytesBenchmark {

    private static final long WINDOW_NANOS = 200_000_000L;
    private static volatile long blackhole;

    public static void main(String[] args) {
        byte[] object = bytes("{\"id\":1,\"padding\":[\"" + "x".repeat(500_000) + "\"],\"tail\":42}");
        byte[] array = bytes("{\"items\":[" + new String(object, StandardCharsets.UTF_8) + "]}");
        byte[] small = bytes("{\"id\":1,\"nested\":{\"id\":2},\"tail\":42}");
        byte[] many = bytes("{\"items\":[" + ("{\"id\":1,\"padding\":\"" + "x".repeat(100) + "\"},").repeat(1_000)
                + "{\"id\":2}]}");
        byte[] structured = structuredDocument();
        byte[] value = bytes("123");
        measureFind("find/start", object, "$id");
        measureFind("find/end", object, "$tail");
        measureFind("find/structured-end", structured, "$tail");
        measureFind("find/structured-end-hint", structured, "$<tail");
        measureFind("find/end-hint", object, "$<tail");
        measureFind("find/large-end-hint", object, "$<padding");
        measureFind("find/index-head", array, "$items[0].id");
        ElementPath wildcard = ElementPath.compile("$items[*].id");
        measure("findAll/wildcard", many, doc -> JsonBytes.of(doc).findAll(wildcard).size());
        measure("findAll/single", object, doc -> JsonBytes.of(doc).findAll(ElementPath.compile("$id")).size());
        ElementPath id = ElementPath.compile("$id");
        measure("put/small", small, doc -> JsonBytes.of(doc).set(id, value).length);
        measure("put/large", object, doc -> JsonBytes.of(doc).set(id, value).length);
        List<Edit> single = List.of(Edit.raw("$id", "123"));
        measure("apply/single", small, doc -> JsonBytes.of(doc).apply(single).length);
        List<Edit> batch = List.of(Edit.raw("$id", "123"), Edit.raw("$nested.id", "456"));
        measure("apply/batch", small, doc -> JsonBytes.of(doc).apply(batch).length);
        System.out.println("checksum=" + blackhole);
    }

    private static byte[] structuredDocument() {
        String row = "{\"id\":1,\"name\":\"group-1\",\"active\":true,"
                + "\"attrs\":{\"color\":\"red\",\"size\":7,\"unit\":\"kg\",\"note\":\"example\"},"
                + "\"values\":[0,1,2,3,4],\"tags\":[\"first\",\"second\",\"common\"],"
                + "\"nested\":{\"child\":{\"x\":1.5,\"y\":-1},\"leaf\":\"value\"}}";
        return bytes("{\"head\":1,\"items\":[" + (row + ",").repeat(2_000) + row + "],\"tail\":42}");
    }

    static void measureFind(String name, byte[] doc, String path) {
        ElementPath compiled = ElementPath.compile(path);
        measure(name, doc, input -> JsonBytes.of(input).find(compiled).length());
    }

    private static void measure(String name, byte[] doc, ToIntFunction<byte[]> action) {
        ThreadMXBean memory = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 3; i++) {
            window(doc, action);
        }
        double[] timings = new double[5];
        double[] allocations = new double[5];
        for (int i = 0; i < timings.length; i++) {
            long allocated = memory.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            long operations = window(doc, action);
            timings[i] = (System.nanoTime() - start) / (double) operations;
            allocations[i] = (memory.getThreadAllocatedBytes(thread) - allocated) / (double) operations;
        }
        Arrays.sort(timings);
        Arrays.sort(allocations);
        System.out.printf(java.util.Locale.ROOT, "%s,%.1f,%.1f%n", name, timings[2], allocations[2]);
    }

    private static long window(byte[] doc, ToIntFunction<byte[]> action) {
        long deadline = System.nanoTime() + WINDOW_NANOS;
        long count = 0;
        long checksum = 0;
        do {
            for (int i = 0; i < 256; i++) {
                checksum += action.applyAsInt(doc);
            }
            count += 256;
        } while (System.nanoTime() < deadline);
        blackhole = checksum;
        return count;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
