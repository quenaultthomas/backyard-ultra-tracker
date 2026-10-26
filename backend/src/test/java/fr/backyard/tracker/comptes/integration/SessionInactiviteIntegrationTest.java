package fr.backyard.tracker.comptes.integration;

import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_ADMIN;
import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_ADMIN_NOUVEAU;
import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_BENEVOLE;
import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_COUREUR;
import static fr.backyard.tracker.comptes.integration.OutilsSession.MOT_DE_PASSE_PATRON;
import static fr.backyard.tracker.comptes.integration.OutilsSession.patienter;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.domaine.PolitiqueSession;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.OutilsSession.Session;
import java.net.http.HttpResponse;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Incrément 8.2 (SEC-16), CA4 à CA7 : délai d'inactivité de session par rôle, sur un vrai conteneur de servlets
 * (expiration réelle, pas de MockMvc). Administrateurs 4 s, coureurs et bénévoles 3 600 s. Les marges sont larges
 * (attentes de 6 s pour un délai de 4 s, requêtes glissantes toutes les 2 s) car le conteneur compte à la seconde.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=" + MOT_DE_PASSE_PATRON,
        "backyard.session.inactivite-admin-secondes=4",
        "backyard.session.inactivite-coureur-benevole-secondes=3600"})
@Testcontainers
class SessionInactiviteIntegrationTest {

    /** Attente qui dépasse nettement le délai de 4 s des administrateurs. */
    private static final long APRES_EXPIRATION = 6_000;

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
    PolitiqueSession politique;

    JdbcTemplate jdbc;
    ApiHttp api;
    OutilsSession outils;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registre.vider();
        api = new ApiHttp(port);
        outils = new OutilsSession(api, jdbc, encodeur);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        outils.inserer("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN);
        outils.inserer("Léo", "BENEVOLE", MOT_DE_PASSE_BENEVOLE);
        outils.inserer("Alice", "COUREUR", MOT_DE_PASSE_COUREUR);
    }

    @Test
    @DisplayName("CA4 : la politique de test est bien celle des propriétés (administrateurs 4 s, autres 3 600 s)")
    void ca4_la_politique_suit_les_proprietes() {
        assertThat(politique).isEqualTo(new PolitiqueSession(Duration.ofSeconds(4), Duration.ofSeconds(3600)));
    }

