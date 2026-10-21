package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contrat de l'incrément 7.2 (RG5, IP cliente) : quand le pair TCP est le proxy de confiance
 * (BACKYARD_PROXY_DE_CONFIANCE, ici {@code localhost}), l'IP retenue est celle du {@code X-Forwarded-For} ; limite
 * fixée à 2 par IP et par minute.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "backyard.limite-debit-public.par-minute=2", "backyard.limite-debit-public.proxy-de-confiance=localhost"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class LimiteDebitPublicProxyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder encodeur;

    @Autowired
    InitialiserAdminMaster initialiserAdminMaster;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    @Autowired
    CoursesIntegrationTest.HorlogeMutable horloge;

    DecorAbandon decor;
    UUID course;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
        course = decor.insererCourse("Backyard proxy", "EN_PREPARATION");
        horloge.fixer(Instant.parse("2026-10-21T10:00:00Z").plusSeconds(60L * Math.floorMod(System.nanoTime(), 100_000L)));
    }

    private HttpResponse<String> suiviDepuis(String ip) throws Exception {
        return decor.api.requete("GET", "/api/courses/" + course + "/suivi", Map.of("X-Forwarded-For", ip), null,
                null);
    }

    @Test
    @DisplayName("CA10 : derrière le proxy de confiance, chaque IP du X-Forwarded-For a son propre compteur : l'IP qui dépasse est refusée, une autre IP passe")
    void ca10_le_compteur_suit_l_ip_du_x_forwarded_for_du_proxy_de_confiance() throws Exception {
        assertThat(suiviDepuis("203.0.113.10").statusCode()).isEqualTo(200);
        assertThat(suiviDepuis("203.0.113.10").statusCode()).isEqualTo(200);
        assertThat(suiviDepuis("203.0.113.10").statusCode()).isEqualTo(429);

        assertThat(suiviDepuis("203.0.113.11").statusCode()).as("autre IP cliente").isEqualTo(200);
        assertThat(suiviDepuis("203.0.113.10").statusCode()).isEqualTo(429);
    }
}
