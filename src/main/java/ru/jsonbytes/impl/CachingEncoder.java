package ru.jsonbytes.impl;

import ru.jsonbytes.JsonEncoder;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш сериализованных значений: повторная вставка того же значения берёт готовые байты без сериализации.
 * <p>
 * Ключ: строки — по {@code equals} (их {@code hashCode} кэшируется, а одинаковые строки обычно
 * разные экземпляры); остальные объекты — по идентичности ({@code ==}): их {@code equals}/{@code hashCode}
 * обошли бы всё дерево значения. Закэшированный объект нельзя мутировать — байты останутся старыми.
 * <p>
 * Два поколения: новое значение попадает в young, при повторном обращении переезжает в old.
 * Переполнение чистит только своё поколение, поэтому поток одноразовых объектов не вымывает горячие.
 */
public final class CachingEncoder implements JsonEncoder {

    private record Identity(Object ref) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && identity.ref == ref;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(ref);
        }
    }

    private final JsonEncoder delegate;
    private final int generationSize;
    private final ConcurrentHashMap<Object, byte[]> young;
    private final ConcurrentHashMap<Object, byte[]> old;

    public CachingEncoder(JsonEncoder delegate, int maxEntries) {
        this.delegate = delegate;
        this.generationSize = Math.max(1, maxEntries / 2);
        this.young = new ConcurrentHashMap<>(generationSize * 2);
        this.old = new ConcurrentHashMap<>(generationSize * 2);
    }

    @Override
    public byte[] encode(Object value) {
        if (value == null) {
            return delegate.encode(null);
        }
        Object key = keyOf(value);
        byte[] cached = old.get(key);
        if (cached != null) {
            return cached;
        }
        cached = young.remove(key);
        if (cached != null) {
            promote(key, cached);
            return cached;
        }
        byte[] json = delegate.encode(value);
        remember(young, key, json);
        return json;
    }

    private static Object keyOf(Object value) {
        return value instanceof String ? value : new Identity(value);
    }

    private void promote(Object key, byte[] json) {
        remember(old, key, json);
    }

    private void remember(ConcurrentHashMap<Object, byte[]> generation, Object key, byte[] json) {
        if (generation.size() >= generationSize) {
            generation.clear();
        }
        generation.put(key, json);
    }

    public int size() {
        return young.size() + old.size();
    }
}
