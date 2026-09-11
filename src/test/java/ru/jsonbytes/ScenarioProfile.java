package ru.jsonbytes;

import perfs.json.bench.KeysScenario;
import perfs.json.bench.LazyStrategy;
import perfs.json.bench.Op;
import perfs.json.bench.Strategy;

import java.util.List;

/** Warm up first, then attach async-profiler when READY appears. Only the JsonBytes strategy runs. */
public final class ScenarioProfile {

    private static volatile long blackhole;

    public static void main(String[] args) {
        KeysScenario scenario = new KeysScenario();
        List<byte[]> documents = scenario.documents();
        List<Op> operations = args.length > 1
                ? List.of(scenario.ops().get(Integer.parseInt(args[1]))) : scenario.ops();
        Strategy strategy = new LazyStrategy();
        runForSeconds(strategy, documents, operations, 5);
        System.out.println("READY pid=" + ProcessHandle.current().pid());
        long count = runForSeconds(strategy, documents, operations, args.length > 0 ? Integer.parseInt(args[0]) : 30);
        System.out.println("documents=" + count + ", checksum=" + blackhole);
    }

    private static long runForSeconds(Strategy strategy, List<byte[]> documents, List<Op> operations, int seconds) {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        long checksum = 0;
        int iteration = 0;
        do {
            byte[] doc = documents.get(iteration % documents.size());
            for (Op operation : operations) {
                checksum += strategy.run(operation, doc, iteration);
            }
            iteration++;
        } while (System.nanoTime() < deadline);
        blackhole = checksum;
        return iteration;
    }
}
