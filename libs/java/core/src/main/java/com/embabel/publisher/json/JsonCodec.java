package com.embabel.publisher.json;

/**
 * The one thing the core needs from a JSON library: reading a document into a plain tree and
 * writing one back. A tree is {@code Map<String, Object>} (in document order), {@code List<Object>},
 * {@code String}, {@code Boolean}, {@code null}, and numbers as {@code Long}, {@code BigInteger} or
 * {@code BigDecimal} so nothing is rounded through floating point.
 * <p>
 * Each starter supplies one over the Jackson its Boot line brings, so the core depends on none.
 */
public interface JsonCodec {

    /** @throws IllegalArgumentException when the bytes are not JSON */
    Object read(byte[] json);

    byte[] write(Object tree);
}