    @Test
    @DisplayName("CA4 : une session ADMIN ou ADMIN_MASTER inactive au-delà de son délai reçoit 401 NON_AUTHENTIFIE identique à l'absence de cookie, sans nouvelle session ni effet du POST, et une reconnexion fonctionne")
    void ca4_session_admin_expiree_apres_son_delai() throws Exception {
        Session nadia = outils.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        Session patron = outils.ouvrir("Patron", MOT_DE_PASSE_PATRON);

        assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(outils.lire(patron, "/api/comptes/moi").statusCode()).isEqualTo(200);

        patienter(APRES_EXPIRATION);

        for (Session session : new Session[] {nadia, patron}) {
            verifierRefusIdentiqueAAnonyme(session, "/api/comptes/moi");
            verifierRefusIdentiqueAAnonyme(session, "/api/administration/benevoles");
        }
        HttpResponse<String> anonymePost = postCreationBenevole(null, "Fantome");
        HttpResponse<String> expiree = postCreationBenevole(nadia, "Fantome");
        assertThat(anonymePost.statusCode()).isEqualTo(401);
        assertThat(expiree.statusCode()).isEqualTo(401);
        assertThat(code(expiree)).isEqualTo("NON_AUTHENTIFIE");
        assertThat(json.readTree(expiree.body())).isEqualTo(json.readTree(anonymePost.body()));
        assertThat(ApiHttp.valeurCookie(expiree, "JSESSIONID")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from compte where pseudo = 'Fantome'", Integer.class)).isZero();

        HttpResponse<String> reconnexion = outils.connecter("Nadia", MOT_DE_PASSE_ADMIN, null, api.jetonValide());
        assertThat(reconnexion.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reconnexion.body()).get("role").asString()).isEqualTo("ADMIN");
        Session nouvelle = new Session(ApiHttp.valeurCookie(reconnexion, "JSESSIONID"), "x");
        assertThat(nouvelle.id()).isNotEqualTo(nadia.id());
        assertThat(outils.lire(nouvelle, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA5 : après 6 s sans activité, COUREUR et BENEVOLE (délai 3 600 s) restent valides alors que l'ADMIN (4 s) est expiré")
    void ca5_coureur_et_benevole_restent_valides() throws Exception {
        Session leo = outils.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session alice = outils.ouvrir("Alice", MOT_DE_PASSE_COUREUR);
        Session nadia = outils.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);

        patienter(APRES_EXPIRATION);

        assertThat(outils.lire(leo, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(outils.lire(alice, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(outils.lire(leo, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("CA6 : la session est glissante, des requêtes toutes les 2 s sur 10 s (plus de deux fois le délai de 4 s) restent acceptées, un appel à 1 s d'inactivité repousse l'expiration, puis 6 s sans requête donnent 401")
    void ca6_session_glissante_sans_duree_absolue() throws Exception {
        Session nadia = outils.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);

        for (int i = 1; i <= 5; i++) {
            patienter(2_000);
            assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode()).as("requête %d à %d s", i, 2 * i)
                    .isEqualTo(200);
        }

        patienter(1_000);
        assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode()).as("à 1 s d'inactivité").isEqualTo(200);
        patienter(3_000);
        assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode())
                .as("3 s après l'appel qui a repoussé l'expiration").isEqualTo(200);

        patienter(APRES_EXPIRATION);
        HttpResponse<String> expiree = outils.lire(nadia, "/api/comptes/moi");
        assertThat(expiree.statusCode()).isEqualTo(401);
        assertThat(code(expiree)).isEqualTo("NON_AUTHENTIFIE");
    }

    @Test
    @DisplayName("CA7 : le changement de mot de passe régénère l'identifiant (ancien refusé) et la session garde le délai court du rôle")
    void ca7_changement_de_mot_de_passe_conserve_le_delai() throws Exception {
        Session nadia = outils.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);

        HttpResponse<String> changement = api.requete("PUT", "/api/comptes/moi/mot-de-passe", nadia.entetes(),
                "application/json", "{\"motDePasseActuel\":\"" + MOT_DE_PASSE_ADMIN + "\",\"nouveauMotDePasse\":\""
                        + MOT_DE_PASSE_ADMIN_NOUVEAU + "\"}");
        assertThat(changement.statusCode()).isEqualTo(204);
        String nouvelIdentifiant = ApiHttp.valeurCookie(changement, "JSESSIONID");
        assertThat(nouvelIdentifiant).isNotBlank().isNotEqualTo(nadia.id());
        Session apres = new Session(nouvelIdentifiant, nadia.xsrf());
        assertThat(outils.lire(apres, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode()).as("ancien identifiant").isEqualTo(401);

        patienter(APRES_EXPIRATION);

        assertThat(outils.lire(apres, "/api/comptes/moi").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("CA7 : une connexion présentant le cookie d'une ancienne session fermée donne une nouvelle session (autre identifiant, ancien refusé) avec le délai du rôle du Compte qui se connecte")
    void ca7_connexion_avec_cookie_d_une_session_precedente() throws Exception {
        // Une session anonyme n'existe pas (IF_REQUIRED, aucune requête anonyme n'en crée) : le cookie préexistant
        // le plus proche est celui d'une session précédente, fermée. Une session encore authentifiée donne 409.
        Session alice = outils.ouvrir("Alice", MOT_DE_PASSE_COUREUR);
        api.requete("POST", "/api/deconnexion", alice.entetes(), null, null);

        HttpResponse<String> reponse = outils.connecter("Nadia", MOT_DE_PASSE_ADMIN, alice, api.jetonValide());

        assertThat(reponse.statusCode()).isEqualTo(200);
        String nouvelIdentifiant = ApiHttp.valeurCookie(reponse, "JSESSIONID");
        assertThat(nouvelIdentifiant).isNotBlank().isNotEqualTo(alice.id());
        Session nadia = new Session(nouvelIdentifiant, "x");
        assertThat(outils.lire(alice, "/api/comptes/moi").statusCode()).as("ancien identifiant").isEqualTo(401);
        assertThat(json.readTree(outils.lire(nadia, "/api/comptes/moi").body()).get("role").asString())
                .isEqualTo("ADMIN");

        patienter(APRES_EXPIRATION);

        assertThat(outils.lire(nadia, "/api/comptes/moi").statusCode()).as("délai de 4 s des administrateurs")
                .isEqualTo(401);
    }

    // ------------------------------------------------------------------ outils

    private void verifierRefusIdentiqueAAnonyme(Session expiree, String chemin) throws Exception {
        HttpResponse<String> anonyme = outils.lireSansCookie(chemin);
        HttpResponse<String> reponse = outils.lire(expiree, chemin);

        assertThat(reponse.statusCode()).as(chemin).isEqualTo(401);
        assertThat(code(reponse)).as(chemin).isEqualTo("NON_AUTHENTIFIE");
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps).as(chemin).isEqualTo(json.readTree(anonyme.body()));
        assertThat(ApiHttp.valeurCookie(reponse, "JSESSIONID")).as(chemin).isNull();
    }

    private HttpResponse<String> postCreationBenevole(Session session, String pseudo) throws Exception {
        String corps = "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + MOT_DE_PASSE_BENEVOLE + "\"}";
        if (session == null) {
            ApiHttp.Jeton jeton = api.jetonValide();
            return api.requete("POST", "/api/administration/benevoles",
                    java.util.Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie(), "X-XSRF-TOKEN", jeton.entete()),
                    "application/json", corps);
        }
        return api.requete("POST", "/api/administration/benevoles", session.entetes(), "application/json", corps);
    }

    private String code(HttpResponse<String> reponse) throws Exception {
        return json.readTree(reponse.body()).get("code").asString();
    }
}
