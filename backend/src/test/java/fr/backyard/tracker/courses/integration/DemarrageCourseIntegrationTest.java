package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.OctetsDeLogo;
import fr.backyard.tracker.courses.infrastructure.CourseJpaAdapter;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.slf4j.LoggerFactory;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 4.1 : POST /api/administration/courses/{id}/demarrage (CA5 à CA13). Le contrat vient de la
 * spec : l'horloge de l'application est fixée au 9 octobre 2026 à 16:03:27 à Paris. Le schéma (CA4) est vérifié dans
 * {@code DemarrageCourseSchemaRedemarrageIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class DemarrageCourseIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Instant MAINTENANT = Instant.parse("2026-10-09T14:03:27.654Z");
    static final String DEMARREE_LE = "2026-10-09T14:03:27Z";
    static final String JOUR = "2026-10-09";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String NON_DEMARRABLE_DETAIL =
            "La course n'est plus en préparation : elle ne peut plus être démarrée.";
    static final String HORS_DATE_DETAIL = "La course ne peut être démarrée que le jour de sa date.";
    static final String SANS_INSCRIT_DETAIL = "La course ne peut pas être démarrée : aucun coureur n'est inscrit.";
    static final List<String> CHAMPS_FICHE = List.of("id", "nom", "date", "statut", "distanceBoucleMetres",
            "dureeBoucleMinutes", "denivelePositifBoucleMetres", "nombreMaxParticipants", "nombreMaxBoucles", "logoUrl",
            "benevoleIds", "demarreeLe");
    static final List<String> CHAMPS_COURSE = CHAMPS_FICHE.subList(0, 10);
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles) "
            + "values (?, ?, ?, ?, 400, 60, 0, 10, 5)";

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
    CourseJpaAdapter depotCourses;

    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    ListAppender<ILoggingEvent> journal;
    Session patron;
    Session nadia;
    Session alice;
    Session bruno;
    Session chloe;
    Session leo;
    Session marc;
    UUID idBruno;
    UUID idLeo;
    UUID idMarc;

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
        insererCompte("Nadia", "ADMIN");
        insererCompte("Alice", "COUREUR");
        idBruno = insererCompte("Bruno", "COUREUR");
        insererCompte("Chloé", "COUREUR");
        idLeo = insererCompte("Léo", "BENEVOLE");
        idMarc = insererCompte("Marc", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        chloe = api.ouvrir("Chloé", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        marc = api.ouvrir("Marc", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    /** X : logo, bénévole Léo, inscrits Bruno (dossard 1) et Chloé (2). Y : un inscrit, Bruno (1). */
    private record Scenario(String x, String y) {
    }

    private Scenario scenario() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", JOUR);
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo).statusCode()).isEqualTo(200);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, x).statusCode()).isEqualTo(201);
        String y = creerCourse(patron, "Autre course", JOUR);
        assertThat(sinscrire(bruno, y).statusCode()).isEqualTo(201);
        return new Scenario(x, y);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : Patron démarre X : 200 à douze champs, EN_COURS, demarreeLe tronqué à la seconde, base et fiche cohérentes, Y, logo, affectation et inscriptions de X intacts")
    void ca5_demarrage_nominal() throws Exception {
        Scenario s = scenario();
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList(
                "select * from inscription where course_id = ? order by dossard", UUID.fromString(s.x));
        List<Map<String, Object>> inscriptionsYAvant = jdbc.queryForList(
                "select * from inscription where course_id = ?", UUID.fromString(s.y));
        Map<String, Object> ligneYAvant = jdbc.queryForMap("select * from course where id = ?", UUID.fromString(s.y));
        JsonNode ficheAvant = json.readTree(lire(patron, s.x).body());
        assertThat(ficheAvant.get("demarreeLe").isNull()).isTrue();

        HttpResponse<String> reponse = demarrer(patron, s.x);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json").doesNotContain("problem"));
        JsonNode fiche = json.readTree(reponse.body());
        assertThat(fiche.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_FICHE);
        assertThat(fiche.get("id").asString()).isEqualTo(s.x);
        assertThat(fiche.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(fiche.get("demarreeLe").asString()).isEqualTo(DEMARREE_LE);
        for (String champ : List.of("nom", "date", "distanceBoucleMetres", "dureeBoucleMinutes",
                "denivelePositifBoucleMetres", "nombreMaxParticipants", "nombreMaxBoucles", "logoUrl", "benevoleIds")) {
            assertThat(fiche.get(champ)).as(champ).isEqualTo(ficheAvant.get(champ));
        }
        assertThat(fiche.get("benevoleIds")).hasSize(1);
        assertThat(fiche.get("distanceBoucleMetres").asInt()).isEqualTo(6706);

        Map<String, Object> ligne = jdbc.queryForMap("select * from course where id = ?", UUID.fromString(s.x));
        assertThat(ligne.get("statut")).isEqualTo("EN_COURS");
        assertThat(demarreeLeEnBase(s.x)).isEqualTo(Instant.parse(fiche.get("demarreeLe").asString()));
        assertThat(ligne.get("distance_boucle_metres")).isEqualTo(6706);
        assertThat(ligne.get("duree_boucle_minutes")).isEqualTo(60);
        assertThat(ligne.get("denivele_positif_boucle_metres")).isEqualTo(120);
        assertThat(ligne.get("nombre_max_participants")).isEqualTo(50);
        assertThat(ligne.get("nombre_max_boucles")).isEqualTo(24);

        JsonNode relue = json.readTree(lire(patron, s.x).body());
        assertThat(relue).isEqualTo(fiche);
        JsonNode liste = json.readTree(api.requete("GET", CHEMIN, patron.enteteLecture(), null, null).body());
        JsonNode xListe = parId(liste, s.x);
        assertThat(xListe.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COURSE);
        assertThat(xListe.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(parId(liste, s.y).get("statut").asString()).isEqualTo("EN_PREPARATION");

        assertThat(jdbc.queryForMap("select * from course where id = ?", UUID.fromString(s.y)))
                .isEqualTo(ligneYAvant);
        assertThat(jdbc.queryForList("select * from inscription where course_id = ?", UUID.fromString(s.y)))
                .isEqualTo(inscriptionsYAvant);
        assertThat(lireLogoPublic(s.x).statusCode()).isEqualTo(200);
        assertThat(affectationsDe(s.x)).containsExactly(idLeo);
        List<Map<String, Object>> inscriptionsApres = jdbc.queryForList(
                "select * from inscription where course_id = ? order by dossard", UUID.fromString(s.x));
        assertThat(inscriptionsApres).isEqualTo(inscriptionsAvant).hasSize(2);
        assertThat(inscriptionsApres).extracting(i -> i.get("dossard")).containsExactly(1, 2);
        assertThat(inscriptionsApres).extracting(i -> i.get("statut")).containsOnly("EN_COURSE");
    }

    @Test
    @DisplayName("CA5 : Nadia (ADMIN, pas créatrice) démarre Y (une seule inscription) en 200 ; un corps injecté (demarreeLe, statut) est ignoré")
    void ca5_admin_une_inscription_et_corps_ignore() throws Exception {
        Scenario s = scenario();

        HttpResponse<String> reponse = api.requete("POST", CHEMIN + "/" + s.y + "/demarrage", nadia.entetes(),
                "application/json", "{\"demarreeLe\":\"2020-01-01T00:00:00Z\",\"statut\":\"TERMINEE\"}");

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode fiche = json.readTree(reponse.body());
        assertThat(fiche.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(fiche.get("demarreeLe").asString()).isEqualTo(DEMARREE_LE);
        assertThat(demarreeLeEnBase(s.y)).isEqualTo(Instant.parse(DEMARREE_LE));
        assertThat(statutEnBase(s.x)).isEqualTo("EN_PREPARATION");
        assertThat(demarreeLeEnBase(s.x)).isNull();
    }

    @Test
    @DisplayName("CA5 : une Course complète (inscrits = max) est démarrable ; deux Courses du jour se démarrent indépendamment, chacune avec son demarreeLe")
    void ca5_course_complete_et_courses_independantes() throws Exception {
        String pleine = creerCourse(patron, "Pleine", JOUR, 1);
        assertThat(sinscrire(bruno, pleine).statusCode()).isEqualTo(201);
        String autre = creerCourse(patron, "Autre", JOUR);
        assertThat(sinscrire(chloe, autre).statusCode()).isEqualTo(201);

        assertThat(demarrer(patron, pleine).statusCode()).isEqualTo(200);
        horloge.fixer(Instant.parse("2026-10-09T15:10:00.999Z"));
        assertThat(demarrer(patron, autre).statusCode()).isEqualTo(200);

        assertThat(demarreeLeEnBase(pleine)).isEqualTo(Instant.parse(DEMARREE_LE));
        assertThat(demarreeLeEnBase(autre)).isEqualTo(Instant.parse("2026-10-09T15:10:00Z"));
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : second démarrage de X : 409 COURSE_NON_DEMARRABLE, demarree_le d'origine conservé ; Course TERMINEE ou EN_COURS hors date : même 409, rien modifié")
    void ca6_course_deja_demarree_ou_terminee() throws Exception {
        Scenario s = scenario();
        assertThat(demarrer(patron, s.x).statusCode()).isEqualTo(200);
        horloge.fixer(Instant.parse("2026-10-09T16:00:00Z"));

        assertErreur(demarrer(patron, s.x), 409, "COURSE_NON_DEMARRABLE", "Conflit", NON_DEMARRABLE_DETAIL);
        assertThat(demarreeLeEnBase(s.x)).isEqualTo(Instant.parse(DEMARREE_LE));
        assertThat(statutEnBase(s.x)).isEqualTo("EN_COURS");

        UUID terminee = insererCourse("Terminée", "2026-10-09", "TERMINEE");
        assertErreur(demarrer(patron, terminee.toString()), 409, "COURSE_NON_DEMARRABLE", "Conflit",
                NON_DEMARRABLE_DETAIL);
        assertThat(statutEnBase(terminee.toString())).isEqualTo("TERMINEE");
        assertThat(demarreeLeEnBase(terminee.toString())).isNull();

        UUID horsDate = insererCourse("En cours hors date", "2026-10-01", "EN_COURS");
        assertErreur(demarrer(patron, horsDate.toString()), 409, "COURSE_NON_DEMARRABLE", "Conflit",
                NON_DEMARRABLE_DETAIL);
        assertThat(demarreeLeEnBase(horsDate.toString())).isNull();
        assertThat(statutEnBase(s.y)).isEqualTo("EN_PREPARATION");
    }

    @Test
    @DisplayName("CA6 : id inconnu et id non UUID : 404 COURSE_INTROUVABLE ; aucune ligne créée ni modifiée")
    void ca6_introuvable() throws Exception {
        scenario();
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");

        for (String id : List.of(ID_INCONNU, "inexistant")) {
            assertErreur(demarrer(patron, id), 404, "COURSE_INTROUVABLE", "Introuvable", "La course est introuvable.");
        }

        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : veille (V) et lendemain (L) avec inscription : 409 COURSE_HORS_DATE, restent EN_PREPARATION sans demarree_le ; les detail n'ont ni nom, ni identifiant")
    void ca7_hors_date() throws Exception {
        UUID v = insererCourse("Veille secrète", "2026-10-08", "EN_PREPARATION");
        insererInscription(v, UUID.randomUUID(), 1);
        String l = creerCourse(patron, "Lendemain secret", "2026-10-10");
        assertThat(sinscrire(bruno, l).statusCode()).isEqualTo(201);

        for (String id : List.of(v.toString(), l)) {
            HttpResponse<String> reponse = demarrer(patron, id);
            assertErreur(reponse, 409, "COURSE_HORS_DATE", "Conflit", HORS_DATE_DETAIL);
            assertThat(json.readTree(reponse.body()).get("detail").asString()).doesNotContain("secr").doesNotContain(id);
            assertThat(statutEnBase(id)).isEqualTo("EN_PREPARATION");
            assertThat(demarreeLeEnBase(id)).isNull();
        }
    }

    @Test
    @DisplayName("CA7 : Course du jour sans inscription (W) : 409 COURSE_SANS_INSCRIT ; hors date et sans inscription : COURSE_HORS_DATE (date avant inscrits)")
    void ca7_sans_inscrit_et_ordre_des_controles() throws Exception {
        String w = creerCourse(patron, "Vide", JOUR);
        String videHorsDate = creerCourse(patron, "Vide demain", "2026-10-10");

        assertErreur(demarrer(patron, w), 409, "COURSE_SANS_INSCRIT", "Conflit", SANS_INSCRIT_DETAIL);
        assertErreur(demarrer(patron, videHorsDate), 409, "COURSE_HORS_DATE", "Conflit", HORS_DATE_DETAIL);
        assertThat(statutEnBase(w)).isEqualTo("EN_PREPARATION");
        assertThat(demarreeLeEnBase(w)).isNull();
    }

    @Test
    @DisplayName("CA7 : une Course dont la dernière inscription vient d'être supprimée par désinscription (204) donne 409 COURSE_SANS_INSCRIT")
    void ca7_derniere_inscription_desinscrite() throws Exception {
        String z = creerCourse(patron, "Une seule", JOUR);
        assertThat(sinscrire(bruno, z).statusCode()).isEqualTo(201);
        assertThat(seDesinscrire(bruno, idInscription(z, idBruno)).statusCode()).isEqualTo(204);

        assertErreur(demarrer(patron, z), 409, "COURSE_SANS_INSCRIT", "Conflit", SANS_INSCRIT_DETAIL);
        assertThat(statutEnBase(z)).isEqualTo("EN_PREPARATION");
    }

    @Test
    @DisplayName("CA7 : à 22:30Z le 9 (00:30 le 10 à Paris), X (09) donne 409 COURSE_HORS_DATE et L (10) est démarrée en 200")
    void ca7_fuseau_de_paris() throws Exception {
        Scenario s = scenario();
        String l = creerCourse(patron, "Lendemain", "2026-10-10");
        assertThat(sinscrire(chloe, l).statusCode()).isEqualTo(201);
        horloge.fixer(Instant.parse("2026-10-09T22:30:00Z"));

        assertErreur(demarrer(patron, s.x), 409, "COURSE_HORS_DATE", "Conflit", HORS_DATE_DETAIL);
        HttpResponse<String> reponse = demarrer(patron, l);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body()).get("demarreeLe").asString()).isEqualTo("2026-10-09T22:30:00Z");
        assertThat(statutEnBase(s.x)).isEqualTo("EN_PREPARATION");
    }

    @Test
    @DisplayName("CA7 : à 21:59:59Z le 9 (23:59:59 à Paris), X (09) est démarrée en 200 : un démarrage juste avant minuit est accepté")
    void ca7_juste_avant_minuit_a_paris() throws Exception {
        Scenario s = scenario();
        horloge.fixer(Instant.parse("2026-10-09T21:59:59Z"));

        HttpResponse<String> reponse = demarrer(patron, s.x);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body()).get("demarreeLe").asString()).isEqualTo("2026-10-09T21:59:59Z");
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : anonyme 401 NON_AUTHENTIFIE ; Alice (COUREUR) et Léo (BENEVOLE affecté) 403 ACCES_REFUSE, même sur id inconnu, V ou W ; X intacte")
    void ca8_authentification_et_roles() throws Exception {
        Scenario s = scenario();
        UUID v = insererCourse("Veille", "2026-10-08", "EN_PREPARATION");
        String w = creerCourse(patron, "Vide", JOUR);
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        assertErreur(api.requete("POST", CHEMIN + "/" + s.x + "/demarrage", anonyme, null, null), 401,
                "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        for (Session session : List.of(alice, leo)) {
            for (String cible : List.of(s.x, ID_INCONNU, "inexistant", v.toString(), w)) {
                assertErreur(demarrer(session, cible), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
            }
        }
        assertThat(statutEnBase(s.x)).isEqualTo("EN_PREPARATION");
        assertThat(demarreeLeEnBase(s.x)).isNull();
    }

    @Test
    @DisplayName("CA8 : sans X-XSRF-TOKEN, avec un jeton différent ou sans cookie, Patron reçoit 403 CSRF_INVALIDE avant tout autre contrôle ; X intacte")
    void ca8_csrf() throws Exception {
        Scenario s = scenario();
        List<Map<String, String>> variantes = List.of(patron.enteteLecture(),
                Map.of("Cookie", "JSESSIONID=" + patron.id() + "; XSRF-TOKEN=" + patron.xsrf(), "X-XSRF-TOKEN",
                        "autre-valeur"),
                Map.of("X-XSRF-TOKEN", patron.xsrf()));

        for (Map<String, String> entetes : variantes) {
            for (String cible : List.of(s.x, ID_INCONNU)) {
                assertErreur(api.requete("POST", CHEMIN + "/" + cible + "/demarrage", entetes, null, null), 403,
                        "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
            }
        }
        assertThat(statutEnBase(s.x)).isEqualTo("EN_PREPARATION");
        assertThat(demarreeLeEnBase(s.x)).isNull();
    }

    @Test
    @DisplayName("CA8 : GET, PUT, PATCH et DELETE sur .../demarrage par Patron donnent 404 ou 405, jamais 2xx ; X intacte")
    void ca8_autres_methodes() throws Exception {
        Scenario s = scenario();

        for (String methode : List.of("GET", "PUT", "PATCH", "DELETE")) {
            HttpResponse<String> reponse = api.requete(methode, CHEMIN + "/" + s.x + "/demarrage",
                    "GET".equals(methode) ? patron.enteteLecture() : patron.entetes(), "application/json",
                    "GET".equals(methode) || "DELETE".equals(methode) ? null : "{}");
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        assertThat(statutEnBase(s.x)).isEqualTo("EN_PREPARATION");
        assertThat(demarreeLeEnBase(s.x)).isNull();
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : X démarrée, PUT de la Course, PUT et DELETE du logo : 409 COURSE_NON_MODIFIABLE, base et logo inchangés ; DELETE de la Course par Patron : 409 COURSE_NON_SUPPRIMABLE")
    void ca9_parametres_figes_et_non_supprimable() throws Exception {
        Scenario s = scenario();
        assertThat(demarrer(patron, s.x).statusCode()).isEqualTo(200);
        Map<String, Object> ligneAvant = jdbc.queryForMap("select * from course where id = ?", UUID.fromString(s.x));
        ObjectNode corps = corpsCourse("Backyard des Crêtes", JOUR);
        corps.put("distanceBoucleMetres", 7000);

        assertThat(code(api.requete("PUT", CHEMIN + "/" + s.x, patron.entetes(), "application/json",
                corps.toString()), 409)).isEqualTo("COURSE_NON_MODIFIABLE");
        assertThat(code(reponseTexte(envoiLogoBrut(patron, s.x)), 409)).isEqualTo("COURSE_NON_MODIFIABLE");
        assertThat(code(api.requete("DELETE", CHEMIN + "/" + s.x + "/logo", patron.entetes(), null, null), 409))
                .isEqualTo("COURSE_NON_MODIFIABLE");
        assertErreur(api.requete("DELETE", CHEMIN + "/" + s.x, patron.entetes(), null, null), 409,
                "COURSE_NON_SUPPRIMABLE", "Conflit",
                "La course n'est plus en préparation : elle ne peut plus être supprimée.");

        assertThat(jdbc.queryForMap("select * from course where id = ?", UUID.fromString(s.x)))
                .isEqualTo(ligneAvant);
        assertThat(compter("logo_course", s.x)).isEqualTo(1);
        assertThat(lireLogoPublic(s.x).statusCode()).isEqualTo(200);
        assertThat(affectationsDe(s.x)).containsExactly(idLeo);
    }

    @Test
    @DisplayName("CA9 : X démarrée, PUT .../benevoles avec [Léo, Marc] : 200, affectations mises à jour, demarreeLe renvoyé et inchangé")
    void ca9_benevoles_toujours_affectables() throws Exception {
        Scenario s = scenario();
        assertThat(demarrer(patron, s.x).statusCode()).isEqualTo(200);

        HttpResponse<String> reponse = affecter(patron, s.x, idLeo, idMarc);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode fiche = json.readTree(reponse.body());
        assertThat(fiche.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_FICHE);
        assertThat(fiche.get("demarreeLe").asString()).isEqualTo(DEMARREE_LE);
        assertThat(fiche.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(affectationsDe(s.x)).containsExactlyInAnyOrder(idLeo, idMarc);
        assertThat(demarreeLeEnBase(s.x)).isEqualTo(Instant.parse(DEMARREE_LE));
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : X démarrée, inscription d'Alice : 409 COURSE_NON_OUVERTE, aucune ligne créée ; X absente des courses ouvertes d'Alice, Y présente")
    void ca10_inscriptions_fermees() throws Exception {
        Scenario s = scenario();
        assertThat(demarrer(patron, s.x).statusCode()).isEqualTo(200);
        int avant = jdbc.queryForObject("select count(*) from inscription", Integer.class);

        assertErreur(sinscrire(alice, s.x), 409, "COURSE_NON_OUVERTE", "Conflit",
                "La course n'est plus ouverte aux inscriptions.");

        assertThat(jdbc.queryForObject("select count(*) from inscription", Integer.class)).isEqualTo(avant);
        assertThat(jdbc.queryForObject("select max(dossard) from inscription where course_id = ?", Integer.class,
                UUID.fromString(s.x))).isEqualTo(2);
        List<String> ouvertes = ids(api.requete("GET", "/api/coureur/courses", alice.enteteLecture(), null, null));
        assertThat(ouvertes).contains(s.y).doesNotContain(s.x);
    }

    @Test
    @DisplayName("CA10 : X démarrée, Bruno ne peut plus se désinscrire (409 DESINSCRIPTION_IMPOSSIBLE), son inscription est intacte dans « mes inscriptions » ; celle de Y reste désinscriptible (204)")
    void ca10_desinscription_fermee_et_inscriptions_intactes() throws Exception {
        Scenario s = scenario();
        String inscriptionX = idInscription(s.x, idBruno);
        String jeton = jdbc.queryForObject("select jeton_qr from inscription where id = ?", String.class,
                UUID.fromString(inscriptionX));
        assertThat(demarrer(patron, s.x).statusCode()).isEqualTo(200);

        assertErreur(seDesinscrire(bruno, inscriptionX), 409, "DESINSCRIPTION_IMPOSSIBLE", "Conflit",
                "La course n'est plus en préparation : la désinscription est impossible.");

        assertThat(compter("inscription", s.x)).isEqualTo(2);
        JsonNode mesInscriptions = json.readTree(
                api.requete("GET", "/api/coureur/inscriptions", bruno.enteteLecture(), null, null).body());
        JsonNode celleDeX = null;
        for (JsonNode i : mesInscriptions) {
            if (inscriptionX.equals(i.get("id").asString())) {
                celleDeX = i;
            }
        }
        assertThat(celleDeX).isNotNull();
        assertThat(celleDeX.get("dossard").asInt()).isEqualTo(1);
        assertThat(celleDeX.get("jetonQr").asString()).isEqualTo(jeton);
        assertThat(celleDeX.get("statut").asString()).isEqualTo("EN_COURSE");
        assertThat(seDesinscrire(bruno, idInscription(s.y, idBruno)).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("CA10 : X démarrée, Léo la voit EN_COURS dans ses courses de bénévole ; la liste des inscrits de X garde deux lignes (dossards 1 et 2, EN_COURSE)")
    void ca10_benevole_et_inscrits_admin() throws Exception {
        Scenario s = scenario();
        assertThat(demarrer(patron, s.x).statusCode()).isEqualTo(200);

        JsonNode mesCourses = json.readTree(
                api.requete("GET", "/api/benevole/courses", leo.enteteLecture(), null, null).body());
        assertThat(parId(mesCourses, s.x).get("statut").asString()).isEqualTo("EN_COURS");

        JsonNode inscrits = json.readTree(
                api.requete("GET", CHEMIN + "/" + s.x + "/inscriptions", patron.enteteLecture(), null, null).body());
        assertThat(inscrits.get("inscrits")).hasSize(2);
        assertThat(inscrits.get("inscrits")).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2);
        assertThat(inscrits.get("inscrits")).extracting(n -> n.get("statut").asString()).containsOnly("EN_COURSE");
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : deux démarrages simultanés : exactement un 200 et un 409 COURSE_NON_DEMARRABLE, jamais 500, demarree_le est celui du 200")
    void ca11_deux_demarrages_simultanes() throws Exception {
        for (int tour = 0; tour < 6; tour++) {
            String c = courseAvecInscrit("Concurrence " + tour, bruno);

            List<HttpResponse<String>> reponses = enParallele(() -> demarrer(patron, c), () -> demarrer(patron, c));

            assertThat(reponses).extracting(HttpResponse::statusCode).as("tour " + tour)
                    .containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> perdante = reponses.stream().filter(r -> r.statusCode() == 409).findFirst().get();
            assertThat(json.readTree(perdante.body()).get("code").asString()).isEqualTo("COURSE_NON_DEMARRABLE");
            HttpResponse<String> gagnante = reponses.stream().filter(r -> r.statusCode() == 200).findFirst().get();
            assertThat(demarreeLeEnBase(c)).isEqualTo(
                    Instant.parse(json.readTree(gagnante.body()).get("demarreeLe").asString()));
        }
    }

    @Test
    @DisplayName("CA11 : démarrage et inscription d'Alice simultanés : 201 (inscription existante, X EN_COURS) ou 409 COURSE_NON_OUVERTE (aucune inscription d'Alice) ; jamais 500")
    void ca11_demarrage_et_inscription_simultanes() throws Exception {
        for (int tour = 0; tour < 6; tour++) {
            String c = courseAvecInscrit("Concurrence " + tour, bruno);

            List<HttpResponse<String>> reponses = enParallele(() -> demarrer(patron, c), () -> sinscrire(alice, c));

            assertThat(reponses.get(0).statusCode()).as("démarrage, tour " + tour).isEqualTo(200);
            assertThat(statutEnBase(c)).isEqualTo("EN_COURS");
            int inscriptionsAlice = jdbc.queryForObject(
                    "select count(*) from inscription i join compte k on k.id = i.compte_id "
                            + "where i.course_id = ? and k.pseudo = 'Alice'", Integer.class, UUID.fromString(c));
            if (reponses.get(1).statusCode() == 201) {
                assertThat(inscriptionsAlice).as("tour " + tour).isEqualTo(1);
            } else {
                assertErreur(reponses.get(1), 409, "COURSE_NON_OUVERTE", "Conflit",
                        "La course n'est plus ouverte aux inscriptions.");
                assertThat(inscriptionsAlice).as("tour " + tour).isZero();
            }
        }
    }

    @Test
    @DisplayName("CA11 : démarrage et désinscription du seul inscrit simultanés : (204 puis 409 COURSE_SANS_INSCRIT, EN_PREPARATION) ou (200 puis 409 DESINSCRIPTION_IMPOSSIBLE, inscription conservée) ; jamais EN_COURS sans inscription")
    void ca11_demarrage_et_desinscription_du_dernier_inscrit() throws Exception {
        for (int tour = 0; tour < 8; tour++) {
            String c = courseAvecInscrit("Concurrence " + tour, bruno);
            String inscription = idInscription(c, idBruno);

            List<HttpResponse<String>> reponses = enParallele(() -> demarrer(patron, c),
                    () -> seDesinscrire(bruno, inscription));

            int inscrits = compter("inscription", c);
            if (reponses.get(0).statusCode() == 200) {
                assertErreur(reponses.get(1), 409, "DESINSCRIPTION_IMPOSSIBLE", "Conflit",
                        "La course n'est plus en préparation : la désinscription est impossible.");
                assertThat(statutEnBase(c)).as("tour " + tour).isEqualTo("EN_COURS");
                assertThat(inscrits).isEqualTo(1);
            } else {
                assertErreur(reponses.get(0), 409, "COURSE_SANS_INSCRIT", "Conflit", SANS_INSCRIT_DETAIL);
                assertThat(reponses.get(1).statusCode()).isEqualTo(204);
                assertThat(statutEnBase(c)).as("tour " + tour).isEqualTo("EN_PREPARATION");
                assertThat(inscrits).isZero();
            }
        }
    }

    @Test
    @DisplayName("CA11 : démarrage et suppression simultanés : (200 puis 409 COURSE_NON_SUPPRIMABLE) ou (204 puis 404), jamais 500")
    void ca11_demarrage_et_suppression_simultanes() throws Exception {
        for (int tour = 0; tour < 8; tour++) {
            String c = courseAvecInscrit("Concurrence " + tour, bruno);

            List<HttpResponse<String>> reponses = enParallele(() -> demarrer(patron, c),
                    () -> api.requete("DELETE", CHEMIN + "/" + c, patron.entetes(), null, null));

            if (reponses.get(0).statusCode() == 200) {
                assertErreur(reponses.get(1), 409, "COURSE_NON_SUPPRIMABLE", "Conflit",
                        "La course n'est plus en préparation : elle ne peut plus être supprimée.");
                assertThat(compter("course", c)).isEqualTo(1);
                assertThat(statutEnBase(c)).isEqualTo("EN_COURS");
            } else {
                assertThat(reponses.get(1).statusCode()).as("tour " + tour).isEqualTo(204);
                assertThat(reponses.get(0).statusCode()).isEqualTo(404);
                assertThat(compter("course", c)).isZero();
            }
        }
    }

    @Test
    @DisplayName("CA11 : une panne à l'enregistrement donne 500 ERREUR_INTERNE sans fuite, X reste EN_PREPARATION sans demarree_le")
    void ca11_atomicite_en_cas_de_panne() throws Exception {
        Scenario s = scenario();
        doThrow(new IllegalStateException("panne simulée secret-technique")).when(depotCourses).enregistrer(any());

        HttpResponse<String> reponse = demarrer(patron, s.x);

        assertThat(reponse.statusCode()).isEqualTo(500);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("secret-technique");
        assertThat(statutEnBase(s.x)).isEqualTo("EN_PREPARATION");
        assertThat(demarreeLeEnBase(s.x)).isNull();
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : une seule ligne INFO « Course démarrée » avec l'id de X ; rien en INFO ou plus pour les refus (403, 404, 409 dont HORS_DATE et SANS_INSCRIT) ; ni nom, ni pseudo, ni UUID de Compte dans le journal")
    void ca12_journal() throws Exception {
        Scenario s = scenario();
        String l = creerCourse(patron, "Lendemain", "2026-10-10");
        String w = creerCourse(patron, "Vide", JOUR);
        demarrerJournal();

        assertThat(demarrer(patron, s.x).statusCode()).isEqualTo(200);
        int apresSucces = journal.list.size();
        assertThat(demarrer(alice, s.y).statusCode()).isEqualTo(403);
        assertThat(demarrer(patron, ID_INCONNU).statusCode()).isEqualTo(404);
        assertThat(demarrer(patron, s.x).statusCode()).isEqualTo(409);
        assertThat(demarrer(patron, l).statusCode()).isEqualTo(409);
        assertThat(demarrer(patron, w).statusCode()).isEqualTo(409);

        List<ILoggingEvent> demarrages = journal.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Course démarrée")).toList();
        assertThat(demarrages).hasSize(1);
        assertThat(demarrages.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(demarrages.get(0).getFormattedMessage()).contains(s.x);
        assertThat(journal.list.subList(apresSucces, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        assertThat(journal.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(
                "Backyard des Crêtes", "Patron", "Léo", "Bruno", idLeo.toString(), idBruno.toString()));
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : Courses EN_COURS et TERMINEE posées par SQL sans demarree_le : fiche, liste, inscrits et affectation EN_COURS en 200, demarreeLe null ; EN_PREPARATION aussi ; POST : 409 COURSE_NON_DEMARRABLE")
    void ca13_lecture_tolerante_sans_demarree_le() throws Exception {
        UUID enCours = insererCourse("En cours sans heure", JOUR, "EN_COURS");
        UUID terminee = insererCourse("Terminée sans heure", JOUR, "TERMINEE");
        String preparation = creerCourse(patron, "En préparation", JOUR);

        for (String id : List.of(enCours.toString(), terminee.toString(), preparation)) {
            HttpResponse<String> fiche = lire(patron, id);
            assertThat(fiche.statusCode()).as(id).isEqualTo(200);
            JsonNode corps = json.readTree(fiche.body());
            assertThat(corps.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_FICHE);
            assertThat(corps.get("demarreeLe").isNull()).as(id).isTrue();
            assertThat(api.requete("GET", CHEMIN + "/" + id + "/inscriptions", patron.enteteLecture(), null, null)
                    .statusCode()).isEqualTo(200);
        }
        HttpResponse<String> liste = api.requete("GET", CHEMIN, patron.enteteLecture(), null, null);
        assertThat(liste.statusCode()).isEqualTo(200);
        assertThat(json.readTree(liste.body())).hasSize(3);
        HttpResponse<String> affectation = affecter(patron, enCours.toString(), idLeo);
        assertThat(affectation.statusCode()).isEqualTo(200);
        assertThat(json.readTree(affectation.body()).get("demarreeLe").isNull()).isTrue();

        for (UUID id : List.of(enCours, terminee)) {
            assertErreur(demarrer(patron, id.toString()), 409, "COURSE_NON_DEMARRABLE", "Conflit",
                    NON_DEMARRABLE_DETAIL);
            assertThat(demarreeLeEnBase(id.toString())).isNull();
        }
    }

    // ---------------------------------------------------------------- utilitaires

    private List<HttpResponse<String>> enParallele(Callable<HttpResponse<String>> premier,
                                                   Callable<HttpResponse<String>> second) throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        ExecutorService executeur = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> a = executeur.submit(() -> {
                depart.await();
                return premier.call();
            });
            Future<HttpResponse<String>> b = executeur.submit(() -> {
                depart.await();
                return second.call();
            });
            depart.countDown();
            List<HttpResponse<String>> reponses = List.of(a.get(), b.get());
            assertThat(reponses).extracting(HttpResponse::statusCode).noneMatch(statut -> statut >= 500);
            return reponses;
        } finally {
            executeur.shutdownNow();
        }
    }

    private String courseAvecInscrit(String nom, Session coureur) throws Exception {
        String id = creerCourse(patron, nom, JOUR);
        assertThat(sinscrire(coureur, id).statusCode()).isEqualTo(201);
        return id;
    }

    private HttpResponse<String> demarrer(Session session, String idCourse) throws Exception {
        return api.requete("POST", CHEMIN + "/" + idCourse + "/demarrage", session.entetes(), null, null);
    }

    private HttpResponse<String> lire(Session session, String idCourse) throws Exception {
        return api.requete("GET", CHEMIN + "/" + idCourse, session.enteteLecture(), null, null);
    }

    private HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", "/api/coureur/courses/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    private HttpResponse<String> seDesinscrire(Session session, String idInscription) throws Exception {
        return api.requete("DELETE", "/api/coureur/inscriptions/" + idInscription, session.entetes(), null, null);
    }

    private HttpResponse<String> affecter(Session session, String idCourse, UUID... benevoles) throws Exception {
        ArrayNode tableau = json.createArrayNode();
        List.of(benevoles).forEach(i -> tableau.add(i.toString()));
        ObjectNode corps = json.createObjectNode();
        corps.set("benevoleIds", tableau);
        return api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles", session.entetes(), "application/json",
                corps.toString());
    }

    private String idInscription(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                UUID.fromString(idCourse), idCompte).toString();
    }

    private String statutEnBase(String idCourse) {
        return jdbc.queryForObject("select statut from course where id = ?", String.class, UUID.fromString(idCourse));
    }

    private Instant demarreeLeEnBase(String idCourse) {
        Timestamp valeur = jdbc.queryForObject("select demarree_le from course where id = ?", Timestamp.class,
                UUID.fromString(idCourse));
        return valeur == null ? null : valeur.toInstant();
    }

    private int compter(String table, String idCourse) {
        String colonne = "course".equals(table) ? "id" : "course_id";
        return jdbc.queryForObject("select count(*) from " + table + " where " + colonne + " = ?", Integer.class,
                UUID.fromString(idCourse));
    }

    private List<UUID> affectationsDe(String idCourse) {
        return jdbc.queryForList("select benevole_id from affectation_benevole where course_id = ?", UUID.class,
                UUID.fromString(idCourse));
    }

    private UUID insererCourse(String nom, String date, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf(date), statut);
        return id;
    }

    private void insererInscription(UUID idCourse, UUID idCompte, int dossard) {
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, 'EN_COURSE')", UUID.randomUUID(), idCourse, idCompte, dossard,
                UUID.randomUUID().toString());
    }

    private UUID insererCompte(String pseudo, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(MOT_DE_PASSE),
                role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private ObjectNode corpsCourse(String nom, String date) {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", date);
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", 50);
        corps.put("nombreMaxBoucles", 24);
        return corps;
    }

    private String creerCourse(Session session, String nom, String date) throws Exception {
        return creerCourse(session, nom, date, 50);
    }

    private String creerCourse(Session session, String nom, String date, int maxParticipants) throws Exception {
        ObjectNode corps = corpsCourse(nom, date);
        corps.put("nombreMaxParticipants", maxParticipants);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, session.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private JsonNode parId(JsonNode tableau, String id) {
        for (JsonNode n : tableau) {
            if (id.equals(n.get("id").asString())) {
                return n;
            }
        }
        throw new AssertionError("Course " + id + " absente de la liste");
    }

    private List<String> ids(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(200);
        List<String> resultat = new ArrayList<>();
        json.readTree(reponse.body()).forEach(n -> resultat.add(n.get("id").asString()));
        return resultat;
    }

    private String code(HttpResponse<String> reponse, int statut) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        return json.readTree(reponse.body()).get("code").asString();
    }

    private HttpResponse<String> lireLogoPublic(String idCourse) throws Exception {
        HttpRequest requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/courses/"
                + idCourse + "/logo")).GET().build();
        return client.send(requete, HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
    }

    private HttpResponse<byte[]> envoiLogoBrut(Session session, String idCourse) throws Exception {
        String frontiere = "----frontiere" + UUID.randomUUID();
        byte[] corps = OctetsDeLogo.concatener(
                ("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"fichier\"; filename=\"l.png\"\r\n"
                        + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8), OctetsDeLogo.png(),
                ("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder requete = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + CHEMIN + "/" + idCourse + "/logo"))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + frontiere)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(corps));
        session.entetes().forEach(requete::header);
        return client.send(requete.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> envoyerLogo(Session session, String idCourse) throws Exception {
        return reponseTexte(envoiLogoBrut(session, idCourse));
    }

    /** Adapte une réponse binaire en réponse texte (corps JSON ou vide) pour les assertions communes. */
    private HttpResponse<String> reponseTexte(HttpResponse<byte[]> origine) {
        String corps = new String(origine.body(), StandardCharsets.UTF_8);
        return new HttpResponse<>() {
            @Override public int statusCode() { return origine.statusCode(); }
            @Override public HttpRequest request() { return origine.request(); }
            @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpHeaders headers() { return origine.headers(); }
            @Override public String body() { return corps; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public URI uri() { return origine.uri(); }
            @Override public HttpClient.Version version() { return origine.version(); }
        };
    }

    private void demarrerJournal() {
        journal = new ListAppender<>();
        journal.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        journal.start();
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.addAppender(journal);
        racine.setLevel(Level.DEBUG);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(Level.INFO);
    }

    private void arreterJournal() {
        if (journal == null) {
            return;
        }
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.detachAppender(journal);
        racine.setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(null);
        journal = null;
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
