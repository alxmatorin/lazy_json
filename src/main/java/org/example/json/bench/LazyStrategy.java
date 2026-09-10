package org.example.json.bench;

import ru.sber.lazyjson.LazyJson;
import ru.sber.lazyjson.JsonPath;

import java.util.List;

/**
 * Сканирование байт по пути без разбора документа; правки — склейкой.
 * На каждый вызов собирается вся конструкция: путь (из кэша {@link JsonPath#compile}), значение
 * ({@code Map}/{@code List} сериализует {@link LazyJson}, {@code byte[]} вклеивается как есть), правка.
 */
public final class LazyStrategy implements Strategy {

    @Override
    public String name() {
        return "lazy";
    }

    @Override
    public int run(Op op, byte[] doc, int iteration) {
        LazyJson json = LazyJson.of(doc);
        return switch (op) {
            case Op.Find find -> find(json, JsonPath.compile(find.path()));
            case Op.FindMany many -> findMany(json, many.paths());
            case Op.Replace replace -> replace(json, replace.replacements(), iteration).length;
        };
    }

    private static int findMany(LazyJson json, List<String> paths) {
        int total = 0;
        for (String path : paths) {
            total += find(json, JsonPath.compile(path));
        }
        return total;
    }

    private static int find(LazyJson json, JsonPath path) {
        if (path.hasWildcard()) {
            return json.findAll(path).size();
        }
        return json.find(path).length();
    }

    private static byte[] replace(LazyJson json, List<Op.Replacement> replacements, int iteration) {
        if (replacements.size() == 1) {
            Op.Replacement only = replacements.getFirst();
            return json.set(only.path(), only.valueAt().apply(iteration));
        }
        LazyJson.Edits edits = json.edit();
        for (Op.Replacement replacement : replacements) {
            edits.set(replacement.path(), replacement.valueAt().apply(iteration));
        }
        return edits.apply();
    }
}
