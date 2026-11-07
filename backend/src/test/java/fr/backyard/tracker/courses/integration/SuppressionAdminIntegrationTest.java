package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.ConsulterCompteConnecte;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.CompteJpaAdapter;
import fr.backyard.tracker.comptes.infrastructure.RegistreSessionsEnMemoire;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 9.4 : suppression d'un admin, DELETE /api/administration/admins/{id} (CA4 à CA11).
 * Les Comptes sont insérés en base ; l'action testée passe par l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuppressionAdminIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE_ALICE = "un-mot-de-passe-12";
    static final String ADMINS = "/api/administration/admins";
    static final String BENEVOLES = "/api/administration/benevoles";
    static final Instant MAINTENANT = Instant.parse("2026-10-09T14:03:27Z");
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String CSRF_DETAIL = "Jeton CSRF absent ou invalide.";
    static final String INTROUVABLE_DETAIL = "Ce compte est introuvable.";
    static final String DETAIL_MASTER = "L'administrateur principal ne peut pas être supprimé.";
    static final String DETAIL_DERNIER = "Impossible de supprimer le dernier administrateur actif.";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, "
            + "demarree_le) values (?, ?, ?, ?, 400, 60, 0, 10, 5, ?)";

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

    @MockitoSpyBean
    CompteJpaAdapter depotComptes;

    @MockitoSpyBean
    RegistreSessionsEnMemoire registreSessions;

    @MockitoSpyBean
    ConsulterCompteConnecte consulterCompteConnecte;

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    JournalApplicatif journal;
    Session patron;
    Session nadia;
    UUID idPatron;
    UUID idNadia;
    UUID idPaul;
    UUID idLeo;
    UUID idAlice;
    int compteur;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(MAINTENANT);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        idPatron = jdbc.queryForObject("select id from compte where role = 'ADMIN_MASTER'", UUID.class);
        idNadia = insererCompte("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN, 0);
        idPaul = insererCompte("Paul", "ADMIN", MOT_DE_PASSE_ADMIN, 1);
        idLeo = insererCompte("Léo", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, 2);
        idAlice = insererCompte("Alice", "COUREUR", MOT_DE_PASSE_ALICE, 4);
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        journal = JournalApplicatif.demarrer().niveau("fr.backyard.tracker", Level.DEBUG);
    }

    @AfterEach
    void nettoyer() {
        journal.fermer();
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Patron supprime Paul (Nadia active) : 204 sans corps, Paul absent de compte et de la liste, Nadia, Patron et Léo inchangés")
    void ca4_suppression_nominale() throws Exception {
        Map<String, Object> nadiaAvant = jdbc.queryForMap("select * from compte where id = ?", idNadia);
        Map<String, Object> patronAvant = jdbc.queryForMap("select * from compte where id = ?", idPatron);
        Map<String, Object> leoAvant = jdbc.queryForMap("select * from compte where id = ?", idLeo);

        HttpResponse<String> reponse = supprimer(patron, idPaul);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        assertThat(existe(idPaul)).isFalse();
        assertThat(jdbc.queryForMap("select * from compte where id = ?", idNadia)).isEqualTo(nadiaAvant);
        assertThat(jdbc.queryForMap("select * from compte where id = ?", idPatron)).isEqualTo(patronAvant);
        assertThat(jdbc.queryForMap("select * from compte where id = ?", idLeo)).isEqualTo(leoAvant);
        JsonNode liste = json.readTree(lire(patron, ADMINS).body());
        assertThat(pseudos(liste)).containsExactly("Nadia");
    }

    @Test
    @DisplayName("CA4 : après suppression, la connexion de Paul donne le même 401 IDENTIFIANTS_INVALIDES qu'un pseudo inconnu ; POST admins avec Paul puis PAUL : 201, nouvel identifiant, actif, connexion avec le nouveau mot de passe")
    void ca4_connexion_refusee_et_pseudo_libere() throws Exception {
        assertThat(supprimer(patron, idPaul).statusCode()).isEqualTo(204);
        registre.vider();

        HttpResponse<String> supprime = connexion("Paul", MOT_DE_PASSE_ADMIN);
        HttpResponse<String> inconnu = connexion("Inconnu", MOT_DE_PASSE_ADMIN);
        assertThat(supprime.statusCode()).isEqualTo(401);
        assertThat(json.readTree(supprime.body()).get("code").asString()).isEqualTo("IDENTIFIANTS_INVALIDES");
        assertThat(supprime.body()).isEqualTo(inconnu.body());

        UUID precedent = idPaul;
        for (String variante : List.of("Paul", "PAUL")) {
            HttpResponse<String> creation = api.requete("POST", ADMINS, patron.entetes(), "application/json",
                    "{\"pseudo\":\"" + variante + "\",\"motDePasse\":\"mot-de-passe-nouveau-1\"}");
            assertThat(creation.statusCode()).as(variante).isEqualTo(201);
            JsonNode corps = json.readTree(creation.body());
            assertThat(corps.get("id").asString()).isNotEqualTo(precedent.toString());
            assertThat(corps.get("actif").asBoolean()).isTrue();
            UUID nouveau = UUID.fromString(corps.get("id").asString());
            registre.vider();
            assertThat(connexion(variante, "mot-de-passe-nouveau-1").statusCode()).isEqualTo(200);
            assertThat(connexion(variante, MOT_DE_PASSE_ADMIN).statusCode()).isEqualTo(401);
            if (variante.equals("Paul")) {
                assertThat(supprimer(patron, nouveau).statusCode()).isEqualTo(204);
                precedent = nouveau;
            }
        }
    }

    @Test
    @DisplayName("CA4 : Paul désactivé (8.1) avec Nadia active : DELETE 204 sans réactivation")
    void ca4_admin_desactive_supprimable() throws Exception {
        assertThat(api.requete("POST", ADMINS + "/" + idPaul + "/desactivation", patron.entetes(), null, null)
                .statusCode()).isEqualTo(204);

        HttpResponse<String> reponse = supprimer(patron, idPaul);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(existe(idPaul)).isFalse();
        assertThat(jdbc.queryForObject("select actif from compte where id = ?", Boolean.class, idNadia)).isTrue();
    }

    @Test
    @DisplayName("CA4 : seul ADMIN désactivé (Nadia retirée en base) : DELETE 204 ; Paul désactivé avec d'autres désactivés : 204")
    void ca4_seul_admin_desactive_supprimable() throws Exception {
        jdbc.update("delete from compte where id = ?", idNadia);
        jdbc.update("update compte set actif = false where id = ?", idPaul);

        HttpResponse<String> reponse = supprimer(patron, idPaul);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(existe(idPaul)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN'", Integer.class)).isZero();

        UUID a = insererCompte("InactifA", "ADMIN", MOT_DE_PASSE_ADMIN, 40);
        UUID b = insererCompte("InactifB", "ADMIN", MOT_DE_PASSE_ADMIN, 41);
        jdbc.update("update compte set actif = false where id in (?, ?)", a, b);
        assertThat(supprimer(patron, a).statusCode()).isEqualTo(204);
        assertThat(existe(b)).isTrue();
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : Patron vise Patron : 409 ADMIN_MASTER_NON_SUPPRIMABLE au corps exact du contrat, base et session inchangées")
    void ca5_admin_master_non_supprimable() throws Exception {
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> reponse = supprimer(patron, idPatron);

        assertProbleme(reponse, 409, "Conflit", "ADMIN_MASTER_NON_SUPPRIMABLE", DETAIL_MASTER);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("type").asString()).isEqualTo("about:blank");
        assertThat(corps.get("instance").asString()).isEqualTo(ADMINS + "/" + idPatron);
        assertThat(reponse.body()).doesNotContain("Patron");
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(patron, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA5 : Nadia seule ADMIN active : DELETE Nadia 409 DERNIER_ADMIN_ACTIF (detail propre à la suppression) ; la désactivation garde son détail « désactiver »")
    void ca5_dernier_admin_actif() throws Exception {
        jdbc.update("delete from compte where id = ?", idPaul);
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> reponse = supprimer(patron, idNadia);

        assertProbleme(reponse, 409, "Conflit", "DERNIER_ADMIN_ACTIF", DETAIL_DERNIER);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("type").asString()).isEqualTo("about:blank");
        assertThat(corps.get("instance").asString()).isEqualTo(ADMINS + "/" + idNadia);
        assertThat(reponse.body()).doesNotContain("Nadia");
        assertThat(corps.get("detail").asString()).doesNotContain(idNadia.toString());
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);

        HttpResponse<String> desactivation = api.requete("POST", ADMINS + "/" + idNadia + "/desactivation",
                patron.entetes(), null, null);
        assertProbleme(desactivation, 409, "Conflit", "DERNIER_ADMIN_ACTIF",
                "Impossible de désactiver le dernier administrateur actif.");
        assertThat(etatBase()).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA5 : Nadia active et Paul désactivé : DELETE Nadia 409 puis DELETE Paul 204")
    void ca5_admin_inactif_ne_compte_pas() throws Exception {
        jdbc.update("update compte set actif = false where id = ?", idPaul);

        assertProbleme(supprimer(patron, idNadia), 409, "Conflit", "DERNIER_ADMIN_ACTIF", DETAIL_DERNIER);
        assertThat(existe(idNadia)).isTrue();
        assertThat(supprimer(patron, idPaul).statusCode()).isEqualTo(204);
        assertThat(existe(idPaul)).isFalse();
    }

    @Test
    @DisplayName("CA5 : Nadia et Paul actifs : DELETE Paul 204 puis DELETE Nadia 409 ; sessions de Nadia intactes")
    void ca5_deux_admins_actifs_puis_dernier() throws Exception {
        assertThat(supprimer(patron, idPaul).statusCode()).isEqualTo(204);

        HttpResponse<String> reponse = supprimer(patron, idNadia);

        assertProbleme(reponse, 409, "Conflit", "DERNIER_ADMIN_ACTIF", DETAIL_DERNIER);
        assertThat(existe(idNadia)).isTrue();
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : anonyme 401 NON_AUTHENTIFIE ; Nadia, Léo, Alice 403 ACCES_REFUSE pour une cible existante comme inconnue (même réponse) ; base inchangée")
    void ca6_securite_roles() throws Exception {
        Session leo = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session alice = api.ouvrir("Alice", MOT_DE_PASSE_ALICE);
        List<Map<String, Object>> avant = etatBase();
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (UUID cible : List.of(idPaul, UUID.fromString(ID_INCONNU))) {
            assertProbleme(api.requete("DELETE", ADMINS + "/" + cible, anonyme, null, null), 401,
                    "Authentification requise", "NON_AUTHENTIFIE", "Vous devez être connecté.");
        }
        for (Session interdit : List.of(nadia, leo, alice)) {
            HttpResponse<String> connue = supprimer(interdit, idPaul);
            HttpResponse<String> inconnue = supprimer(interdit, UUID.fromString(ID_INCONNU));
            HttpResponse<String> soiMeme = supprimer(interdit, interdit == nadia ? idNadia : idPaul);
            assertProbleme(connue, 403, "Accès refusé", "ACCES_REFUSE", ACCES_REFUSE_DETAIL);
            assertThat(sansInstance(inconnue)).isEqualTo(sansInstance(connue));
            assertThat(sansInstance(soiMeme)).isEqualTo(sansInstance(connue));
        }
        assertThat(etatBase()).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA6 : sans jeton CSRF ou avec un jeton différent, 403 CSRF_INVALIDE et base inchangée")
    void ca6_csrf_absent_ou_invalide() throws Exception {
        List<Map<String, Object>> avant = etatBase();
        HttpResponse<String> sansEntete = api.requete("DELETE", ADMINS + "/" + idPaul,
                Map.of("Cookie", "JSESSIONID=" + patron.id() + "; XSRF-TOKEN=" + patron.xsrf()), null, null);
        assertProbleme(sansEntete, 403, "Accès refusé", "CSRF_INVALIDE", CSRF_DETAIL);
        HttpResponse<String> different = api.requete("DELETE", ADMINS + "/" + idPaul,
                Map.of("Cookie", "JSESSIONID=" + patron.id() + "; XSRF-TOKEN=" + patron.xsrf(),
                        "X-XSRF-TOKEN", "autre-valeur"), null, null);
        assertProbleme(different, 403, "Accès refusé", "CSRF_INVALIDE", CSRF_DETAIL);
        assertThat(etatBase()).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA6 : identifiant inconnu, mal formé, bénévole ou coureuse : 404 COMPTE_INTROUVABLE de corps identique ; second DELETE sur Paul : 404 ; rien n'est modifié")
    void ca6_cibles_introuvables_et_non_idempotence() throws Exception {
        List<Map<String, Object>> avant = etatBase();
        JsonNode reference = null;
        for (String cible : List.of(ID_INCONNU, "pas-un-uuid", idLeo.toString(), idAlice.toString())) {
            HttpResponse<String> reponse = api.requete("DELETE", ADMINS + "/" + cible, patron.entetes(), null, null);
            assertProbleme(reponse, 404, "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
            JsonNode corps = sansInstance(reponse);
            reference = reference == null ? corps : reference;
            assertThat(corps).isEqualTo(reference);
        }
        assertThat(etatBase()).isEqualTo(avant);

        assertThat(supprimer(patron, idPaul).statusCode()).isEqualTo(204);
        assertProbleme(supprimer(patron, idPaul), 404, "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
    }

    @Test
    @DisplayName("CA6 : PUT et PATCH sur /admins/{id} : 404 ou 405 ; DELETE /benevoles/{Nadia} et /benevoles/{Patron} par Patron : 404 ; rien n'est modifié")
    void ca6_autres_methodes_et_route_benevoles() throws Exception {
        List<Map<String, Object>> avant = etatBase();
        for (String methode : List.of("PUT", "PATCH")) {
            HttpResponse<String> reponse = api.requete(methode, ADMINS + "/" + idPaul, patron.entetes(),
                    "application/json", "{\"pseudo\":\"Pirate\"}");
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        for (UUID cible : List.of(idNadia, idPatron)) {
            assertProbleme(api.requete("DELETE", BENEVOLES + "/" + cible, patron.entetes(), null, null), 404,
                    "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
        }
        assertThat(etatBase()).isEqualTo(avant);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : les deux sessions de Paul sont fermées (401 NON_AUTHENTIFIE) ; celles de Nadia et de Patron restent valides ; la fermeture a lieu après validation (Compte déjà absent)")
    void ca7_fermeture_de_toutes_les_sessions() throws Exception {
        Session s1 = api.ouvrir("Paul", MOT_DE_PASSE_ADMIN);
        Session s2 = api.ouvrir("Paul", MOT_DE_PASSE_ADMIN);
        assertThat(lire(s1, "/api/comptes/moi").statusCode()).isEqualTo(200);
        AtomicReference<Boolean> absentALaFermeture = new AtomicReference<>();
        doAnswer(invocation -> {
            absentALaFermeture.set(horsTransaction()
                    .queryForObject("select count(*) from compte where id = ?", Integer.class, idPaul) == 0);
            return invocation.callRealMethod();
        }).when(registreSessions).invaliderToutesLesSessions(idPaul);

        assertThat(supprimer(patron, idPaul).statusCode()).isEqualTo(204);

        assertThat(absentALaFermeture.get()).isTrue();
        for (Session fermee : List.of(s1, s2)) {
            assertProbleme(lire(fermee, "/api/comptes/moi"), 401, "Authentification requise", "NON_AUTHENTIFIE",
                    "Vous devez être connecté.");
        }
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(patron, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA7 : une suppression annulée par une panne à supprimer laisse les sessions valides et Paul en base (500 ERREUR_INTERNE)")
    void ca7_panne_pendant_la_suppression() throws Exception {
        Session s1 = api.ouvrir("Paul", MOT_DE_PASSE_ADMIN);
        Session s2 = api.ouvrir("Paul", MOT_DE_PASSE_ADMIN);
        doThrow(new IllegalStateException("panne simulée secret-technique")).when(depotComptes).supprimer(any());

        HttpResponse<String> reponse = supprimer(patron, idPaul);

        assertThat(reponse.statusCode()).isEqualTo(500);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("secret-technique");
        assertThat(existe(idPaul)).isTrue();
        for (Session session : List.of(s1, s2)) {
            assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("CA7 : une connexion finalisée pendant la suppression n'aboutit à aucune session valide : 401 IDENTIFIANTS_INVALIDES")
    void ca7_connexion_concurrente_d_une_suppression() throws Exception {
        doAnswer(invocation -> {
            horsTransaction().update("delete from compte where id = ?", idPaul);
            return invocation.callRealMethod();
        }).when(consulterCompteConnecte).estConnectable(idPaul);

        HttpResponse<String> reponse = connexion("Paul", MOT_DE_PASSE_ADMIN);

        assertProbleme(reponse, 401, "Authentification échouée", "IDENTIFIANTS_INVALIDES",
                "Pseudo ou mot de passe incorrect.");
        for (String identifiant : cookiesDeSession(reponse)) {
            assertThat(lire(new Session(identifiant, "x"), "/api/comptes/moi").statusCode()).isEqualTo(401);
        }
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : après 5 échecs de connexion (429 TENTATIVES_EXCESSIVES), la suppression puis la recréation du même pseudo remettent le compteur à zéro : la connexion du nouveau Compte réussit")
    void ca8_compteur_d_echecs_remis_a_zero() throws Exception {
        UUID jetable = insererCompte("Jetable", "ADMIN", MOT_DE_PASSE_ADMIN, 20);
        registre.vider();
        for (int i = 0; i < 5; i++) {
            assertThat(connexion("Jetable", "mauvais-mot-de-passe-1").statusCode()).as("échec " + i).isEqualTo(401);
        }
        HttpResponse<String> bloque = connexion("Jetable", MOT_DE_PASSE_ADMIN);
        assertThat(bloque.statusCode()).isEqualTo(429);
        assertThat(json.readTree(bloque.body()).get("code").asString()).isEqualTo("TENTATIVES_EXCESSIVES");

        assertThat(supprimer(patron, jetable).statusCode()).isEqualTo(204);
        HttpResponse<String> creation = api.requete("POST", ADMINS, patron.entetes(), "application/json",
                "{\"pseudo\":\"Jetable\",\"motDePasse\":\"mot-de-passe-jetable-2\"}");
        assertThat(creation.statusCode()).isEqualTo(201);

        assertThat(connexion("Jetable", "mot-de-passe-jetable-2").statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 (a) : deux DELETE simultanés de Paul : exactement un 204 et un 404, jamais de 500 (20 répétitions)")
    void ca9_deux_suppressions_simultanees() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = nouvelAdmin();
            CountDownLatch depart = new CountDownLatch(1);
            Callable<HttpResponse<String>> action = () -> {
                depart.await();
                return supprimer(patron, cible);
            };

            List<HttpResponse<String>> reponses = enParallele(depart, action, action);

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(204, 404);
            assertThat(existe(cible)).isFalse();
        }
    }

    @Test
    @DisplayName("CA9 (b) : DELETE Nadia et DELETE Paul simultanés (les deux seuls ADMIN actifs) : un 204 et un 409 DERNIER_ADMIN_ACTIF, au moins un ADMIN actif à la fin (20 répétitions)")
    void ca9_suppression_des_deux_derniers_admins() throws Exception {
        for (int i = 0; i < 20; i++) {
            jdbc.update("delete from compte where role = 'ADMIN'");
            UUID premier = insererCompte("Premier" + i, "ADMIN", MOT_DE_PASSE_ADMIN, 50);
            UUID second = insererCompte("Second" + i, "ADMIN", MOT_DE_PASSE_ADMIN, 51);
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return supprimer(patron, premier);
                    },
                    () -> {
                        depart.await();
                        return supprimer(patron, second);
                    });

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(204, 409);
            HttpResponse<String> refus = reponses.get(0).statusCode() == 409 ? reponses.get(0) : reponses.get(1);
            assertThat(json.readTree(refus.body()).get("code").asString()).isEqualTo("DERNIER_ADMIN_ACTIF");
            assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN' and actif",
                    Integer.class)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CA9 (c) : DELETE Paul et désactivation de Nadia simultanés : un seul effet, un ADMIN actif subsiste, l'autre appel 409 ou 404, jamais de 500 (20 répétitions)")
    void ca9_suppression_et_desactivation_simultanees() throws Exception {
        for (int i = 0; i < 20; i++) {
            jdbc.update("delete from compte where role = 'ADMIN'");
            UUID premier = insererCompte("Premier" + i, "ADMIN", MOT_DE_PASSE_ADMIN, 50);
            UUID second = insererCompte("Second" + i, "ADMIN", MOT_DE_PASSE_ADMIN, 51);
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return supprimer(patron, premier);
                    },
                    () -> {
                        depart.await();
                        return api.requete("POST", ADMINS + "/" + second + "/desactivation", patron.entetes(), null,
                                null);
                    });

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(204, 409);
            assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN' and actif",
                    Integer.class)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CA9 (c) : DELETE et désactivation simultanés de la même cible (Nadia active) : DELETE 204, désactivation 204 ou 404, jamais de 500 (20 répétitions)")
    void ca9_suppression_et_desactivation_de_la_meme_cible() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = nouvelAdmin();
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return supprimer(patron, cible);
                    },
                    () -> {
                        depart.await();
                        return api.requete("POST", ADMINS + "/" + cible + "/desactivation", patron.entetes(), null,
                                null);
                    });

            assertThat(reponses.get(0).statusCode()).as("suppression " + i).isEqualTo(204);
            assertThat(reponses.get(1).statusCode()).as("désactivation " + i).isIn(204, 404);
            assertThat(existe(cible)).isFalse();
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : succès, refus 409 puis 404 : une ligne « Admin supprimé », une ligne « Suppression refusée » (motif DERNIER_ADMIN_ACTIF), aucune ligne pour le 404 ; ni journal ni réponse avec pseudo ni mot de passe")
    void ca10_journal() throws Exception {
        HttpResponse<String> succes = supprimer(patron, idPaul);
        HttpResponse<String> refus = supprimer(patron, idNadia);
        HttpResponse<String> introuvable = supprimer(patron, idPaul);

        assertThat(succes.statusCode()).isEqualTo(204);
        assertThat(refus.statusCode()).isEqualTo(409);
        assertThat(introuvable.statusCode()).isEqualTo(404);
        assertThat(lignes("Admin supprimé")).containsExactly("Admin supprimé (compte " + idPaul + " par "
                + idPatron + ")");
        assertThat(lignes("Suppression refusée")).containsExactly("Suppression refusée (compte " + idNadia
                + ", motif DERNIER_ADMIN_ACTIF)");
        assertThat(refus.body()).doesNotContain("Nadia").doesNotContain(MOT_DE_PASSE_ADMIN);
        assertThat(introuvable.body()).doesNotContain("Paul").doesNotContain(MOT_DE_PASSE_ADMIN);
        for (ILoggingEvent evenement : journal.evenements()) {
            String texte = evenement.getFormattedMessage() + " " + evenement.getMDCPropertyMap()
                    + (evenement.getThrowableProxy() == null ? "" : evenement.getThrowableProxy().getMessage());
            assertThat(texte).as(evenement.getLevel() + " " + evenement.getLoggerName())
                    .doesNotContain(MOT_DE_PASSE_BENEVOLE).doesNotContain(MOT_DE_PASSE_ADMIN)
                    .doesNotContain(MOT_DE_PASSE_PATRON);
            if (evenement.getLevel().isGreaterOrEqual(Level.INFO)
                    && evenement.getLoggerName().startsWith("fr.backyard.tracker")) {
                assertThat(texte).as("INFO+ " + evenement.getLoggerName()).doesNotContain("Paul")
                        .doesNotContain("Nadia").doesNotContain("Alice").doesNotContain("Léo");
            }
        }
    }

    @Test
    @DisplayName("CA10 : refus de l'admin master : une ligne « Suppression refusée » de motif ADMIN_MASTER_NON_SUPPRIMABLE ; 403 et 404 ne produisent aucune ligne de ce cas d'usage")
    void ca10_refus_admin_master_et_aucune_ligne_pour_403_et_404() throws Exception {
        assertThat(supprimer(patron, idPatron).statusCode()).isEqualTo(409);
        assertThat(lignes("Suppression refusée")).containsExactly("Suppression refusée (compte " + idPatron
                + ", motif ADMIN_MASTER_NON_SUPPRIMABLE)");
        int marque = journal.marque();

        assertThat(supprimer(nadia, idPaul).statusCode()).isEqualTo(403);
        assertThat(supprimer(patron, idLeo).statusCode()).isEqualTo(404);
        assertThat(supprimer(patron, UUID.fromString(ID_INCONNU)).statusCode()).isEqualTo(404);

        assertThat(journal.applicatifsDepuis(marque)).filteredOn(e -> e.getLevel() == Level.INFO)
                .noneMatch(e -> e.getFormattedMessage().startsWith("Admin supprimé")
                        || e.getFormattedMessage().startsWith("Suppression refusée"));
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : aucun nouveau changeset (0002 à 0012-photo-compte) ; la suppression ne modifie ni affectation ni inscription ; démarrage sur schéma validé")
    void ca11_schema_inchange_et_donnees_courses_intactes() throws Exception {
        UUID course = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, course, "Course CA11", Date.valueOf("2026-10-09"), "EN_PREPARATION", null);
        jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", course, idLeo);
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, 1, ?, 'EN_COURSE')", UUID.randomUUID(), course, idAlice,
                UUID.randomUUID().toString());
        List<Map<String, Object>> affectationsAvant = jdbc.queryForList("select * from affectation_benevole");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription");

        assertThat(supprimer(patron, idPaul).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForList("select * from affectation_benevole")).isEqualTo(affectationsAvant);
        assertThat(jdbc.queryForList("select * from inscription")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course", "0009-passage",
                        "0010-abandon-inscription", "0011-compte-actif", "0012-photo-compte");
    }

    // ---------------------------------------------------------------- utilitaires

    private JdbcTemplate horsTransaction() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private UUID insererCompte(String pseudo, String role, String motDePasse, int minutes) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(motDePasse), role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z").plusSeconds(60L * minutes)));
        return id;
    }

    /** Admin jetable actif ; Nadia et Paul restent actifs, donc la cible est toujours supprimable. */
    private UUID nouvelAdmin() {
        compteur++;
        return insererCompte("Anonyme" + compteur, "ADMIN", MOT_DE_PASSE_ADMIN, 60 + compteur);
    }

    private boolean existe(UUID compte) {
        return jdbc.queryForObject("select count(*) from compte where id = ?", Integer.class, compte) > 0;
    }

    private List<Map<String, Object>> etatBase() {
        return new ArrayList<>(jdbc.queryForList("select * from compte order by id"));
    }

    private HttpResponse<String> supprimer(Session session, UUID id) throws Exception {
        return api.requete("DELETE", ADMINS + "/" + id, session.entetes(), null, null);
    }

    private HttpResponse<String> lire(Session session, String chemin) throws Exception {
        return api.requete("GET", chemin, session.enteteLecture(), null, null);
    }

    private HttpResponse<String> connexion(String pseudo, String motDePasse) throws Exception {
        String jeton = api.jetonValide();
        return api.requete("POST", "/api/connexion", Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton),
                "application/json", "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}");
    }

    private static List<String> cookiesDeSession(HttpResponse<?> reponse) {
        return reponse.headers().allValues("set-cookie").stream().filter(cookie -> cookie.startsWith("JSESSIONID="))
                .map(cookie -> cookie.substring("JSESSIONID=".length(),
                        cookie.indexOf(';') < 0 ? cookie.length() : cookie.indexOf(';')))
                .filter(valeur -> !valeur.isEmpty()).toList();
    }

    private List<String> pseudos(JsonNode liste) {
        List<String> valeurs = new ArrayList<>();
        liste.forEach(compte -> valeurs.add(compte.get("pseudo").asString()));
        return valeurs;
    }

    /** Messages INFO de l'application commençant par le préfixe. */
    private List<String> lignes(String prefixe) {
        return journal.evenementsApplicatifs().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(prefixe)).toList();
    }

    private JsonNode sansInstance(HttpResponse<String> reponse) {
        ObjectNode corps = (ObjectNode) json.readTree(reponse.body());
        corps.remove("instance");
        return corps;
    }

    @SafeVarargs
    private final List<HttpResponse<String>> enParallele(CountDownLatch depart,
                                                         Callable<HttpResponse<String>>... actions) throws Exception {
        ExecutorService executeur = Executors.newFixedThreadPool(actions.length);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (Callable<HttpResponse<String>> action : actions) {
                futures.add(executeur.submit(action));
            }
            Thread.sleep(150);
            depart.countDown();
            List<HttpResponse<String>> reponses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                reponses.add(future.get());
            }
            return reponses;
        } finally {
            executeur.shutdownNow();
        }
    }

    private void assertProbleme(HttpResponse<String> reponse, int statut, String titre, String code, String detail) {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("application/problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo(code);
        assertThat(corps.get("title").asString()).isEqualTo(titre);
        assertThat(corps.get("status").asInt()).isEqualTo(statut);
        assertThat(corps.get("detail").asString()).isEqualTo(detail);
    }
}
