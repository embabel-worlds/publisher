package com.embabel.publisher.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** The world's side of the wire, over the JDK's own client so both Boot lines are tested alike. */
public final class RealmClient {

    public static final ObjectMapper JSON = new ObjectMapper();

    public record Answer(int status, Map<String, java.util.List<String>> headers, String body) {

        public JsonNode json() {
            try {
                return JSON.readTree(body);
            }
            catch (IOException e) {
                throw new IllegalStateException("Not JSON: " + body, e);
            }
        }

        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final String base;
    private final String token;

    public RealmClient(int port, String token) {
        this.base = "http://localhost:" + port + "/embabel";
        this.token = token;
    }

    public Answer get(String path, String... headers) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), headers);
    }

    public Answer post(String path, Object body, String... headers) {
        try {
            String json = body instanceof String s ? s : JSON.writeValueAsString(body);
            return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)), headers);
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Answer send(HttpRequest.Builder builder, String... headers) {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(response.statusCode(), response.headers().map(), response.body());
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
