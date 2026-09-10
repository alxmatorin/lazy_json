package ru.sber.lazyjson;

import java.nio.charset.StandardCharsets;

/**
 * Правка «вставить или заменить» с уже готовым JSON в {@code value}.
 * Объекты сериализуются через {@link LazyJson#edit()} — энкодером того документа; здесь только готовые байты.
 */
public record Edit(JsonPath path, byte[] value) {

    public static Edit of(String path, byte[] json) {
        return new Edit(JsonPath.compile(path), json);
    }

    /** {@code json} — готовый JSON-текст, вставляется как есть. */
    public static Edit raw(String path, String json) {
        return new Edit(JsonPath.compile(path), json.getBytes(StandardCharsets.UTF_8));
    }
}
