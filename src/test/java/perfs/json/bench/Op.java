package perfs.json.bench;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Операция бенчмарка, не зависящая от стратегии.
 * Значение для замены выбирается по номеру итерации — так эмулируется поток разных значений,
 * которые нельзя сериализовать заранее. Значение — {@code Map}/{@code List} (сериализуется стратегией
 * на каждой итерации) либо готовый JSON в {@code byte[]} (подставляется как есть).
 */
public sealed interface Op permits Op.Find, Op.FindMany, Op.Replace {

    String name();

    record Find(String name, String path) implements Op {
    }

    /** Несколько поисков по одному входящему документу — на одном экземпляре обёртки. */
    record FindMany(String name, List<String> paths) implements Op {
    }

    record Replace(String name, List<Replacement> replacements) implements Op {
        public static Replace of(String name, String path, IntFunction<Object> valueAt) {
            return new Replace(name, List.of(new Replacement(path, valueAt)));
        }
    }

    record Replacement(String path, IntFunction<Object> valueAt) {
        public static Replacement fixed(String path, Object value) {
            return new Replacement(path, i -> value);
        }
    }
}
