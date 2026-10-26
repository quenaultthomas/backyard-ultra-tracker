package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Incrément 8.3 : exposition minimale de la version (CA2, CA3, CA5). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "info.app.version=1.2.3-rc.1")
@Testcontainers
class VersionPubliqueIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Test
    @DisplayName("CA2 : GET /actuator/info anonyme répond 200 et exactement {\"app\":{\"version\":\"1.2.3-rc.1\"}}")
    void ca2_info_ne_contient_que_la_version_deployee() throws Exception {
        HttpResponse<String> reponse = SanteHttp.get(port, "/actuator/info");

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("Content-Type").orElse("")).contains("json");
        JsonNode corps = JsonMapper.builder().build().readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactly("app");
        assertThat(corps.get("app").propertyNames()).containsExactly("version");
        assertThat(corps.get("app").get("version").asString()).isEqualTo("1.2.3-rc.1");
    }

    @Test
    @DisplayName("CA3 : le corps brut de /actuator/info ne divulgue aucune métadonnée de build ni secret")
    void ca3_info_ne_divulgue_aucune_metadonnee() throws Exception {
        String corps = SanteHttp.get(port, "/actuator/info").body();

        assertThat(corps).doesNotContain("group", "artifact", "name", "time", "build", "fr.backyard",
                "backyard-ultra-tracker", "Backyard Ultra Tracker", "0.5.0", "password", "motDePasse");
    }

    @Test
    @DisplayName("CA5 : health 200 UP, env et beans 404 pour un anonyme")
    void ca5_seuls_health_et_info_sont_exposes() throws Exception {
        HttpResponse<String> sante = SanteHttp.get(port, "/actuator/health");
        assertThat(sante.statusCode()).isEqualTo(200);
        assertThat(JsonMapper.builder().build().readTree(sante.body()).get("status").asString()).isEqualTo("UP");
        assertThat(SanteHttp.get(port, "/actuator/env").statusCode()).isEqualTo(404);
        assertThat(SanteHttp.get(port, "/actuator/beans").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("CA5 : POST /actuator/info sans session n'est jamais 2xx")
    void ca5_post_sur_info_nest_jamais_accepte() throws Exception {
        HttpRequest requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/info"))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> reponse = client.send(requete, HttpResponse.BodyHandlers.ofString());
            assertThat(reponse.statusCode() / 100).as("statut " + reponse.statusCode()).isNotEqualTo(2);
        }
    }

    @Test
    @DisplayName("CA5 : GET /actuator/info ne crée aucun cookie de session")
    void ca5_info_ne_cree_pas_de_session() throws Exception {
        HttpResponse<String> reponse = SanteHttp.get(port, "/actuator/info");

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().allValues("Set-Cookie")).isEmpty();
    }
}
