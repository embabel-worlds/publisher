package com.embabel.publisher.it;

import com.embabel.publisher.it.app.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.context.ContextConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.embabel.publisher.it.RealmClient.map;
import static org.assertj.core.api.Assertions.assertThat;

/** Protocol §2.2: off means every path answers PUBLISHER_DISABLED and nothing else. */
class OffSwitchIT {

    static void assertOff(int port) {
        for (RealmClient client : List.of(new RealmClient(port, "test-token"), new RealmClient(port, null))) {
            for (RealmClient.Answer answer : List.of(client.get(""), client.get("/"),
                    client.post("/fetch", map("type", "Customer", "by", "id", "keys", List.of("1"))),
                    client.post("/query", map("type", "Customer")), client.post("/verbs/rename", map("id", 1, "name", "x")),
                    client.get("/changes"), client.get("/skills/x/SKILL.md"), client.post("/aggregate", "{}"))) {
                assertThat(answer.status()).isEqualTo(503);
                assertThat(answer.header("Content-Type")).startsWith("application/problem+json");
                JsonNode problem = ProtocolSchema.assertValid("Problem", answer.json());
                assertThat(problem.get("code").asText()).isEqualTo("PUBLISHER_DISABLED");
                assertThat(answer.header("ETag")).isNull();
                /* Nothing else: no type, verb or realm name, no detail. */
                assertThat(answer.body()).doesNotContain("Customer", "billing", "rename", "detail");
            }
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "embabel.realm.enabled=false")
    class ByProperty {

        @LocalServerPort
        int port;

        @Test
        void everyPathIsDisabled() {
            assertOff(port);
        }
    }

    /** The variable turns publication off even where the configuration says on. */
    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "embabel.realm.enabled=true")
    @ContextConfiguration(initializers = ByEnvironmentVariable.Variable.class)
    class ByEnvironmentVariable {

        static class Variable implements ApplicationContextInitializer<ConfigurableApplicationContext> {

            @Override
            public void initialize(ConfigurableApplicationContext context) {
                var sources = context.getEnvironment().getPropertySources();
                Map<String, Object> env = new HashMap<>(System.getenv());
                env.put("EMBABEL_REALM_ENABLED", "false");
                sources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
            }
        }

        @LocalServerPort
        int port;

        @Test
        void everyPathIsDisabled() {
            assertOff(port);
        }
    }

    /** The rest of the application is untouched. */
    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "embabel.realm.enabled=false")
    class OtherPaths {

        @LocalServerPort
        int port;

        @Test
        void areNotTheFiltersBusiness() throws Exception {
            var response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/embabelish")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.body()).doesNotContain("PUBLISHER_DISABLED");
        }
    }
}
