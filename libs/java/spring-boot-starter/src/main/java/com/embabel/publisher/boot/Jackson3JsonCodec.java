package com.embabel.publisher.boot;

import com.embabel.publisher.json.JsonCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core's JSON over Jackson 3. Its own mapper, not the application's: the protocol's wire form is
 * fixed, and an application's customizations (dates as timestamps, say) must not leak into it.
 * Floats read as {@code BigDecimal}, so a decimal is never rounded on the way in.
 */
public class Jackson3JsonCodec implements JsonCodec {

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Override
    public Object read(byte[] json) {
        try {
            return mapper.readValue(json, Object.class);
        }
        catch (JacksonException e) {
            throw new IllegalArgumentException("Not JSON", e);
        }
    }

    @Override
    public byte[] write(Object tree) {
        return mapper.writeValueAsBytes(tree);
    }
}
