package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Incrément 8.3 CA4 : même avec un BuildProperties présent, /actuator/info n'expose pas le bloc build. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "info.app.version=1.2.3-rc.1")
@Import(VersionPubliqueBuildInfoIntegrationTest.BuildPropertiesDeTest.class)
@Testcontainers
class VersionPubliqueBuildInfoIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @TestConfiguration
    static class BuildPropertiesDeTest {
        @Bean
        BuildProperties buildProperties() {
            Properties p = new Properties();
            p.setProperty("group", "fr.backyard");
            p.setProperty("artifact", "backyard-ultra-tracker");
            p.setProperty("name", "Backyard Ultra Tracker");
            p.setProperty("version", "0.5.0");
            p.setProperty("time", Instant.parse("2026-10-26T08:00:00Z").toString());
            return new BuildProperties(p);
        }
    }

    @Test
    @DisplayName("CA4 : un BuildProperties présent n'ajoute pas le bloc build à /actuator/info")
    void ca4_le_bloc_build_reste_absent_meme_avec_build_properties() throws Exception {
        String corps = SanteHttp.get(port, "/actuator/info").body();

        JsonNode json = JsonMapper.builder().build().readTree(corps);
        assertThat(json.has("build")).isFalse();
        assertThat(json.propertyNames()).containsExactly("app");
        assertThat(corps).doesNotContain("fr.backyard", "0.5.0", "backyard-ultra-tracker");
    }
}
