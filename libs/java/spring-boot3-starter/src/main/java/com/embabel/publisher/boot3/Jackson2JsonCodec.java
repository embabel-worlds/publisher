package com.embabel.publisher.boot3;

import com.embabel.publisher.json.JsonCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * The core's JSON over Jackson 2. Its own mapper, not the application's: the protocol's wire form is
 * fixed, and an application's customizations must not leak into it. Floats read as
 * {@code BigDecimal}, so a decimal is never rounded on the way in.
 */
public class Jackson2JsonCodec implements JsonCodec {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Override
    public Object read(byte[] json) {
        try {
            return mapper.readValue(json, Object.class);
        }
        catch (IOException e) {
            throw new IllegalArgumentException("Not JSON", e);
        }
    }

    @Override
    public byte[] write(Object tree) {
        try {
            return mapper.writeValueAsBytes(tree);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Writing JSON failed", e);
        }
    }
}
