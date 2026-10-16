package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 4.3, hors profil e2e et sans Clock de test : CA5 (RG1, RG7, RG13, SEC-11). Le contexte est
 * celui de production : le seul bean Clock est l'horloge système, l'endpoint /api/test/horloge n'existe pas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class HorlogeHorsProfilIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/test/horloge";
    static final String CORPS_VALIDE = "{\"duree\":\"PT60M\"}";

    static JournalApplicatif journal;

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
    ApplicationContext contexte;

    final JsonMapper json = JsonMapper.builder().build();
    ApiHttp api;

    @BeforeAll
    static void capturer_le_journal() {
        journal = JournalApplicatif.demarrer();
    }

    @AfterAll
    static void liberer_le_journal() {
        journal.close();
    }

    @BeforeEach
    void preparer() {
        journal.rattacher(); // le démarrage du contexte réinitialise Logback
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registre.vider();
        api = new ApiHttp(port);
        initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON);
        inserer(jdbc, "Nadia", "ADMIN");
        inserer(jdbc, "Léo", "BENEVOLE");
        inserer(jdbc, "Alice", "COUREUR");
    }

    private void inserer(JdbcTemplate jdbc, String pseudo, String role) {
        jdbc.update("insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le) "
                        + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), pseudo, pseudo.toLowerCase(Locale.ROOT),
                encodeur.encode(MOT_DE_PASSE), role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
    }

    private Map<String, String> session(String pseudo, String motDePasse) throws Exception {
        ApiHttp.Jeton jeton = api.jetonValide();
        HttpResponse<String> connexion = api.requete("POST", "/api/connexion",
                Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie(), "X-XSRF-TOKEN", jeton.entete()), "application/json",
                "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}");
        assertThat(connexion.statusCode()).isEqualTo(200);
        String id = ApiHttp.valeurCookie(connexion, "JSESSIONID");
        return Map.of("Cookie", "JSESSIONID=" + id + "; XSRF-TOKEN=" + jeton.cookie(),
                "X-XSRF-TOKEN", jeton.entete());
    }

    private void verifierProbleme(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo(code);
    }

    @Test
    @DisplayName("CA5 : sans profil e2e ni Clock de test, aucun bean de réglage ni d'horloge décalée et le seul bean Clock est l'horloge système")
    void ca5_le_seul_bean_clock_est_l_horloge_systeme() throws Exception {
        assertThat(contexte.getEnvironment().getActiveProfiles()).doesNotContain("e2e");
        assertThat(contexte.getBeanNamesForType(Clock.class)).hasSize(1);
        assertThat(contexte.getBeanNamesForType(fr.backyard.tracker.comptes.domaine.ReglageHorloge.class)).isEmpty();
        assertThat(contexte.getBeanNamesForType(fr.backyard.tracker.comptes.infrastructure.HorlogeDecalee.class))
                .isEmpty();
        assertThat(contexte.getBeanNamesForType(fr.backyard.tracker.comptes.application.PiloterHorloge.class))
                .isEmpty();
        assertThat(contexte.getBeanNamesForType(fr.backyard.tracker.comptes.exposition.HorlogeController.class))
                .isEmpty();
        assertThat(contexte.getBeanNamesForType(
                fr.backyard.tracker.comptes.infrastructure.GardeFouProfilE2e.class)).isEmpty();

        Clock horloge = contexte.getBean(Clock.class);
        assertThat(horloge.getZone()).isEqualTo(ZoneOffset.UTC);
        for (int i = 0; i < 3; i++) {
            assertThat(Duration.between(Instant.now(), horloge.instant()).abs()).isLessThan(Duration.ofSeconds(2));
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("CA5 : Patron obtient 404 RESSOURCE_INTROUVABLE sur GET, POST /avance (avec CSRF) et DELETE de /api/test/horloge")
    void ca5_patron_obtient_404() throws Exception {
        Map<String, String> patron = session("Patron", MOT_DE_PASSE_PATRON);

        HttpResponse<String> lecture = api.requete("GET", CHEMIN, Map.of("Cookie", patron.get("Cookie")), null, null);
        HttpResponse<String> avance = api.requete("POST", CHEMIN + "/avance", patron, "application/json",
                CORPS_VALIDE);
        HttpResponse<String> reinit = api.requete("DELETE", CHEMIN, patron, null, null);

        verifierProbleme(lecture, 404, "RESSOURCE_INTROUVABLE");
        verifierProbleme(avance, 404, "RESSOURCE_INTROUVABLE");
        verifierProbleme(reinit, 404, "RESSOURCE_INTROUVABLE");
    }

    @Test
    @DisplayName("CA5 : Nadia, Léo et Alice obtiennent 403 ACCES_REFUSE sur les trois opérations, un anonyme 401 NON_AUTHENTIFIE sur GET")
    void ca5_autres_roles_403_et_anonyme_401() throws Exception {
        for (String pseudo : new String[] {"Nadia", "Léo", "Alice"}) {
            Map<String, String> session = session(pseudo, MOT_DE_PASSE);
            verifierProbleme(api.requete("GET", CHEMIN, Map.of("Cookie", session.get("Cookie")), null, null), 403,
                    "ACCES_REFUSE");
            verifierProbleme(api.requete("POST", CHEMIN + "/avance", session, "application/json", CORPS_VALIDE), 403,
                    "ACCES_REFUSE");
            verifierProbleme(api.requete("DELETE", CHEMIN, session, null, null), 403, "ACCES_REFUSE");
        }

        verifierProbleme(api.get(CHEMIN), 401, "NON_AUTHENTIFIE");
    }

    @Test
    @DisplayName("CA5 : aucune ligne de journal ne mentionne l'horloge pilotable, requêtes (le démarrage est couvert par CA10)")
    void ca5_aucune_ligne_de_journal_sur_l_horloge_pilotable() throws Exception {
        Map<String, String> patron = session("Patron", MOT_DE_PASSE_PATRON);
        api.requete("GET", CHEMIN, Map.of("Cookie", patron.get("Cookie")), null, null);
        api.requete("POST", CHEMIN + "/avance", patron, "application/json", CORPS_VALIDE);
        api.requete("DELETE", CHEMIN, patron, null, null);

        assertThat(journal.messagesEtExceptions()).isNotEmpty().allSatisfy(message -> assertThat(message)
                .doesNotContainIgnoringCase("horloge pilotable")
                .doesNotContain("Profil e2e actif")
                .doesNotContain("Horloge avancée")
                .doesNotContain("Horloge réinitialisée"));
    }
}
