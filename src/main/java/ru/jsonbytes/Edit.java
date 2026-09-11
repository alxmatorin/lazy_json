package ru.jsonbytes;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Правка «вставить или заменить» с уже готовым JSON в {@code value}.
 * Объекты сериализуются через {@link JsonBytes#edit()} — энкодером того документа; здесь только готовые байты.
 */
public record Edit(ElementPath path, byte[] value) {

    public static Edit of(String path, byte[] json) {
        return new Edit(ElementPath.compile(path), json);
    }

    public static Edit of(List<String> keys, byte[] json) {
        return new Edit(ElementPath.of(keys), json);
    }

    public static Edit of(String[] keys, int from, byte[] json) {
        return new Edit(ElementPath.of(keys, from), json);
    }

    /** {@code json} — готовый JSON-текст, вставляется как есть. */
    public static Edit raw(String path, String json) {
        return new Edit(ElementPath.compile(path), json.getBytes(StandardCharsets.UTF_8));
    }

    public static Edit raw(List<String> keys, String json) {
        return new Edit(ElementPath.of(keys), json.getBytes(StandardCharsets.UTF_8));
    }

    public static Edit raw(String[] keys, int from, String json) {
        return new Edit(ElementPath.of(keys, from), json.getBytes(StandardCharsets.UTF_8));
    }
}
