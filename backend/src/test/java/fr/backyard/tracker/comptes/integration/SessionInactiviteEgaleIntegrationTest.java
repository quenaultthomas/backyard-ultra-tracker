package fr.backyard.tracker.comptes.integration;

import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_ADMIN;
import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_BENEVOLE;
import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_COUREUR;
import static fr.backyard.tracker.comptes.integration.OutilsSession.patienter;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.PolitiqueSession;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.OutilsSession.Session;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Incrément 8.2 (SEC-16), CA5 : la configuration « inversée » de la spec (administrateurs 3 600 s, autres 3 s) est
 * refusée au démarrage par RG3 (administrateurs plus longs que les autres), refus vérifié par CA8. Elle ne peut donc
 * pas être exercée sur un contexte réel. Ce test couvre l'intention de CA5 avec l'égalité admise (3 s partout) :
 * le délai est posé explicitement pour COUREUR et BENEVOLE par la politique, et non laissé au délai du conteneur (12 h).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.session.inactivite-admin-secondes=3",
        "backyard.session.inactivite-coureur-benevole-secondes=3"})
@Testcontainers
class SessionInactiviteEgaleIntegrationTest {

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
    RegistreTentativesConnexionEnMemoire registre;

    @Autowired
    PolitiqueSession politique;

    OutilsSession outils;

    @BeforeEach
    void preparer() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registre.vider();
        outils = new OutilsSession(new ApiHttp(port), jdbc, encodeur);
        outils.inserer("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN);
        outils.inserer("Léo", "BENEVOLE", MOT_DE_PASSE_BENEVOLE);
        outils.inserer("Alice", "COUREUR", MOT_DE_PASSE_COUREUR);
    }

    @Test
    @DisplayName("CA5 : avec des délais égaux et courts (3 s partout, égalité admise), COUREUR, BENEVOLE et ADMIN expirent tous après 6 s : le délai court s'applique aux quatre rôles par la politique, pas par un défaut du conteneur")
    void ca5_delai_pose_explicitement_pour_les_trois_roles() throws Exception {
        assertThat(politique).isEqualTo(new PolitiqueSession(Duration.ofSeconds(3), Duration.ofSeconds(3)));
        Session leo = outils.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session alice = outils.ouvrir("Alice", MOT_DE_PASSE_COUREUR);
        Session nadia = outils.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        assertThat(outils.lire(leo, "/api/comptes/moi").statusCode()).isEqualTo(200);

        patienter(5_500);

        assertThat(outils.lire(leo, "/api/comptes/moi").statusCode()).as("BENEVOLE").isEqualTo(401);
        assertThat(outils.lire(alice, "/api/comptes/moi").statusCode()).as("COUREUR").isEqualTo(401);
        assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode()).as("ADMIN").isEqualTo(401);
    }
}
