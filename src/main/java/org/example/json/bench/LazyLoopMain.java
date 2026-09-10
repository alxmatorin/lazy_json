package org.example.json.bench;

import ru.sber.lazyjson.LazyJson;
import ru.sber.lazyjson.JsonPath;
import ru.sber.lazyjson.Slice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Модель нагрузки: на каждой итерации приходит документ, и для каждой строки таблицы операция
 * готовится и выполняется на нём. Время считается отдельно на каждую строку; каждые
 * {@value #REPORT_EVERY} документов печатается tps по окну, в конце — сводная таблица.
 *
 * Аргументы: [число документов] [стратегия: lazy | all]
 */
public final class LazyLoopMain {

    private static final int DEFAULT_ITERATIONS = 20_000;
    private static final int REPORT_EVERY = 1_000;
    private static final List<Strategy> STRATEGIES = List.of(new LazyStrategy());
    private static final Scenario SCENARIO = new KeysScenario();

    public static void main(String[] args) throws IOException {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_ITERATIONS;
        List<Strategy> strategies = selectStrategies(args.length > 1 ? args[1] : "lazy");

        List<byte[]> docs = SCENARIO.documents();
        List<Op> ops = SCENARIO.ops();
        printScenario(SCENARIO, ops, docs);
        List<Result> results = new ArrayList<>();
        for (Strategy strategy : strategies) {
            results.addAll(runDocuments(strategy, ops, docs, iterations));
        }
        printSummary(ops, strategies, results);
        printChecksum(results);
    }

    /** Итог одной строки одной стратегией: среднее по всем окнам, кроме первого (прогрев). */
    private record Result(Strategy strategy, Op op, double microsPerOp, long checksum) {

        double tps() {
            return 1e6 / microsPerOp;
        }
    }

    private static List<Strategy> selectStrategies(String wanted) {
        if (wanted.equals("all")) {
            return STRATEGIES;
        }
        return STRATEGIES.stream().filter(s -> s.name().equals(wanted)).findFirst().map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("unknown strategy: " + wanted));
    }

    private static void printScenario(Scenario scenario, List<Op> ops, List<byte[]> docs) {
        byte[] doc = docs.getFirst();
        System.out.printf("=== scenario %s: %d documents of %.1f KB, %d ops per document, report every %d documents%n",
                scenario.name(), docs.size(), doc.length / 1024.0, ops.size(), REPORT_EVERY);
        for (Op op : ops) {
            for (String path : pathsOf(op)) {
                printTarget(doc, path);
            }
        }
    }

    private static List<String> pathsOf(Op op) {
        return switch (op) {
            case Op.Find find -> List.of(find.path());
            case Op.FindMany many -> many.paths();
            case Op.Replace replace -> replace.replacements().stream().map(Op.Replacement::path).toList();
        };
    }

    private static void printTarget(byte[] doc, String path) {
        JsonPath compiled = JsonPath.compile(path);
        Slice slice = compiled.hasWildcard() ? null : LazyJson.of(doc).find(compiled);
        if (slice == null) {
            System.out.printf("    %-28s absent or wildcard%n", path);
        } else {
            System.out.printf("    %-28s at offset %7d (%4.1f%%), %6d bytes%n",
                    path, slice.offset(), 100.0 * slice.offset() / doc.length, slice.length());
        }
    }

    /** Внешний цикл — входящие документы, внутренний — строки таблицы. */
    private static List<Result> runDocuments(Strategy strategy, List<Op> ops, List<byte[]> docs, int iterations) {
        System.out.println("=== " + strategy.name() + " ===");
        long[] windowNanos = new long[ops.size()];
        long[] measuredNanos = new long[ops.size()];
        long[] checksums = new long[ops.size()];
        int measuredIterations = 0;
        for (int i = 1; i <= iterations; i++) {
            byte[] doc = docs.get(i % docs.size());
            for (int k = 0; k < ops.size(); k++) {
                long start = System.nanoTime();
                checksums[k] += strategy.run(ops.get(k), doc, i);
                windowNanos[k] += System.nanoTime() - start;
            }
            if (i % REPORT_EVERY == 0 || i == iterations) {
                int windowIterations = (i - 1) % REPORT_EVERY + 1;
                printWindow(strategy, ops, i, windowNanos, windowIterations);
                if (i > REPORT_EVERY || iterations <= REPORT_EVERY) {
                    for (int k = 0; k < ops.size(); k++) {
                        measuredNanos[k] += windowNanos[k];
                    }
                    measuredIterations += windowIterations;
                }
                java.util.Arrays.fill(windowNanos, 0);
            }
        }
        List<Result> results = new ArrayList<>(ops.size());
        for (int k = 0; k < ops.size(); k++) {
            results.add(new Result(strategy, ops.get(k), measuredNanos[k] / 1e3 / Math.max(1, measuredIterations), checksums[k]));
        }
        return results;
    }

    private static void printWindow(Strategy strategy, List<Op> ops, int iteration, long[] windowNanos, int windowIterations) {
        for (int k = 0; k < ops.size(); k++) {
            double tps = windowIterations / (windowNanos[k] / 1e9);
            double microsPerOp = windowNanos[k] / 1e3 / windowIterations;
            System.out.printf("%-8s %-54s doc %6d: %10.1f tps, %9.1f us/op%n",
                    strategy.name(), ops.get(k).name(), iteration, tps, microsPerOp);
        }
    }

    private static void printSummary(List<Op> ops, List<Strategy> strategies, List<Result> results) {
        boolean withRatio = strategies.size() == 2;
        System.out.println();
        System.out.println(summaryHeader(strategies, withRatio));
        System.out.println(summarySeparator(strategies, withRatio));
        for (Op op : ops) {
            System.out.println(summaryRow(op, strategies, results, withRatio));
        }
    }

    private static String summaryHeader(List<Strategy> strategies, boolean withRatio) {
        StringBuilder line = new StringBuilder(String.format("| %-54s |", "операция"));
        for (Strategy strategy : strategies) {
            line.append(String.format(" %15s | %15s |", strategy.name() + ", us/op", strategy.name() + ", tps"));
        }
        if (withRatio) {
            line.append(String.format(" %14s |", strategies.get(1).name() + "/" + strategies.get(0).name()));
        }
        return line.toString();
    }

    private static String summarySeparator(List<Strategy> strategies, boolean withRatio) {
        StringBuilder line = new StringBuilder("|" + "-".repeat(56) + "|");
        for (int i = 0; i < strategies.size(); i++) {
            line.append("-".repeat(16)).append(":|").append("-".repeat(16)).append(":|");
        }
        if (withRatio) {
            line.append("-".repeat(15)).append(":|");
        }
        return line.toString();
    }

    private static String summaryRow(Op op, List<Strategy> strategies, List<Result> results, boolean withRatio) {
        StringBuilder line = new StringBuilder(String.format("| %-54s |", op.name()));
        for (Strategy strategy : strategies) {
            Result result = resultOf(results, strategy, op);
            line.append(String.format(" %15.1f | %15.0f |", result.microsPerOp, result.tps()));
        }
        if (withRatio) {
            double ratio = resultOf(results, strategies.get(1), op).microsPerOp
                    / resultOf(results, strategies.get(0), op).microsPerOp;
            line.append(String.format(" %14s |", String.format("×%.1f", ratio)));
        }
        return line.toString();
    }

    private static Result resultOf(List<Result> results, Strategy strategy, Op op) {
        return results.stream().filter(r -> r.strategy == strategy && r.op == op).findFirst().orElseThrow();
    }

    private static void printChecksum(List<Result> results) {
        System.out.println();
        System.out.println("checksum: " + results.stream().mapToLong(Result::checksum).sum());
    }
}
