package ru.sber.lazyjson;

import com.fasterxml.jackson.databind.ObjectMapper;
import ru.sber.lazyjson.impl.CachingEncoder;
import ru.sber.lazyjson.impl.JacksonEncoder;

/**
 * Сериализация значения в JSON-байты для {@link LazyJson#set(String, Object)}.
 * По умолчанию — Jackson с кэшем на {@value #DEFAULT_CACHE_SIZE} объектов: повторная вставка
 * того же экземпляра берёт готовые байты без сериализации (см. {@link #cached}).
 */
@FunctionalInterface
public interface JsonEncoder {

    int DEFAULT_CACHE_SIZE = 500;

    JsonEncoder DEFAULT = cached(jackson(new ObjectMapper()), DEFAULT_CACHE_SIZE);

    byte[] encode(Object value);

    static JsonEncoder jackson(ObjectMapper mapper) {
        return new JacksonEncoder(mapper);
    }

    /**
     * Кэш на {@code maxEntries} значений: строки — по {@code equals}, остальные объекты — по идентичности
     * ({@code ==}, не {@code equals}). Закэшированные объекты не должны мутировать.
     */
    static JsonEncoder cached(JsonEncoder delegate, int maxEntries) {
        return new CachingEncoder(delegate, maxEntries);
    }
}
