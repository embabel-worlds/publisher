package com.embabel.publisher.realm;

import com.embabel.publisher.json.JsonCodec;
import com.embabel.publisher.protocol.ErrorCode;
import com.embabel.publisher.protocol.RealmException;

import java.util.Map;
import java.util.function.Function;

/** Everything under the publisher URL, independent of the HTTP stack that carries it. */
public interface RealmEndpoint {

    /**
     * @param method  the HTTP method
     * @param subPath the path below the publisher URL: {@code ""} for the manifest, {@code "/fetch"}, …
     * @param header  a request header by name, case-insensitively; {@code null} when absent
     * @param body    the request body, empty when there is none
     */
    record Request(String method, String subPath, Function<String, String> header, byte[] body) {
    }

    record Response(int status, String contentType, Map<String, String> headers, byte[] body) {
    }

    Response handle(Request request);

    /**
     * The endpoint of a publisher that is turned off (§2.2): every path, the manifest included,
     * answers 503 {@code PUBLISHER_DISABLED} and nothing else. It holds no realm, so it has
     * nothing it could reveal.
     */
    static RealmEndpoint disabled(JsonCodec codec) {
        byte[] body = codec.write(RealmException.problem(ErrorCode.PUBLISHER_DISABLED, null));
        return request -> new Response(503, "application/problem+json", Map.of(), body);
    }
}
