package com.embabel.publisher.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates a message against the protocol's own schema, {@code spec/schema/protocol.schema.json},
 * read from the repository on every run, with a JSON Schema 2020-12 validator. The way
 * {@code check.py} does: the whole schema, rooted at {@code #/$defs/<Def>}.
 */
public final class ProtocolSchema {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final Map<String, JsonSchema> SCHEMAS = new ConcurrentHashMap<>();
    private static final JsonNode ROOT = load();

    private ProtocolSchema() {
    }

    private static JsonNode load() {
        String location = System.getProperty("protocol.schema");
        if (location == null) {
            throw new IllegalStateException("Set -Dprotocol.schema to spec/schema/protocol.schema.json (the build does)");
        }
        try {
            return JSON.readTree(Files.readString(Path.of(location)));
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Asserts the message is a valid {@code definition}, and returns it. */
    public static JsonNode assertValid(String definition, JsonNode message) {
        Set<ValidationMessage> errors = errors(definition, message);
        assertThat(errors).as("%s must validate against $defs/%s: %s", message, definition, errors).isEmpty();
        return message;
    }

    public static Set<ValidationMessage> errors(String definition, JsonNode message) {
        JsonSchema schema = SCHEMAS.computeIfAbsent(definition, def -> {
            assertThat(ROOT.get("$defs").has(def)).as("the schema defines %s", def).isTrue();
            ObjectNode rooted = ROOT.deepCopy();
            rooted.put("$ref", "#/$defs/" + def);
            return FACTORY.getSchema(rooted);
        });
        return schema.validate(message);
    }

    /** Asserts a message is valid against a JSON Schema a manifest carries, such as a verb's output. */
    public static void assertValidAgainst(JsonNode schema, JsonNode message) {
        Set<ValidationMessage> errors = FACTORY.getSchema(schema).validate(message);
        assertThat(errors).as("%s must validate against %s: %s", message, schema, errors).isEmpty();
    }
}
