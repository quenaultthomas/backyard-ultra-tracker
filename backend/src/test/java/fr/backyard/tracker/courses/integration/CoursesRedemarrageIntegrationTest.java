package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contrat de l'incrément 2.1a, CA18 : après redémarrage du contexte sur la même base, la Course est toujours listée. */
@Testcontainers
class CoursesRedemarrageIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    /** L'arrêt d'une application retire le pont JUL : on le remet pour les autres tests du module. */
    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @Test
    @DisplayName("CA18 : après redémarrage du contexte sur la même base (ddl-auto=validate), la Course déclarée est toujours listée à l'identique")
    void ca18_la_course_survit_au_redemarrage() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        String date = LocalDate.now().plusDays(30).toString();
        String corps = "{\"nom\":\"Backyard des Crêtes\",\"date\":\"" + date + "\",\"distanceBoucleMetres\":6706,"
                + "\"dureeBoucleMinutes\":60,\"denivelePositifBoucleMetres\":0,\"nombreMaxParticipants\":50,"
                + "\"nombreMaxBoucles\":24}";
        JsonNode creee;
        try (ConfigurableApplicationContext premier = demarrer()) {
            ClientHttp api = new ClientHttp(port(premier));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> reponse = api.requete("POST", "/api/administration/courses", patron.entetes(),
                    "application/json", corps);
            assertThat(reponse.statusCode()).isEqualTo(201);
            creee = json.readTree(reponse.body());
        }

        try (ConfigurableApplicationContext second = demarrer()) {
            assertThat(second.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            ClientHttp api = new ClientHttp(port(second));
            Session patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
            HttpResponse<String> liste = api.requete("GET", "/api/administration/courses", patron.enteteLecture(),
                    null, null);
            assertThat(liste.statusCode()).isEqualTo(200);
            JsonNode tableau = json.readTree(liste.body());
            assertThat(tableau).hasSize(1);
            assertThat(tableau.get(0)).isEqualTo(creee);
        }
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
