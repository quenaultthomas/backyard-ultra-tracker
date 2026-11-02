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
 * Contrat de l'incrément 9.3 : suppression d'un bénévole, DELETE /api/administration/benevoles/{id} (CA5 à CA14).
 * Les Comptes, Courses et affectations sont insérés en base ; l'action testée passe par l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuppressionBenevoleIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE_ALICE = "un-mot-de-passe-12";
    static final String BENEVOLES = "/api/administration/benevoles";
    static final String ADMINS = "/api/administration/admins";
    static final String COURSES = "/api/administration/courses";
    static final Instant MAINTENANT = Instant.parse("2026-10-09T14:03:27Z");
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String CSRF_DETAIL = "Jeton CSRF absent ou invalide.";
    static final String INTROUVABLE_DETAIL = "Ce compte est introuvable.";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, "
            + "demarree_le) values (?, ?, ?, ?, 400, 60, 0, 10, 5, ?)";
    static final String NOM_P = "Course Secrete Preparation";
    static final String NOM_C = "Backyard C";
    static final String DETAIL_C = "Ce bénévole est affecté à la course en cours « Backyard C ». "
            + "Retirez-le de cette course avant de le supprimer.";

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
    UUID idLeo;
    UUID idMarc;
    UUID idAlice;
    UUID p;
    UUID p2;
    UUID c;
    UUID t;
    int compteurPseudos;

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
        idLeo = insererCompte("Léo", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, 2);
        idMarc = insererCompte("Marc", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, 3);
        idAlice = insererCompte("Alice", "COUREUR", MOT_DE_PASSE_ALICE, 4);
        p = insererCourse(NOM_P, "2026-10-09", "EN_PREPARATION", null);
        p2 = insererCourse("Deuxieme preparation", "2026-11-01", "EN_PREPARATION", null);
        c = insererCourse(NOM_C, "2026-10-09", "EN_COURS", Instant.parse("2026-10-09T13:30:00Z"));
        t = insererCourse("Course terminee", "2026-10-08", "TERMINEE", Instant.parse("2026-10-08T08:00:00Z"));
        for (UUID course : List.of(p, p2, t)) {
            affecter(course, idLeo);
        }
        affecter(p, idMarc);
        affecter(c, idMarc);
        UUID inscriptionC = inscrire(c, idAlice);
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) "
                + "values (?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscriptionC,
                Timestamp.from(Instant.parse("2026-10-09T13:50:00Z")));
        UUID inscriptionT = inscrire(t, idAlice);
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) "
                + "values (?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscriptionT,
                Timestamp.from(Instant.parse("2026-10-08T08:40:00Z")));
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        journal = JournalApplicatif.demarrer().niveau("fr.backyard.tracker", Level.DEBUG);
    }

    @AfterEach
    void nettoyer() {
        journal.fermer();
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : Nadia supprime Léo (P, P2, T) : 204 sans corps, Compte et affectations de Léo disparus, Marc conservé, Passages SCAN, Courses et Inscriptions inchangés, listes et fiches à jour")
    void ca5_suppression_nominale() throws Exception {
        List<Map<String, Object>> passagesAvant = jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");

        HttpResponse<String> reponse = supprimer(nadia, idLeo);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from compte where id = ?", Integer.class, idLeo)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where benevole_id = ?",
                Integer.class, idLeo)).isZero();
        assertThat(affectationsDe(p)).containsExactly(idMarc);
        assertThat(affectationsDe(c)).containsExactly(idMarc);
        assertThat(jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(jdbc.queryForList("select origine from passage", String.class)).containsOnly("SCAN");
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);

        JsonNode liste = json.readTree(lire(nadia, BENEVOLES).body());
        assertThat(pseudos(liste)).containsExactly("Marc");
        for (UUID course : List.of(p, t)) {
            JsonNode fiche = json.readTree(lire(nadia, COURSES + "/" + course).body());
            assertThat(textes(fiche.get("benevoleIds"))).doesNotContain(idLeo.toString());
        }
        assertThat(textes(json.readTree(lire(nadia, COURSES + "/" + p).body()).get("benevoleIds")))
                .containsExactly(idMarc.toString());
    }

    @Test
    @DisplayName("CA5 : Patron obtient le même 204 ; un bénévole sans aucune affectation est supprimé (204)")
    void ca5_admin_master_et_benevole_sans_affectation() throws Exception {
        UUID libre = insererCompte("Libre", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, 10);

        HttpResponse<String> parPatron = supprimer(patron, libre);
        HttpResponse<String> leoParPatron = supprimer(patron, idLeo);

        assertThat(parPatron.statusCode()).isEqualTo(204);
        assertThat(parPatron.body()).isEmpty();
        assertThat(leoParPatron.statusCode()).isEqualTo(204);
        assertThat(existe(libre)).isFalse();
        assertThat(existe(idLeo)).isFalse();
        assertThat(existe(idMarc)).isTrue();
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : après suppression, la connexion de Léo donne le même 401 IDENTIFIANTS_INVALIDES qu'un pseudo inconnu ; son pseudo (casse différente) est réutilisable (201, nouvel identifiant, actif, sans affectation) et se connecte avec le nouveau mot de passe")
    void ca6_connexion_refusee_et_pseudo_libere() throws Exception {
        assertThat(supprimer(nadia, idLeo).statusCode()).isEqualTo(204);
        registre.vider();

        HttpResponse<String> supprime = connexion("Léo", MOT_DE_PASSE_BENEVOLE);
        HttpResponse<String> inconnu = connexion("Inconnu", MOT_DE_PASSE_BENEVOLE);
        assertThat(supprime.statusCode()).isEqualTo(401);
        assertThat(json.readTree(supprime.body()).get("code").asString()).isEqualTo("IDENTIFIANTS_INVALIDES");
        assertThat(supprime.body()).isEqualTo(inconnu.body());

        for (String variante : List.of("LÉO", "léo", "Léo")) {
            HttpResponse<String> creation = api.requete("POST", BENEVOLES, nadia.entetes(), "application/json",
                    "{\"pseudo\":\"" + variante + "\",\"motDePasse\":\"mot-de-passe-nouveau-1\"}");
            assertThat(creation.statusCode()).as(variante).isEqualTo(201);
            JsonNode corps = json.readTree(creation.body());
            assertThat(corps.get("id").asString()).isNotEqualTo(idLeo.toString());
            assertThat(corps.get("actif").asBoolean()).isTrue();
            UUID nouveau = UUID.fromString(corps.get("id").asString());
            assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where benevole_id = ?",
                    Integer.class, nouveau)).isZero();
            if (!variante.equals("Léo")) {
                assertThat(supprimer(nadia, nouveau).statusCode()).isEqualTo(204);
            }
        }
        registre.vider();
        HttpResponse<String> reussie = connexion("Léo", "mot-de-passe-nouveau-1");
        assertThat(reussie.statusCode()).isEqualTo(200);
        assertThat(connexion("Léo", MOT_DE_PASSE_BENEVOLE).statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : un bénévole désactivé affecté à P est supprimé comme un actif (204)")
    void ca7_benevole_desactive_supprimable() throws Exception {
        assertThat(api.requete("POST", BENEVOLES + "/" + idLeo + "/desactivation", nadia.entetes(), null, null)
                .statusCode()).isEqualTo(204);

        HttpResponse<String> reponse = supprimer(nadia, idLeo);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(existe(idLeo)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where benevole_id = ?",
                Integer.class, idLeo)).isZero();
    }

    @Test
    @DisplayName("CA7 : Marc désactivé et affecté à C EN_COURS : 409 BENEVOLE_AFFECTE_COURSE_EN_COURS ; après retrait de C par PUT (200), la suppression réussit (204)")
    void ca7_desactive_affecte_course_en_cours() throws Exception {
        assertThat(api.requete("POST", BENEVOLES + "/" + idMarc + "/desactivation", nadia.entetes(), null, null)
                .statusCode()).isEqualTo(204);

        HttpResponse<String> refus = supprimer(nadia, idMarc);
        assertProbleme(refus, 409, "Conflit", "BENEVOLE_AFFECTE_COURSE_EN_COURS", DETAIL_C);
        assertThat(existe(idMarc)).isTrue();

        assertThat(affecterVia(nadia, c).statusCode()).isEqualTo(200);
        assertThat(affectationsDe(c)).isEmpty();
        assertThat(supprimer(nadia, idMarc).statusCode()).isEqualTo(204);
        assertThat(existe(idMarc)).isFalse();
        assertThat(affectationsDe(p)).containsExactly(idLeo);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : Marc affecté à P, T et C EN_COURS : 409 au corps exact du contrat (une seule Course nommée), rien n'est modifié, sessions valides")
    void ca8_refus_course_en_cours_corps_exact() throws Exception {
        affecter(t, idMarc);
        Session sessionMarc = api.ouvrir("Marc", MOT_DE_PASSE_BENEVOLE);
        List<Map<String, Object>> comptesAvant = jdbc.queryForList("select * from compte order by id");

        HttpResponse<String> reponse = supprimer(nadia, idMarc);

        assertProbleme(reponse, 409, "Conflit", "BENEVOLE_AFFECTE_COURSE_EN_COURS", DETAIL_C);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("type").asString()).isEqualTo("about:blank");
        assertThat(corps.get("instance").asString()).isEqualTo(BENEVOLES + "/" + idMarc);
        assertThat(corps.get("courses")).hasSize(1);
        assertThat(corps.get("courses").get(0).get("id").asString()).isEqualTo(c.toString());
        assertThat(corps.get("courses").get(0).get("nom").asString()).isEqualTo(NOM_C);
        assertThat(corps.get("courses").toString()).doesNotContain(NOM_P).doesNotContain("terminee");
        assertThat(corps.get("detail").asString()).doesNotContain("Marc").doesNotContain(idMarc.toString());
        assertThat(reponse.body()).doesNotContain("Marc");
        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(comptesAvant);
        assertThat(jdbc.queryForList("select course_id from affectation_benevole where benevole_id = ?", UUID.class,
                idMarc)).containsExactlyInAnyOrder(p, t, c);
        assertThat(lire(sessionMarc, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(lire(sessionMarc, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA8 : deux Courses EN_COURS « alpha » et « Zèbre » : courses triées sans tenir compte de la casse, detail au pluriel")
    void ca8_refus_deux_courses_en_cours_triees() throws Exception {
        UUID zebre = insererCourse("Zèbre", "2026-10-09", "EN_COURS", Instant.parse("2026-10-09T13:30:00Z"));
        affecter(zebre, idMarc);
        jdbc.update("update course set nom = 'alpha' where id = ?", c);

        HttpResponse<String> reponse = supprimer(nadia, idMarc);

        assertProbleme(reponse, 409, "Conflit", "BENEVOLE_AFFECTE_COURSE_EN_COURS",
                "Ce bénévole est affecté aux courses en cours « alpha », « Zèbre ». "
                        + "Retirez-le de ces courses avant de le supprimer.");
        JsonNode courses = json.readTree(reponse.body()).get("courses");
        assertThat(courses).hasSize(2);
        assertThat(courses.get(0).get("id").asString()).isEqualTo(c.toString());
        assertThat(courses.get(0).get("nom").asString()).isEqualTo("alpha");
        assertThat(courses.get(1).get("id").asString()).isEqualTo(zebre.toString());
        assertThat(courses.get(1).get("nom").asString()).isEqualTo("Zèbre");
        assertThat(existe(idMarc)).isTrue();
        assertThat(affectationsDe(p)).contains(idMarc);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : anonyme 401 NON_AUTHENTIFIE ; Léo (bénévole) et Alice (coureuse) 403 ACCES_REFUSE, même réponse pour un identifiant inconnu ; base inchangée")
    void ca9_securite_roles() throws Exception {
        Session leo = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session alice = api.ouvrir("Alice", MOT_DE_PASSE_ALICE);
        List<Map<String, Object>> avant = etatBase();
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (UUID cible : List.of(idLeo, UUID.fromString(ID_INCONNU))) {
            assertProbleme(api.requete("DELETE", BENEVOLES + "/" + cible, anonyme, null, null), 401,
                    "Authentification requise", "NON_AUTHENTIFIE", "Vous devez être connecté.");
        }
        for (Session interdit : List.of(leo, alice)) {
            HttpResponse<String> connue = supprimer(interdit, idMarc);
            HttpResponse<String> inconnue = supprimer(interdit, UUID.fromString(ID_INCONNU));
            assertProbleme(connue, 403, "Accès refusé", "ACCES_REFUSE", ACCES_REFUSE_DETAIL);
            assertThat(sansInstance(inconnue)).isEqualTo(sansInstance(connue));
        }
        assertThat(etatBase()).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA9 : sans jeton CSRF ou avec un jeton différent, 403 CSRF_INVALIDE et base inchangée")
    void ca9_csrf_absent_ou_invalide() throws Exception {
        List<Map<String, Object>> avant = etatBase();
        for (Session session : List.of(patron, nadia)) {
            HttpResponse<String> sansEntete = api.requete("DELETE", BENEVOLES + "/" + idLeo,
                    Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf()), null, null);
            assertProbleme(sansEntete, 403, "Accès refusé", "CSRF_INVALIDE", CSRF_DETAIL);
            HttpResponse<String> different = api.requete("DELETE", BENEVOLES + "/" + idLeo,
                    Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf(),
                            "X-XSRF-TOKEN", "autre-valeur"), null, null);
            assertProbleme(different, 403, "Accès refusé", "CSRF_INVALIDE", CSRF_DETAIL);
        }
        assertThat(etatBase()).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA9 : identifiant inconnu, mal formé, coureuse, admin (dont l'appelant) ou admin master : 404 COMPTE_INTROUVABLE de corps identique, rien n'est modifié ; second DELETE sur Léo : 404")
    void ca9_cibles_introuvables_et_non_idempotence() throws Exception {
        List<Map<String, Object>> avant = etatBase();
        JsonNode reference = null;
        for (String cible : List.of(ID_INCONNU, "pas-un-uuid", idAlice.toString(), idNadia.toString(),
                idPatron.toString())) {
            HttpResponse<String> reponse = api.requete("DELETE", BENEVOLES + "/" + cible, nadia.entetes(), null, null);
            assertProbleme(reponse, 404, "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
            JsonNode corps = sansInstance(reponse);
            reference = reference == null ? corps : reference;
            assertThat(corps).isEqualTo(reference);
        }
        assertThat(etatBase()).isEqualTo(avant);

        assertThat(supprimer(nadia, idLeo).statusCode()).isEqualTo(204);
        assertProbleme(supprimer(nadia, idLeo), 404, "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
    }

    @Test
    @DisplayName("CA9 : PUT et PATCH sur /benevoles/{id} : 404 ou 405 ; DELETE /admins/{id} par Nadia : 403 ACCES_REFUSE ; rien n'est modifié")
    void ca9_autres_methodes_et_admins() throws Exception {
        List<Map<String, Object>> avant = etatBase();
        for (String methode : List.of("PUT", "PATCH")) {
            HttpResponse<String> reponse = api.requete(methode, BENEVOLES + "/" + idLeo, patron.entetes(),
                    "application/json", "{\"pseudo\":\"Pirate\"}");
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        assertProbleme(api.requete("DELETE", ADMINS + "/" + idNadia, nadia.entetes(), null, null), 403,
                "Accès refusé", "ACCES_REFUSE", ACCES_REFUSE_DETAIL);
        assertThat(etatBase()).isEqualTo(avant);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : les deux sessions de Léo sont fermées (401 NON_AUTHENTIFIE) après suppression ; celles de Marc et de Nadia restent valides ; la fermeture a lieu après validation (Compte déjà absent)")
    void ca10_fermeture_de_toutes_les_sessions() throws Exception {
        Session s1 = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session s2 = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session s3 = api.ouvrir("Marc", MOT_DE_PASSE_BENEVOLE);
        assertThat(lire(s1, "/api/benevole/courses").statusCode()).isEqualTo(200);
        AtomicReference<Boolean> absentALaFermeture = new AtomicReference<>();
        doAnswer(invocation -> {
            absentALaFermeture.set(horsTransaction()
                    .queryForObject("select count(*) from compte where id = ?", Integer.class, idLeo) == 0);
            return invocation.callRealMethod();
        }).when(registreSessions).invaliderToutesLesSessions(idLeo);

        assertThat(supprimer(nadia, idLeo).statusCode()).isEqualTo(204);

        assertThat(absentALaFermeture.get()).isTrue();
        for (Session fermee : List.of(s1, s2)) {
            assertProbleme(lire(fermee, "/api/benevole/courses"), 401, "Authentification requise", "NON_AUTHENTIFIE",
                    "Vous devez être connecté.");
            assertThat(lire(fermee, "/api/comptes/moi").statusCode()).isEqualTo(401);
        }
        assertThat(lire(s3, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA10 : une suppression annulée par une panne à supprimer laisse les sessions valides, le Compte et ses affectations en base (500 ERREUR_INTERNE)")
    void ca10_panne_pendant_la_suppression() throws Exception {
        Session s1 = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        Session s2 = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        doThrow(new IllegalStateException("panne simulée secret-technique")).when(depotComptes).supprimer(any());

        HttpResponse<String> reponse = supprimer(nadia, idLeo);

        assertThat(reponse.statusCode()).isEqualTo(500);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("secret-technique");
        assertThat(existe(idLeo)).isTrue();
        assertThat(jdbc.queryForList("select course_id from affectation_benevole where benevole_id = ?", UUID.class,
                idLeo)).containsExactlyInAnyOrder(p, p2, t);
        for (Session session : List.of(s1, s2)) {
            assertThat(lire(session, "/api/benevole/courses").statusCode()).isEqualTo(200);
            assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("CA10 : une connexion finalisée pendant la suppression n'aboutit à aucune session valide : 401 IDENTIFIANTS_INVALIDES")
    void ca10_connexion_concurrente_d_une_suppression() throws Exception {
        doAnswer(invocation -> {
            horsTransaction().update("delete from affectation_benevole where benevole_id = ?", idLeo);
            horsTransaction().update("delete from compte where id = ?", idLeo);
            return invocation.callRealMethod();
        }).when(consulterCompteConnecte).estConnectable(idLeo);

        HttpResponse<String> reponse = connexion("Léo", MOT_DE_PASSE_BENEVOLE);

        assertProbleme(reponse, 401, "Authentification échouée", "IDENTIFIANTS_INVALIDES",
                "Pseudo ou mot de passe incorrect.");
        for (String identifiant : cookiesDeSession(reponse)) {
            assertThat(lire(new Session(identifiant, "x"), "/api/comptes/moi").statusCode()).isEqualTo(401);
            assertThat(lire(new Session(identifiant, "x"), "/api/benevole/courses").statusCode()).isEqualTo(401);
        }
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : après 5 échecs de connexion (429 TENTATIVES_EXCESSIVES), la suppression puis la recréation du même pseudo remettent le compteur à zéro : la connexion du nouveau Compte réussit")
    void ca11_compteur_d_echecs_remis_a_zero() throws Exception {
        UUID jetable = insererCompte("Jetable", "BENEVOLE", MOT_DE_PASSE_BENEVOLE, 20);
        registre.vider();
        for (int i = 0; i < 5; i++) {
            assertThat(connexion("Jetable", "mauvais-mot-de-passe-1").statusCode()).as("échec " + i).isEqualTo(401);
        }
        HttpResponse<String> bloque = connexion("Jetable", MOT_DE_PASSE_BENEVOLE);
        assertThat(bloque.statusCode()).isEqualTo(429);
        assertThat(json.readTree(bloque.body()).get("code").asString()).isEqualTo("TENTATIVES_EXCESSIVES");

        assertThat(supprimer(nadia, jetable).statusCode()).isEqualTo(204);
        HttpResponse<String> creation = api.requete("POST", BENEVOLES, nadia.entetes(), "application/json",
                "{\"pseudo\":\"Jetable\",\"motDePasse\":\"mot-de-passe-jetable-2\"}");
        assertThat(creation.statusCode()).isEqualTo(201);

        HttpResponse<String> reussie = connexion("Jetable", "mot-de-passe-jetable-2");
        assertThat(reussie.statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 (a) : deux DELETE simultanés de Léo : exactement un 204 et un 404, jamais de 500 (20 répétitions)")
    void ca12_deux_suppressions_simultanees() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = nouveauBenevole();
            UUID course = nouvelleCoursePreparation();
            affecter(course, cible);
            CountDownLatch depart = new CountDownLatch(1);
            Callable<HttpResponse<String>> action = () -> {
                depart.await();
                return supprimer(nadia, cible);
            };

            List<HttpResponse<String>> reponses = enParallele(depart, action, action);

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(204, 404);
            assertThat(existe(cible)).isFalse();
            assertThat(affectationsDe(course)).isEmpty();
        }
    }

    @Test
    @DisplayName("CA12 (b) : DELETE et désactivation simultanés : DELETE 204, désactivation 204 ou 404, Compte absent, jamais de 500 (20 répétitions)")
    void ca12_suppression_et_desactivation_simultanees() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = nouveauBenevole();
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return supprimer(nadia, cible);
                    },
                    () -> {
                        depart.await();
                        return api.requete("POST", BENEVOLES + "/" + cible + "/desactivation", nadia.entetes(), null,
                                null);
                    });

            assertThat(reponses.get(0).statusCode()).as("suppression " + i).isEqualTo(204);
            assertThat(reponses.get(1).statusCode()).as("désactivation " + i).isIn(204, 404);
            assertThat(existe(cible)).isFalse();
        }
    }

    @Test
    @DisplayName("CA12 (c) : DELETE et démarrage simultanés de la Course où le bénévole est affecté : 409 (Course EN_COURS relue) ou 204 avec toutes les affectations retirées, jamais de 500 ni d'état intermédiaire (20 répétitions)")
    void ca12_suppression_et_demarrage_simultanes() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = nouveauBenevole();
            UUID course = nouvelleCoursePreparation();
            affecter(course, cible);
            inscrire(course, idAlice);
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return supprimer(nadia, cible);
                    },
                    () -> {
                        depart.await();
                        return api.requete("POST", COURSES + "/" + course + "/demarrage", nadia.entetes(), null, null);
                    });

            assertThat(reponses.get(1).statusCode()).as("démarrage " + i).isEqualTo(200);
            int statut = reponses.get(0).statusCode();
            assertThat(statut).as("suppression " + i).isIn(204, 409);
            if (statut == 204) {
                assertThat(existe(cible)).isFalse();
                assertThat(affectationsDe(course)).isEmpty();
            } else {
                assertThat(json.readTree(reponses.get(0).body()).get("code").asString())
                        .isEqualTo("BENEVOLE_AFFECTE_COURSE_EN_COURS");
                assertThat(existe(cible)).isTrue();
                assertThat(affectationsDe(course)).containsExactly(cible);
            }
            assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, course))
                    .isEqualTo("EN_COURS");
        }
    }

    @Test
    @DisplayName("CA12 (d) : DELETE et PUT .../benevoles ajoutant le bénévole simultanés : jamais de 500, DELETE 204, Compte absent (20 répétitions)")
    void ca12_suppression_et_affectation_simultanees() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = nouveauBenevole();
            UUID course = nouvelleCoursePreparation();
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return supprimer(nadia, cible);
                    },
                    () -> {
                        depart.await();
                        return affecterVia(nadia, course, cible);
                    });

            assertThat(reponses.get(0).statusCode()).as("suppression " + i).isEqualTo(204);
            assertThat(reponses.get(1).statusCode()).as("affectation " + i).isLessThan(500);
            assertThat(existe(cible)).isFalse();
        }
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : succès, refus 409 puis 404 : une ligne « Bénévole supprimé » (3 affectations), une ligne « Suppression refusée » (1 course), aucune ligne pour le 404 ; journal et 404 sans pseudo, mot de passe ni nom de Course")
    void ca13_journal() throws Exception {
        HttpResponse<String> succes = supprimer(nadia, idLeo);
        HttpResponse<String> refus = supprimer(nadia, idMarc);
        HttpResponse<String> introuvable = supprimer(nadia, idLeo);

        assertThat(succes.statusCode()).isEqualTo(204);
        assertThat(refus.statusCode()).isEqualTo(409);
        assertThat(introuvable.statusCode()).isEqualTo(404);
        assertThat(lignes("Bénévole supprimé")).containsExactly("Bénévole supprimé (compte " + idLeo + " par "
                + idNadia + ", 3 affectation(s) retirée(s))");
        assertThat(lignes("Suppression refusée")).containsExactly("Suppression refusée (compte " + idMarc
                + ", motif BENEVOLE_AFFECTE_COURSE_EN_COURS, 1 course(s))");
        assertThat(introuvable.body()).doesNotContain("Léo").doesNotContain(NOM_C).doesNotContain(NOM_P)
                .doesNotContain(MOT_DE_PASSE_BENEVOLE);
        assertThat(refus.body()).contains(NOM_C);
        for (ILoggingEvent evenement : journal.evenements()) {
            String texte = evenement.getFormattedMessage() + " " + evenement.getMDCPropertyMap()
                    + (evenement.getThrowableProxy() == null ? "" : evenement.getThrowableProxy().getMessage());
            assertThat(texte).as(evenement.getLevel() + " " + evenement.getLoggerName())
                    .doesNotContain(MOT_DE_PASSE_BENEVOLE).doesNotContain(MOT_DE_PASSE_ADMIN)
                    .doesNotContain(MOT_DE_PASSE_PATRON);
            if (evenement.getLevel().isGreaterOrEqual(Level.INFO)
                    && evenement.getLoggerName().startsWith("fr.backyard.tracker")) {
                assertThat(texte).as("INFO+ " + evenement.getLoggerName()).doesNotContain("Léo")
                        .doesNotContain("Marc").doesNotContain("Nadia").doesNotContain("Alice")
                        .doesNotContain(NOM_C).doesNotContain(NOM_P);
            }
        }
    }

    @Test
    @DisplayName("CA13 : 403 et 404 ne produisent aucune ligne INFO de ce cas d'usage")
    void ca13_aucune_ligne_pour_403_et_404() throws Exception {
        Session leo = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        int marque = journal.marque();

        assertThat(supprimer(leo, idMarc).statusCode()).isEqualTo(403);
        assertThat(supprimer(nadia, idAlice).statusCode()).isEqualTo(404);
        assertThat(supprimer(nadia, UUID.fromString(ID_INCONNU)).statusCode()).isEqualTo(404);

        assertThat(journal.applicatifsDepuis(marque)).filteredOn(e -> e.getLevel() == Level.INFO)
                .noneMatch(e -> e.getFormattedMessage().startsWith("Bénévole supprimé")
                        || e.getFormattedMessage().startsWith("Suppression refusée"));
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : aucun nouveau changeset (0002 à 0011-compte-actif dans l'ordre) ; aucune affectation orpheline après suppression ; démarrage sur schéma validé")
    void ca14_schema_inchange() throws Exception {
        assertThat(supprimer(nadia, idLeo).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course", "0009-passage",
                        "0010-abandon-inscription", "0011-compte-actif");
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole a left join compte b "
                + "on b.id = a.benevole_id where b.id is null", Integer.class)).isZero();
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

    private UUID nouveauBenevole() {
        compteurPseudos++;
        return insererCompte("Anonyme" + compteurPseudos, "BENEVOLE", MOT_DE_PASSE_BENEVOLE, 30 + compteurPseudos);
    }

    private UUID nouvelleCoursePreparation() {
        compteurPseudos++;
        return insererCourse("Preparation " + compteurPseudos, "2026-10-09", "EN_PREPARATION", null);
    }

    private UUID insererCourse(String nom, String date, String statut, Instant demarreeLe) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf(date), statut,
                demarreeLe == null ? null : Timestamp.from(demarreeLe));
        return id;
    }

    private UUID inscrire(UUID course, UUID compte) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, 1, ?, 'EN_COURSE')", id, course, compte, UUID.randomUUID().toString());
        return id;
    }

    private void affecter(UUID course, UUID benevole) {
        jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", course, benevole);
    }

    private List<UUID> affectationsDe(UUID course) {
        return jdbc.queryForList("select benevole_id from affectation_benevole where course_id = ?", UUID.class,
                course);
    }

    private boolean existe(UUID compte) {
        return jdbc.queryForObject("select count(*) from compte where id = ?", Integer.class, compte) > 0;
    }

    private List<Map<String, Object>> etatBase() {
        List<Map<String, Object>> etat = new ArrayList<>(jdbc.queryForList("select * from compte order by id"));
        etat.addAll(jdbc.queryForList("select * from affectation_benevole order by course_id, benevole_id"));
        return etat;
    }

    private HttpResponse<String> supprimer(Session session, UUID id) throws Exception {
        return api.requete("DELETE", BENEVOLES + "/" + id, session.entetes(), null, null);
    }

    private HttpResponse<String> lire(Session session, String chemin) throws Exception {
        return api.requete("GET", chemin, session.enteteLecture(), null, null);
    }

    private HttpResponse<String> connexion(String pseudo, String motDePasse) throws Exception {
        String jeton = api.jetonValide();
        return api.requete("POST", "/api/connexion", Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton),
                "application/json", "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}");
    }

    private HttpResponse<String> affecterVia(Session session, UUID course, UUID... benevoles) throws Exception {
        ObjectNode corps = json.createObjectNode();
        var tableau = corps.putArray("benevoleIds");
        for (UUID benevole : benevoles) {
            tableau.add(benevole.toString());
        }
        return api.requete("PUT", COURSES + "/" + course + "/benevoles", session.entetes(), "application/json",
                corps.toString());
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

    private List<String> textes(JsonNode tableau) {
        List<String> valeurs = new ArrayList<>();
        tableau.forEach(valeur -> valeurs.add(valeur.asString()));
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
