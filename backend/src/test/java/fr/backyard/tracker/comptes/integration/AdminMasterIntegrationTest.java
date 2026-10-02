package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 1.4 : unicité de l'admin master en base (CA12), contrôle des rôles sur
 * /api/administration/** (CA19, CA20), création interdite (CA18), limitation de connexion (CA21) et
 * non-régression du rôle COUREUR (CA22). Les rôles ADMIN et BENEVOLE sont insérés directement en base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class AdminMasterIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String INSERTION = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le) "
            + "values (?, ?, ?, ?, ?, ?)";

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

    JdbcTemplate jdbc;
    ApiHttp api;
    final JsonMapper json = JsonMapper.builder().build();
    String empreinteCoureur;

    /** Cookies d'une session ouverte. */
    record Session(String id, String xsrf) {
        String cookie() {
            return "JSESSIONID=" + id + "; XSRF-TOKEN=" + xsrf;
        }
    }

    /** Base remise à zéro : l'admin master est recréé par le cas d'usage avec les propriétés du contexte. */
    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registre.vider();
        api = new ApiHttp(port);
        empreinteCoureur = encodeur.encode(MOT_DE_PASSE);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : un second ADMIN_MASTER échoue sur uk_compte_admin_master ; un ADMIN et plusieurs COUREUR s'insèrent")
    void ca12_index_unique_partiel_sur_admin_master() {
        assertThatThrownBy(() -> inserer("Autre", "ADMIN_MASTER"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_compte_admin_master");

        inserer("Admin1", "ADMIN");
        inserer("Admin2", "ADMIN");
        inserer("Coureur1", "COUREUR");
        inserer("Coureur2", "COUREUR");
        inserer("Coureur3", "COUREUR");

        assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN_MASTER'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForList("select indexdef from pg_indexes where indexname = 'uk_compte_admin_master'",
                String.class)).singleElement().asString().containsIgnoringCase("unique").containsIgnoringCase("where");
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : POST /api/comptes avec role ADMIN_MASTER crée un COUREUR (201) et la table garde un seul ADMIN_MASTER")
    void ca18_admin_master_non_creable_par_l_api() throws Exception {
        HttpResponse<String> reponse = api.postJson("/api/comptes",
                "{\"pseudo\":\"Bob12\",\"motDePasse\":\"" + MOT_DE_PASSE + "\",\"role\":\"ADMIN_MASTER\"}",
                api.jetonValide());

        assertThat(reponse.statusCode()).isEqualTo(201);
        assertThat(json.readTree(reponse.body()).get("role").asString()).isEqualTo("COUREUR");
        assertThat(jdbc.queryForObject("select role from compte where pseudo = 'Bob12'", String.class))
                .isEqualTo("COUREUR");
        assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN_MASTER'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select pseudo from compte where role = 'ADMIN_MASTER'", String.class))
                .isEqualTo("Patron");
    }

    // ---------------------------------------------------------------- CA19

    @Test
    @DisplayName("CA19 : GET /api/administration/acces, anonyme 401, coureur et bénévole 403, ADMIN et ADMIN_MASTER 204")
    void ca19_controle_des_roles_sur_l_endpoint_d_acces() throws Exception {
        inserer("Alice", "COUREUR");
        inserer("Benevole1", "BENEVOLE");
        inserer("Admin1", "ADMIN");

        assertErreur(api.get("/api/administration/acces"), 401, "NON_AUTHENTIFIE", "Authentification requise",
                "Vous devez être connecté.");
        assertThat(api.get("/api/administration/acces").headers().firstValue("www-authenticate")).isEmpty();

        for (String pseudo : List.of("Alice", "Benevole1")) {
            Session session = ouvrir(pseudo, MOT_DE_PASSE);
            assertErreur(acces(session), 403, "ACCES_REFUSE", "Accès refusé",
                    "Vous n'avez pas les droits nécessaires.");
        }
        for (Session session : List.of(ouvrir("Admin1", MOT_DE_PASSE), ouvrir("Patron", MOT_DE_PASSE_PATRON))) {
            HttpResponse<String> reponse = acces(session);
            assertThat(reponse.statusCode()).isEqualTo(204);
            assertThat(reponse.body()).isEmpty();
        }
    }

    // ---------------------------------------------------------------- CA20

    @Test
    @DisplayName("CA20 : chemin inexistant et POST sous /api/administration, anonyme 401, coureur 403, admin master 404 sur le chemin inexistant")
    void ca20_refus_avant_le_404() throws Exception {
        inserer("Alice", "COUREUR");

        assertErreur(api.get("/api/administration/inexistant"), 401, "NON_AUTHENTIFIE", "Authentification requise",
                "Vous devez être connecté.");
        assertErreur(api.postJson("/api/administration/acces", "{}", api.jetonValide()), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");

        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        assertErreur(api.requete("GET", "/api/administration/inexistant", Map.of("Cookie", alice.cookie()), null,
                null), 403, "ACCES_REFUSE", "Accès refusé", "Vous n'avez pas les droits nécessaires.");
        assertErreur(posterAdministration(alice), 403, "ACCES_REFUSE", "Accès refusé",
                "Vous n'avez pas les droits nécessaires.");

        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        HttpResponse<String> introuvable = api.requete("GET", "/api/administration/inexistant",
                Map.of("Cookie", patron.cookie()), null, null);
        assertErreur(introuvable, 404, "RESSOURCE_INTROUVABLE", "Introuvable", "La ressource demandée est introuvable.");
    }

    // ---------------------------------------------------------------- CA21

    @Test
    @DisplayName("CA21 : 5 échecs puis le bon mot de passe de l'admin master, 429 TENTATIVES_EXCESSIVES ; après déconnexion, l'accès est 401")
    void ca21_blocage_de_l_admin_master_et_deconnexion() throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> echec = connecter("Patron", "mauvais-mot-de-passe-1");
            assertThat(echec.statusCode()).as("échec " + i).isEqualTo(401);
        }
        HttpResponse<String> bloquee = connecter("Patron", MOT_DE_PASSE_PATRON);
        assertThat(bloquee.statusCode()).isEqualTo(429);
        assertThat(json.readTree(bloquee.body()).get("code").asString()).isEqualTo("TENTATIVES_EXCESSIVES");

        registre.vider();
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        assertThat(acces(patron).statusCode()).isEqualTo(204);
        HttpResponse<String> deconnexion = api.requete("POST", "/api/deconnexion",
                Map.of("Cookie", patron.cookie(), "X-XSRF-TOKEN", patron.xsrf()), null, null);
        assertThat(deconnexion.statusCode()).isEqualTo(204);

        assertErreur(acces(patron), 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
    }

    // ---------------------------------------------------------------- CA22

    @Test
    @DisplayName("CA22 : un coureur et l'admin master voient leur rôle (COUREUR, ADMIN_MASTER) dans la connexion et GET /api/comptes/moi")
    void ca22_role_dans_connexion_et_comptes_moi() throws Exception {
        inserer("Alice", "COUREUR");

        for (String[] cas : new String[][] {{"alice", MOT_DE_PASSE, "Alice", "COUREUR"},
                {"patron", MOT_DE_PASSE_PATRON, "Patron", "ADMIN_MASTER"}}) {
            HttpResponse<String> connexion = connecter(cas[0], cas[1]);
            assertThat(connexion.statusCode()).isEqualTo(200);
            JsonNode corps = json.readTree(connexion.body());
            assertThat(corps.get("pseudo").asString()).isEqualTo(cas[2]);
            assertThat(corps.get("role").asString()).isEqualTo(cas[3]);

            String idSession = ApiHttp.valeurCookie(connexion, "JSESSIONID");
            JsonNode moi = json.readTree(api.requete("GET", "/api/comptes/moi",
                    Map.of("Cookie", "JSESSIONID=" + idSession), null, null).body());
            assertThat(moi.get("role").asString()).isEqualTo(cas[3]);
        }
    }

    // ---------------------------------------------------------------- utilitaires

    private void inserer(String pseudo, String role) {
        jdbc.update(INSERTION, UUID.randomUUID(), pseudo, pseudo.toLowerCase(java.util.Locale.ROOT),
                empreinteCoureur, role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
    }

    private HttpResponse<String> connecter(String pseudo, String motDePasse) throws Exception {
        return api.postJson("/api/connexion",
                "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}", api.jetonValide());
    }

    private Session ouvrir(String pseudo, String motDePasse) throws Exception {
        Jeton jeton = api.jetonValide();
        HttpResponse<String> reponse = api.postJson("/api/connexion",
                "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}", jeton);
        assertThat(reponse.statusCode()).as("connexion de " + pseudo).isEqualTo(200);
        return new Session(ApiHttp.valeurCookie(reponse, "JSESSIONID"), jeton.cookie());
    }

    private HttpResponse<String> acces(Session session) throws Exception {
        return api.requete("GET", "/api/administration/acces", Map.of("Cookie", "JSESSIONID=" + session.id()), null,
                null);
    }

    private HttpResponse<String> posterAdministration(Session session) throws Exception {
        return api.requete("POST", "/api/administration/acces",
                Map.of("Cookie", session.cookie(), "X-XSRF-TOKEN", session.xsrf()), "application/json", "{}");
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code, String titre, String detail)
            throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo(code);
        assertThat(corps.get("title").asString()).isEqualTo(titre);
        assertThat(corps.get("status").asInt()).isEqualTo(statut);
        assertThat(corps.get("detail").asString()).isEqualTo(detail);
    }
}
