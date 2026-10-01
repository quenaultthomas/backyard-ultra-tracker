package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SqueletteIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("CA1 : le contexte démarre sur PostgreSQL avec ddl-auto=validate")
    void ca1_le_contexte_demarre_avec_ddl_auto_validate(@Autowired org.springframework.core.env.Environment env)
            throws Exception {
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        try (var connexion = dataSource.getConnection()) {
            assertThat(connexion.getMetaData().getURL()).startsWith("jdbc:postgresql://localhost:" + POSTGRES.getFirstMappedPort());
            assertThat(connexion.isValid(2)).isTrue();
        }
    }

    @Test
    @DisplayName("CA2 : Liquibase est appliqué, aucun changeset applicatif et aucune table métier")
    void ca2_liquibase_applique_sans_table_metier() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer changesets = jdbc.queryForObject("select count(*) from databasechangelog", Integer.class);
        assertThat(changesets).isZero();

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).containsExactlyInAnyOrder("databasechangelog", "databasechangeloglock");
    }

    @Test
    @DisplayName("CA3 : GET /actuator/health sans authentification répond 200 et exactement {\"status\":\"UP\"}")
    void ca3_health_repond_up_sans_details() throws Exception {
        HttpResponse<String> reponse = SanteHttp.get(port, "/actuator/health");

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = JsonMapper.builder().build().readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactly("status");
        assertThat(corps.get("status").asString()).isEqualTo("UP");
        assertThat(reponse.body()).doesNotContain("components");
    }

    @Test
    @DisplayName("CA4 : les endpoints actuator info, env et beans répondent 404")
    void ca4_endpoints_actuator_non_exposes() throws IOException, InterruptedException {
        for (String chemin : List.of("/actuator/info", "/actuator/env", "/actuator/beans")) {
            assertThat(SanteHttp.get(port, chemin).statusCode()).as(chemin).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("CA13 : le build tourne sous Java 25 et Spring Boot 4.1.1")
    void ca13_build_sous_java_25_et_spring_boot_4_1_1() {
        assertThat(Runtime.version().feature()).isEqualTo(25);
        assertThat(SpringBootVersion.getVersion()).isEqualTo("4.1.1");
    }
}
