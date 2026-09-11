package ru.jsonbytes.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.jsonbytes.JsonEncoder;

public final class JacksonEncoder implements JsonEncoder {
    private final ObjectMapper mapper;

    public JacksonEncoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize " + value.getClass().getName(), e);
        }
    }
}
