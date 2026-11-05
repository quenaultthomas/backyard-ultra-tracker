package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 9.6a : un bénévole ou un admin qui a des Inscriptions est anonymisé à la suppression
 * (DELETE /api/administration/benevoles/{id} et /admins/{id}, contrat inchangé : 204), sinon supprimé physiquement
 * (CA5 à CA10, vérifications d'API de CA11). Un ancien coureur devenu BENEVOLE ou ADMIN est fabriqué par SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuppressionAnonymisationIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE_COUREUR = "un-mot-de-passe-12";
    static final String BENEVOLES = "/api/administration/benevoles";
    static final String ADMINS = "/api/administration/admins";
    static final String COURSES = "/api/administration/courses";
    static final String RECHERCHE = "/api/administration/coureurs";
    static final String ANONYME = "Coureur anonyme";
    static final Instant MAINTENANT = Instant.parse("2026-10-09T14:03:27Z");
    static final String INTROUVABLE_DETAIL = "Ce compte est introuvable.";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, "
            + "demarree_le) values (?, ?, ?, ?, 400, 60, 0, 10, 5, ?)";
    static final String NOM_C = "Backyard C";

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

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    JournalApplicatif journal;
    Session patron;
    Session nadia;
    UUID idPatron;
    UUID idNadia;
    UUID idMarc;
    UUID p;
    UUID p2;
    UUID c;
    UUID t;
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
        idNadia = insererCompte("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN);
        idMarc = insererCompte("Marc", "BENEVOLE", MOT_DE_PASSE_BENEVOLE);
        p = insererCourse("Preparation un", "2026-10-09", "EN_PREPARATION", null);
        p2 = insererCourse("Preparation deux", "2026-11-01", "EN_PREPARATION", null);
        c = insererCourse(NOM_C, "2026-10-09", "EN_COURS", Instant.parse("2026-10-09T13:30:00Z"));
        t = insererCourse("Course terminee", "2026-10-08", "TERMINEE", Instant.parse("2026-10-08T08:00:00Z"));
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
    @DisplayName("CA5 : Nadia supprime Alice2 (ex-coureuse, Inscription VAINQUEUR dossard 7 en T, affectée à T et P) : 204 sans corps, Compte anonymisé (même id, pseudo « Coureur anonyme », clé #, empreinte nulle, rôle COUREUR), affectations retirées, Inscription et Passage inchangés, Marc intact")
    void ca5_anonymisation_nominale() throws Exception {
        UUID alice2 = exCoureur("Alice2", "BENEVOLE", 7);
        Instant creeLe = jdbc.queryForObject("select cree_le from compte where id = ?", Timestamp.class, alice2)
                .toInstant();
        affecter(t, alice2);
        affecter(p, alice2);
        affecter(p, idMarc);
        Map<String, Object> marcAvant = jdbc.queryForMap("select * from compte where id = ?", idMarc);
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> passagesAvant = jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");

        HttpResponse<String> reponse = supprimer(nadia, alice2);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        Map<String, Object> apres = jdbc.queryForMap("select * from compte where id = ?", alice2);
        assertThat(apres.get("pseudo")).isEqualTo(ANONYME);
        assertThat((String) apres.get("pseudo_normalise")).startsWith("#");
        assertThat(apres.get("empreinte_mot_de_passe")).isNull();
        assertThat(apres.get("role")).isEqualTo("COUREUR");
        assertThat(((Timestamp) apres.get("cree_le")).toInstant()).isEqualTo(creeLe);
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where benevole_id = ?",
                Integer.class, alice2)).isZero();
        assertThat(affectationsDe(p)).containsExactly(idMarc);
        assertThat(jdbc.queryForMap("select * from compte where id = ?", idMarc)).isEqualTo(marcAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForObject("select dossard from inscription where compte_id = ?", Integer.class, alice2))
                .isEqualTo(7);
        assertThat(jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(jdbc.queryForList("select origine from passage", String.class)).containsOnly("SCAN");
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);

        JsonNode inscrits = json.readTree(lire(nadia, COURSES + "/" + t + "/inscriptions").body()).get("inscrits");
        assertThat(inscrits).hasSize(1);
        assertThat(inscrits.get(0).get("pseudo").asString()).isEqualTo(ANONYME);
        assertThat(inscrits.get(0).get("dossard").asInt()).isEqualTo(7);
        JsonNode suivi = json.readTree(api.requete("GET", "/api/courses/" + t + "/suivi", Map.of(), null, null).body());
        assertThat(suivi.get("vainqueurs")).hasSize(1);
        assertThat(suivi.get("vainqueurs").get(0).get("pseudo").asString()).isEqualTo(ANONYME);
        assertThat(suivi.get("vainqueurs").get(0).get("dossard").asInt()).isEqualTo(7);
        assertThat(suivi.toString()).doesNotContain("Alice2");
    }

    @Test
    @DisplayName("CA5 : connexion avec l'ancien pseudo : 401 IDENTIFIANTS_INVALIDES de corps identique à celui d'un pseudo inconnu ; ses sessions sont fermées (401), celles de Marc et Nadia restent valides")
    void ca5_connexion_impossible_et_sessions_fermees() throws Exception {
        UUID alice2 = exCoureur("Alice2", "BENEVOLE", 7);
        Session s1 = api.ouvrir("Alice2", MOT_DE_PASSE_COUREUR);
        Session s2 = api.ouvrir("Alice2", MOT_DE_PASSE_COUREUR);
        Session sessionMarc = api.ouvrir("Marc", MOT_DE_PASSE_BENEVOLE);
        assertThat(lire(s1, "/api/benevole/courses").statusCode()).isEqualTo(200);

        assertThat(supprimer(nadia, alice2).statusCode()).isEqualTo(204);
        registre.vider();

        HttpResponse<String> ancien = connexion("Alice2", MOT_DE_PASSE_COUREUR);
        HttpResponse<String> inconnu = connexion("Inconnu", MOT_DE_PASSE_COUREUR);
        assertThat(ancien.statusCode()).isEqualTo(401);
        assertThat(json.readTree(ancien.body()).get("code").asString()).isEqualTo("IDENTIFIANTS_INVALIDES");
        assertThat(ancien.body()).isEqualTo(inconnu.body());
        for (Session fermee : List.of(s1, s2)) {
            assertProbleme(lire(fermee, "/api/comptes/moi"), 401, "Authentification requise", "NON_AUTHENTIFIE",
                    "Vous devez être connecté.");
            assertThat(lire(fermee, "/api/benevole/courses").statusCode()).isEqualTo(401);
        }
        assertThat(lire(sessionMarc, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA5 : le pseudo d'origine (et ses variantes de casse) est réutilisable : 201, nouvel identifiant, sans affectation ni Inscription ; connexion avec le nouveau mot de passe")
    void ca5_pseudo_libere() throws Exception {
        UUID alice2 = exCoureur("Alice2", "BENEVOLE", 7);
        assertThat(supprimer(nadia, alice2).statusCode()).isEqualTo(204);

        HttpResponse<String> creation = api.requete("POST", BENEVOLES, nadia.entetes(), "application/json",
                "{\"pseudo\":\"ALICE2\",\"motDePasse\":\"mot-de-passe-nouveau-1\"}");

        assertThat(creation.statusCode()).isEqualTo(201);
        JsonNode corps = json.readTree(creation.body());
        UUID nouveau = UUID.fromString(corps.get("id").asString());
        assertThat(nouveau).isNotEqualTo(alice2);
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                nouveau)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where benevole_id = ?",
                Integer.class, nouveau)).isZero();
        registre.vider();
        assertThat(connexion("ALICE2", "mot-de-passe-nouveau-1").statusCode()).isEqualTo(200);
        assertThat(existe(alice2)).isTrue();
        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, alice2))
                .isEqualTo(ANONYME);
    }

    @Test
    @DisplayName("CA5 : après 5 échecs de connexion (429), l'anonymisation remet le compteur de l'ancien pseudo à zéro : 401 et non 429, et le nouveau Compte du même pseudo se connecte")
    void ca5_compteur_d_echecs_remis_a_zero() throws Exception {
        UUID jetable = exCoureur("Jetable2", "BENEVOLE", 3);
        registre.vider();
        for (int i = 0; i < 5; i++) {
            assertThat(connexion("Jetable2", "mauvais-mot-de-passe-1").statusCode()).as("échec " + i).isEqualTo(401);
        }
        assertThat(connexion("Jetable2", MOT_DE_PASSE_COUREUR).statusCode()).isEqualTo(429);

        assertThat(supprimer(nadia, jetable).statusCode()).isEqualTo(204);

        assertThat(connexion("Jetable2", "mauvais-mot-de-passe-1").statusCode()).isEqualTo(401);
        HttpResponse<String> creation = api.requete("POST", BENEVOLES, nadia.entetes(), "application/json",
                "{\"pseudo\":\"Jetable2\",\"motDePasse\":\"mot-de-passe-jetable-2\"}");
        assertThat(creation.statusCode()).isEqualTo(201);
        assertThat(connexion("Jetable2", "mot-de-passe-jetable-2").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA5 : un bénévole ex-coureur désactivé est anonymisé comme un actif, son état actif est conservé")
    void ca5_benevole_desactive_anonymise() throws Exception {
        UUID alice2 = exCoureur("Alice2", "BENEVOLE", 7);
        assertThat(api.requete("POST", BENEVOLES + "/" + alice2 + "/desactivation", nadia.entetes(), null, null)
                .statusCode()).isEqualTo(204);

        assertThat(supprimer(nadia, alice2).statusCode()).isEqualTo(204);

        Map<String, Object> apres = jdbc.queryForMap("select * from compte where id = ?", alice2);
        assertThat(apres.get("pseudo")).isEqualTo(ANONYME);
        assertThat(apres.get("role")).isEqualTo("COUREUR");
        assertThat(apres.get("empreinte_mot_de_passe")).isNull();
        assertThat(apres.get("actif")).isEqualTo(false);
    }

    @Test
    @DisplayName("CA5 : deux anonymisations successives coexistent (clés # distinctes)")
    void ca5_deux_comptes_anonymises_coexistent() throws Exception {
        UUID a = exCoureur("Alice2", "BENEVOLE", 7);
        UUID b = exCoureur("Bruno2", "BENEVOLE", 8);

        assertThat(supprimer(nadia, a).statusCode()).isEqualTo(204);
        assertThat(supprimer(nadia, b).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForList("select pseudo from compte where id in (?, ?)", String.class, a, b))
                .containsExactly(ANONYME, ANONYME);
        assertThat(jdbc.queryForList("select distinct pseudo_normalise from compte where id in (?, ?)",
                String.class, a, b)).hasSize(2).allSatisfy(cle -> assertThat(cle).startsWith("#"));
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : bénévole jamais coureur, sans Inscription : 204 et Compte supprimé physiquement ; journal « Bénévole supprimé », aucune ligne « anonymisé »")
    void ca6_benevole_sans_inscription_supprime_physiquement() throws Exception {
        UUID libre = insererCompte("Libre", "BENEVOLE", MOT_DE_PASSE_BENEVOLE);
        affecter(p, libre);

        HttpResponse<String> reponse = supprimer(nadia, libre);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        assertThat(existe(libre)).isFalse();
        assertThat(affectationsDe(p)).isEmpty();
        assertThat(lignes("Bénévole supprimé")).containsExactly("Bénévole supprimé (compte " + libre + " par "
                + idNadia + ", 1 affectation(s) retirée(s))");
        assertThat(lignes("Bénévole anonymisé")).isEmpty();
    }

    @Test
    @DisplayName("CA6 : ancien coureur sans aucune Inscription passé en BENEVOLE par SQL : suppression physique (la ligne disparaît)")
    void ca6_ex_coureur_sans_inscription_supprime_physiquement() throws Exception {
        UUID ancien = insererCompte("AncienSans", "COUREUR", MOT_DE_PASSE_COUREUR);
        jdbc.update("update compte set role = 'BENEVOLE' where id = ?", ancien);

        assertThat(supprimer(nadia, ancien).statusCode()).isEqualTo(204);

        assertThat(existe(ancien)).isFalse();
        assertThat(lignes("Bénévole anonymisé")).isEmpty();
    }

    @Test
    @DisplayName("CA6 : bénévole avec une Inscription EN_PREPARATION posée en SQL : l'Inscription est annulée puis le Compte supprimé physiquement")
    void ca6_inscription_en_preparation_annulee_puis_suppression() throws Exception {
        UUID ancien = insererCompte("AncienPrepa", "COUREUR", MOT_DE_PASSE_COUREUR);
        inscrire(p, ancien, 1, "EN_COURSE");
        jdbc.update("update compte set role = 'BENEVOLE' where id = ?", ancien);

        assertThat(supprimer(nadia, ancien).statusCode()).isEqualTo(204);

        assertThat(existe(ancien)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                ancien)).isZero();
        assertThat(lignes("Bénévole supprimé")).hasSize(1);
        assertThat(lignes("Bénévole anonymisé")).isEmpty();
    }

    @Test
    @DisplayName("CA6 : Inscription EN_PREPARATION annulée et Inscription TERMINEE conservée : le Compte est anonymisé, seule l'Inscription de T reste")
    void ca6_inscription_en_preparation_annulee_et_terminee_conservee() throws Exception {
        UUID ancien = exCoureur("AncienMixte", "BENEVOLE", 7);
        inscrire(p, ancien, 3, "EN_COURSE");

        assertThat(supprimer(nadia, ancien).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, ancien))
                .isEqualTo(ANONYME);
        assertThat(jdbc.queryForList("select course_id from inscription where compte_id = ?", UUID.class, ancien))
                .containsExactly(t);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : Patron supprime Admin2 (ex-coureur, Inscription TERMINEE) avec Nadia active : 204, Compte anonymisé comme CA5, Inscription conservée, absent de GET /admins, sessions d'Admin2 fermées, celles de Nadia et Patron valides ; journal « Admin anonymisé »")
    void ca7_admin_avec_inscription_anonymise() throws Exception {
        UUID admin2 = exCoureur("Admin2", "ADMIN", 4);
        Session sessionAdmin2 = api.ouvrir("Admin2", MOT_DE_PASSE_COUREUR);
        assertThat(lire(sessionAdmin2, "/api/comptes/moi").statusCode()).isEqualTo(200);
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");

        HttpResponse<String> reponse = supprimer(patron, admin2);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        Map<String, Object> apres = jdbc.queryForMap("select * from compte where id = ?", admin2);
        assertThat(apres.get("pseudo")).isEqualTo(ANONYME);
        assertThat((String) apres.get("pseudo_normalise")).startsWith("#");
        assertThat(apres.get("empreinte_mot_de_passe")).isNull();
        assertThat(apres.get("role")).isEqualTo("COUREUR");
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        JsonNode liste = json.readTree(lire(patron, ADMINS).body());
        assertThat(pseudos(liste)).containsExactly("Nadia");
        assertThat(lire(sessionAdmin2, "/api/comptes/moi").statusCode()).isEqualTo(401);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(patron, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lignes("Admin anonymisé")).containsExactly("Admin anonymisé (compte " + admin2 + " par "
                + idPatron + ")");
        assertThat(lignes("Admin supprimé")).isEmpty();
        registre.vider();
        assertThat(connexion("Admin2", MOT_DE_PASSE_COUREUR).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("CA7 : un ADMIN sans Inscription est supprimé physiquement (journal « Admin supprimé »)")
    void ca7_admin_sans_inscription_supprime_physiquement() throws Exception {
        UUID paul = insererCompte("Paul", "ADMIN", MOT_DE_PASSE_ADMIN);

        assertThat(supprimer(patron, paul).statusCode()).isEqualTo(204);

        assertThat(existe(paul)).isFalse();
        assertThat(lignes("Admin supprimé")).containsExactly("Admin supprimé (compte " + paul + " par " + idPatron
                + ")");
        assertThat(lignes("Admin anonymisé")).isEmpty();
    }

    @Test
    @DisplayName("CA7 : Admin2 (avec Inscription) seul ADMIN actif (Nadia désactivée) : 409 DERNIER_ADMIN_ACTIF, rien modifié, aucune ligne d'anonymisation")
    void ca7_dernier_admin_actif_avec_inscription() throws Exception {
        UUID admin2 = exCoureur("Admin2", "ADMIN", 4);
        jdbc.update("update compte set actif = false where id = ?", idNadia);
        Session sessionAdmin2 = api.ouvrir("Admin2", MOT_DE_PASSE_COUREUR);
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> reponse = supprimer(patron, admin2);

        assertProbleme(reponse, 409, "Conflit", "DERNIER_ADMIN_ACTIF",
                "Impossible de supprimer le dernier administrateur actif.");
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(sessionAdmin2, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lignes("Admin anonymisé")).isEmpty();
    }

    @Test
    @DisplayName("CA7 : Patron visé : 409 ADMIN_MASTER_NON_SUPPRIMABLE, rien modifié")
    void ca7_admin_master_non_supprimable() throws Exception {
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> reponse = supprimer(patron, idPatron);

        assertProbleme(reponse, 409, "Conflit", "ADMIN_MASTER_NON_SUPPRIMABLE",
                "L'administrateur principal ne peut pas être supprimé.");
        assertThat(etatBase()).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA7 : Nadia (ADMIN) ne peut pas supprimer Admin2 : 403 ACCES_REFUSE, rien anonymisé")
    void ca7_admin_ne_peut_pas_supprimer_un_admin() throws Exception {
        UUID admin2 = exCoureur("Admin2", "ADMIN", 4);
        List<Map<String, Object>> avant = etatBase();

        assertProbleme(supprimer(nadia, admin2), 403, "Accès refusé", "ACCES_REFUSE",
                "Vous n'avez pas les droits nécessaires.");

        assertThat(etatBase()).isEqualTo(avant);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : bénévole ex-coureur (Inscription TERMINEE) affecté à C EN_COURS : 409 BENEVOLE_AFFECTE_COURSE_EN_COURS au corps du 9.3, rien n'est modifié (Compte, empreinte, affectations, sessions) ; après retrait de C par PUT, DELETE 204 et Compte anonymisé")
    void ca8_refus_course_en_cours_puis_anonymisation() throws Exception {
        UUID alice2 = exCoureur("Alice2", "BENEVOLE", 7);
        affecter(c, alice2);
        affecter(p, alice2);
        Session session = api.ouvrir("Alice2", MOT_DE_PASSE_COUREUR);
        List<Map<String, Object>> avant = etatBase();
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");

        HttpResponse<String> refus = supprimer(nadia, alice2);

        assertProbleme(refus, 409, "Conflit", "BENEVOLE_AFFECTE_COURSE_EN_COURS",
                "Ce bénévole est affecté à la course en cours « " + NOM_C + " ». "
                        + "Retirez-le de cette course avant de le supprimer.");
        assertThat(refus.body()).doesNotContain("Alice2");
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(jdbc.queryForObject("select empreinte_mot_de_passe is not null from compte where id = ?",
                Boolean.class, alice2)).isTrue();
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(lire(session, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(lignes("Bénévole anonymisé")).isEmpty();

        assertThat(affecterVia(nadia, c).statusCode()).isEqualTo(200);
        assertThat(supprimer(nadia, alice2).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, alice2))
                .isEqualTo(ANONYME);
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where benevole_id = ?",
                Integer.class, alice2)).isZero();
        assertThat(lire(session, "/api/benevole/courses").statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : un Compte anonymisé (ex-bénévole) est inerte : absent des listes bénévoles et admins, introuvable par la recherche de coureurs (fragment de clé, « anonyme », « coureur »), 404 COMPTE_INTROUVABLE sur DELETE/désactivation/réactivation des deux familles, affectation refusée comme un identifiant inconnu, base inchangée")
    void ca9_compte_anonymise_inerte() throws Exception {
        UUID alice2 = exCoureur("Alice2", "BENEVOLE", 7);
        assertThat(supprimer(nadia, alice2).statusCode()).isEqualTo(204);
        UUID inconnu = UUID.fromString("00000000-0000-0000-0000-000000000000");
        String cle = jdbc.queryForObject("select pseudo_normalise from compte where id = ?", String.class, alice2);
        List<Map<String, Object>> avant = etatBase();

        assertThat(pseudos(json.readTree(lire(nadia, BENEVOLES).body()))).containsExactly("Marc");
        assertThat(pseudos(json.readTree(lire(patron, ADMINS).body()))).containsExactly("Nadia");
        for (String fragment : List.of(cle.substring(1, 5), cle.substring(1, 9), "anonyme", "coureur", "Coureur anonyme")) {
            JsonNode corps = json.readTree(lire(nadia, RECHERCHE + "?q=" + enc(fragment)).body());
            assertThat(pseudos(corps)).as(fragment).doesNotContain(ANONYME);
            corps.get("coureurs").forEach(r -> assertThat(r.get("id").asString()).isNotEqualTo(alice2.toString()));
        }

        for (String famille : List.of(BENEVOLES, ADMINS)) {
            Session appelant = famille.equals(ADMINS) ? patron : nadia;
            assertProbleme(api.requete("DELETE", famille + "/" + alice2, appelant.entetes(), null, null), 404,
                    "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
            assertProbleme(api.requete("POST", famille + "/" + alice2 + "/desactivation", appelant.entetes(), null,
                    null), 404, "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
            assertProbleme(api.requete("POST", famille + "/" + alice2 + "/reactivation", appelant.entetes(), null,
                    null), 404, "Introuvable", "COMPTE_INTROUVABLE", INTROUVABLE_DETAIL);
        }

        HttpResponse<String> anonymise = affecterVia(nadia, p2, alice2);
        HttpResponse<String> inexistant = affecterVia(nadia, p2, inconnu);
        assertThat(anonymise.statusCode()).isEqualTo(inexistant.statusCode()).isGreaterThanOrEqualTo(400);
        assertThat(anonymise.statusCode()).isLessThan(500);
        assertThat(sansIdentifiants(anonymise.body(), alice2)).isEqualTo(sansIdentifiants(inexistant.body(), inconnu));
        assertThat(affectationsDe(p2)).isEmpty();
        assertThat(etatBase()).isEqualTo(avant);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 (a) : deux DELETE simultanés du même bénévole ex-coureur : exactement un 204 et un 404, jamais de 500, Compte anonymisé et Inscription conservée (20 répétitions)")
    void ca10_deux_suppressions_simultanees() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = exCoureur("Concurrent" + i, "BENEVOLE", 10 + i);
            affecter(p, cible);
            CountDownLatch depart = new CountDownLatch(1);
            Callable<HttpResponse<String>> action = () -> {
                depart.await();
                return supprimer(nadia, cible);
            };

            List<HttpResponse<String>> reponses = enParallele(depart, action, action);

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(204, 404);
            assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, cible))
                    .isEqualTo(ANONYME);
            assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                    cible)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where benevole_id = ?",
                    Integer.class, cible)).isZero();
        }
    }

    @Test
    @DisplayName("CA10 (b) : DELETE et désactivation simultanés : jamais de 500, DELETE 204, désactivation 204 ou 404, Compte anonymisé, Inscription conservée, aucune affectation restante (20 répétitions)")
    void ca10_suppression_et_desactivation_simultanees() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = exCoureur("Desact" + i, "BENEVOLE", 40 + i);
            affecter(p, cible);
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
            assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, cible))
                    .isEqualTo(ANONYME);
            assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                    cible)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from affectation_benevole where benevole_id = ?",
                    Integer.class, cible)).isZero();
        }
    }

    // ---------------------------------------------------------------- CA5 / CA7 journaux

    @Test
    @DisplayName("CA5 : journal d'une anonymisation de bénévole : « Bénévole anonymisé (compte <id> par <id>, 2 affectation(s) retirée(s)) » une fois, aucune ligne « supprimé » ; aucun pseudo, nom de Course ni mot de passe en INFO ou plus")
    void ca5_journal_sans_donnees_personnelles() throws Exception {
        UUID alice2 = exCoureur("Alice2", "BENEVOLE", 7);
        affecter(t, alice2);
        affecter(p, alice2);

        assertThat(supprimer(nadia, alice2).statusCode()).isEqualTo(204);

        assertThat(lignes("Bénévole anonymisé")).containsExactly("Bénévole anonymisé (compte " + alice2 + " par "
                + idNadia + ", 2 affectation(s) retirée(s))");
        assertThat(lignes("Bénévole supprimé")).isEmpty();
        for (ILoggingEvent evenement : journal.evenements()) {
            String texte = evenement.getFormattedMessage() + " " + evenement.getMDCPropertyMap()
                    + (evenement.getThrowableProxy() == null ? "" : evenement.getThrowableProxy().getMessage());
            assertThat(texte).as(evenement.getLevel() + " " + evenement.getLoggerName())
                    .doesNotContain(MOT_DE_PASSE_COUREUR).doesNotContain(MOT_DE_PASSE_ADMIN)
                    .doesNotContain(MOT_DE_PASSE_PATRON).doesNotContain(MOT_DE_PASSE_BENEVOLE);
            if (evenement.getLevel().isGreaterOrEqual(Level.INFO)
                    && evenement.getLoggerName().startsWith("fr.backyard.tracker")) {
                assertThat(texte).as("INFO+ " + evenement.getLoggerName()).doesNotContain("Alice2")
                        .doesNotContain("Nadia").doesNotContain("Marc").doesNotContain("Preparation")
                        .doesNotContain("Course terminee").doesNotContain(NOM_C);
            }
        }
    }

    // ---------------------------------------------------------------- CA11 (côté API / schéma)

    @Test
    @DisplayName("CA11 : aucun nouveau changeset (0002 à 0011-compte-actif dans l'ordre) ; aucune Inscription orpheline après anonymisation")
    void ca11_schema_inchange_et_pas_d_inscription_orpheline() throws Exception {
        UUID alice2 = exCoureur("Alice2", "BENEVOLE", 7);
        UUID admin2 = exCoureur("Admin2", "ADMIN", 4);
        assertThat(supprimer(nadia, alice2).statusCode()).isEqualTo(204);
        assertThat(supprimer(patron, admin2).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course", "0009-passage",
                        "0010-abandon-inscription", "0011-compte-actif");
        assertThat(jdbc.queryForObject("select count(*) from inscription i left join compte k on k.id = i.compte_id "
                + "where k.id is null", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- utilitaires

    /** Ancien coureur : Compte COUREUR inscrit à T (VAINQUEUR, Passage SCAN) puis passé au rôle indiqué par SQL. */
    private UUID exCoureur(String pseudo, String role, int dossard) {
        UUID id = insererCompte(pseudo, "COUREUR", MOT_DE_PASSE_COUREUR);
        UUID inscription = inscrire(t, id, dossard, "VAINQUEUR");
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) "
                + "values (?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscription,
                Timestamp.from(Instant.parse("2026-10-08T08:40:00Z")));
        jdbc.update("update compte set role = ? where id = ?", role, id);
        return id;
    }

    private UUID insererCompte(String pseudo, String role, String motDePasse) {
        compteur++;
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(motDePasse), role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z").plusSeconds(60L * compteur)));
        return id;
    }

    private UUID insererCourse(String nom, String date, String statut, Instant demarreeLe) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf(date), statut,
                demarreeLe == null ? null : Timestamp.from(demarreeLe));
        return id;
    }

    private UUID inscrire(UUID course, UUID compte, int dossard, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, ?)", id, course, compte, dossard, UUID.randomUUID().toString(), statut);
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
        etat.addAll(jdbc.queryForList("select * from inscription order by id"));
        return etat;
    }

    private HttpResponse<String> supprimer(Session session, UUID id) throws Exception {
        boolean admin = jdbc.queryForObject("select role from compte where id = ?", String.class, id).equals("ADMIN")
                || id.equals(idPatron);
        return api.requete("DELETE", (admin ? ADMINS : BENEVOLES) + "/" + id, session.entetes(), null, null);
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

    private static String enc(String valeur) {
        return java.net.URLEncoder.encode(valeur, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sansIdentifiants(String corps, UUID id) {
        return corps.replace(id.toString(), "ID");
    }

    private List<String> pseudos(JsonNode liste) {
        List<String> valeurs = new ArrayList<>();
        JsonNode elements = liste.has("coureurs") ? liste.get("coureurs") : liste;
        elements.forEach(compte -> valeurs.add(compte.get("pseudo").asString()));
        return valeurs;
    }

    private List<String> lignes(String prefixe) {
        return journal.evenementsApplicatifs().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(prefixe)).toList();
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
