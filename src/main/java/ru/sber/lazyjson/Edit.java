package ru.sber.lazyjson;

import java.nio.charset.StandardCharsets;

/**
 * Правка «вставить или заменить» с уже готовым JSON в {@code value}.
 * Удобнее собирать через {@link LazyJson#edit()}; напрямую — когда правки готовятся заранее.
 */
public record Edit(JsonPath path, byte[] value) {

    /** Значение сериализуется {@link JsonEncoder#DEFAULT}; {@code byte[]} — уже готовый JSON. */
    public static Edit of(String path, Object value) {
        return new Edit(JsonPath.compile(path), value instanceof byte[] json ? json : JsonEncoder.DEFAULT.encode(value));
    }

    /** {@code json} — готовый JSON-текст, вставляется как есть. */
    public static Edit raw(String path, String json) {
        return new Edit(JsonPath.compile(path), json.getBytes(StandardCharsets.UTF_8));
    }
}
