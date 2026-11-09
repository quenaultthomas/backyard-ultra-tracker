package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Incrément 10.2, CA11 (RG6) : avec BACKYARD_LIMITE_DEBIT_PHOTOS_PAR_MINUTE = 0, aucune limite sur les photos. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "backyard.limite-debit-photos.par-minute=0"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class LimiteDebitPhotosDesactiveeIntegrationTest {

    static final Path REPERTOIRE = repertoireTemporaire();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add("backyard.photos.repertoire", REPERTOIRE::toString);
    }

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

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-debit0").resolve("photos");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    @Test
    @DisplayName("CA11 : limite à 0 : 50 GET /api/photos/<jeton> passent (200), aucun 429")
    void ca11_limite_zero_n_interdit_rien() throws Exception {
        ClientPhotos photos = new ClientPhotos(port);
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 1)).statusCode()).isEqualTo(200);
        String url = decor.json.readTree(new String(photos.lire("GET", "/api/comptes/moi", decor.bruno.enteteLecture())
                .body(), java.nio.charset.StandardCharsets.UTF_8)).get("photoUrl").asString();

        for (int i = 1; i <= 50; i++) {
            assertThat(photos.lire("GET", url, Map.of()).statusCode()).as("requête " + i).isEqualTo(200);
        }
    }
}
