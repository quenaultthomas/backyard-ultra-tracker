package fr.backyard.tracker.comptes.integration;

import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_ADMIN;
import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_PATRON;
import static fr.backyard.tracker.comptes.integration.OutilsSession.attendre;
import static fr.backyard.tracker.comptes.integration.OutilsSession.patienter;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreSessionsEnMemoire;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.OutilsSession.Session;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Incrément 8.2 (SEC-16), CA9 : non-régression avec des sessions expirées (registre, désactivation 8.1) et
 * documentation. Administrateurs 3 s. Le test du registre passe en premier et seul sur ce contexte, pour que le
 * nombre de sessions suivies soit déterministe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=" + MOT_DE_PASSE_PATRON,
        "backyard.session.inactivite-admin-secondes=3",
        "backyard.session.inactivite-coureur-benevole-secondes=3600"})
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionExpireeNonRegressionIntegrationTest {

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
    RegistreTentativesConnexionEnMemoire tentatives;

    @Autowired
    RegistreSessionsEnMemoire sessions;

    JdbcTemplate jdbc;
    ApiHttp api;
    OutilsSession outils;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        tentatives.vider();
        api = new ApiHttp(port);
        outils = new OutilsSession(api, jdbc, encodeur);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
    }

    @Test
    @Order(1)
    @DisplayName("CA9 : le registre des sessions ne contient plus la session ADMIN expirée une fois détruite par le conteneur")
    void ca9_le_registre_oublie_la_session_expiree() throws Exception {
        outils.inserer("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN);
        int avant = sessions.taille();
        Session nadia = outils.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        assertThat(sessions.taille()).isEqualTo(avant + 1);

        patienter(5_000);

        assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(401);
        attendre("la session expirée sort du registre", () -> sessions.taille() == avant);
    }

    @Test
    @Order(2)
    @DisplayName("CA9 : la désactivation d'un ADMIN par Patron reste 204 quand l'une de ses sessions a expiré, ferme sa session active et interdit la reconnexion")
    void ca9_desactivation_avec_une_session_expiree() throws Exception {
        UUID nadiaId = outils.inserer("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN);
        // Un autre admin actif : sans lui, Nadia serait le dernier administrateur actif, non désactivable (8.1, 409).
        outils.inserer("Zoé", "ADMIN", MOT_DE_PASSE_ADMIN);
        Session expiree = outils.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        patienter(5_000);
        Session active = outils.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        Session patron = outils.ouvrir("Patron", MOT_DE_PASSE_PATRON);

        HttpResponse<String> reponse = api.requete("POST", "/api/administration/admins/" + nadiaId + "/desactivation",
                patron.entetes(), null, null);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        assertThat(jdbc.queryForObject("select actif from compte where id = ?", Boolean.class, nadiaId)).isFalse();
        assertThat(outils.lire(active, "/api/comptes/moi").statusCode()).as("session active fermée").isEqualTo(401);
        assertThat(outils.lire(expiree, "/api/comptes/moi").statusCode()).as("session expirée").isEqualTo(401);
        HttpResponse<String> reconnexion = outils.connecter("Nadia", MOT_DE_PASSE_ADMIN, null, api.jetonValide());
        assertThat(reconnexion.statusCode()).isEqualTo(401);
        assertThat(json.readTree(reconnexion.body()).get("code").asString()).isEqualTo("IDENTIFIANTS_INVALIDES");
    }

    @Test
    @DisplayName("CA9 : le référentiel contient la ligne SEC-16, application.yml garde le délai de 12 h du conteneur avec son commentaire et les deux propriétés par défaut 7200 / 43200")
    void ca9_referentiel_et_application_yml() throws Exception {
        String referentiel = Files.readString(Path.of("..", "docs", "audits", "referentiel.md"));
        String yaml = Files.readString(Path.of("src", "main", "resources", "application.yml"));

        assertThat(referentiel).contains("| SEC-16 |").contains("| P2 | 8.2 |");
        assertThat(yaml).contains("timeout: 12h").contains("PolitiqueSession")
                .contains("inactivite-admin-secondes: ${SESSION_INACTIVITE_ADMIN_SECONDES:7200}")
                .contains("inactivite-coureur-benevole-secondes: ${SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES:43200}");
    }

    @Test
    @DisplayName("CA9 : .env.example, docker-compose.yml et docs/deploiement.md mentionnent les deux variables avec leurs défauts 7200 / 43200")
    void ca9_documentation_et_deploiement() throws Exception {
        String env = Files.readString(Path.of("..", ".env.example"));
        String compose = Files.readString(Path.of("..", "docker-compose.yml"));
        String guide = Files.readString(Path.of("..", "docs", "deploiement.md"));

        assertThat(env).contains("SESSION_INACTIVITE_ADMIN_SECONDES=7200")
                .contains("SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES=43200");
        assertThat(compose).contains("${SESSION_INACTIVITE_ADMIN_SECONDES:-7200}")
                .contains("${SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES:-43200}");
        assertThat(guide).contains("`SESSION_INACTIVITE_ADMIN_SECONDES`", "`SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES`",
                "`7200`", "`43200`", "Politique de session", "backyard.session.inactivite-admin-secondes");
    }
}
