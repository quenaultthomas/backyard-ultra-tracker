package fr.backyard.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext
class BaseArreteeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Test
    @DisplayName("CA5 : base arrêtée après démarrage, GET /actuator/health répond 503 et DOWN")
    void ca5_health_repond_503_down_quand_la_base_est_arretee() throws Exception {
        assertThat(SanteHttp.get(port, "/actuator/health").statusCode()).isEqualTo(200);

        POSTGRES.stop();

        HttpResponse<String> reponse = SanteHttp.get(port, "/actuator/health");
        assertThat(reponse.statusCode()).isEqualTo(503);
        assertThat(JsonMapper.builder().build().readTree(reponse.body()).get("status").asString())
                .isEqualTo("DOWN");
    }
}
