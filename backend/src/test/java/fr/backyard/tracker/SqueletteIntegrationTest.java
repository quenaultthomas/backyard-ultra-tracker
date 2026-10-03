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
    @DisplayName("CA2 (évolué par 1.1 CA21 et 2.1a RG19) : Liquibase est appliqué, changesets 0002-compte, 0003-admin-master-unique (1.4 CA22) puis 0004-course, 0005-logo-course (2.3 RG18) et 0006-affectation-benevole (2.4 RG14), et les seules tables métier compte, course, logo_course et affectation_benevole")
    void ca2_liquibase_applique_avec_uniquement_les_tables_compte_et_course() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> changesets = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);
        assertThat(changesets).containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                "0006-affectation-benevole");

        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).containsExactlyInAnyOrder("databasechangelog", "databasechangeloglock", "compte", "course", "logo_course",
                "affectation_benevole");
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
