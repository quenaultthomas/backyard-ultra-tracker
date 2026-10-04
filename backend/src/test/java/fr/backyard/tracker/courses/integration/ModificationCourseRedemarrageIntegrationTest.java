package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contrat de l'incrément 2.2, CA16 : modification, redémarrage du contexte sur la même base, schéma inchangé. */
@Testcontainers
class ModificationCourseRedemarrageIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String CHEMIN = "/api/administration/courses";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @Test
    @DisplayName("CA16 : après modification puis redémarrage (ddl-auto=validate), la Course est relue modifiée, aucun changeset ajouté, les contraintes refusent encore distance 0")
    void ca16_la_modification_survit_au_redemarrage() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        String date = LocalDate.now().plusDays(30).toString();
        String modifiee;
        String id;
        try (ConfigurableApplicationContext premier = demarrer()) {
            ClientHttp api = new ClientHttp(port(premier));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> creation = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                    corps("Backyard des Crêtes", date, 60, 120));
            assertThat(creation.statusCode()).isEqualTo(201);
            id = json.readTree(creation.body()).get("id").asString();
            HttpResponse<String> reponse = api.requete("PUT", CHEMIN + "/" + id, patron.entetes(),
                    "application/json", corps("Backyard des Alpes", date, 45, 0));
            assertThat(reponse.statusCode()).isEqualTo(200);
            modifiee = reponse.body();
        }

        try (ConfigurableApplicationContext second = demarrer()) {
            assertThat(second.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            ClientHttp api = new ClientHttp(port(second));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> liste = api.requete("GET", CHEMIN, patron.enteteLecture(), null, null);
            JsonNode tableau = json.readTree(liste.body());
            assertThat(tableau).hasSize(1);
            assertThat(tableau.get(0)).isEqualTo(json.readTree(modifiee));
            assertThat(tableau.get(0).get("dureeBoucleMinutes").asInt()).isEqualTo(45);
        }

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                "0006-affectation-benevole", "0007-inscription");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'course'",
                Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid = 'course'::regclass",
                Integer.class)).isEqualTo(7);
        assertThatThrownBy(() -> jdbc.update("update course set distance_boucle_metres = 0 where id = ?",
                UUID.fromString(id))).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_course_distance_boucle_metres");
    }

    private static String corps(String nom, String date, int duree, int denivele) {
        return "{\"nom\":\"" + nom + "\",\"date\":\"" + date + "\",\"distanceBoucleMetres\":6706,"
                + "\"dureeBoucleMinutes\":" + duree + ",\"denivelePositifBoucleMetres\":" + denivele
                + ",\"nombreMaxParticipants\":50,\"nombreMaxBoucles\":24}";
    }

    private ConfigurableApplicationContext demarrer() {
        return new SpringApplicationBuilder(BackyardUltraTrackerApplication.class).run(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0",
                "--backyard.admin-master.pseudo=Patron",
                "--backyard.admin-master.mot-de-passe=" + MOT_DE_PASSE_PATRON).toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext contexte) {
        return Integer.parseInt(contexte.getEnvironment().getProperty("local.server.port"));
    }
}
