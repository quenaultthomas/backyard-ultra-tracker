package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.HorlogeDecalee;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 4.3 en profil e2e, sans Clock de test : CA6 à CA9 (horloge pilotable, validation, sécurité,
 * effet sur le pilotage). Le bean HorlogeDecalee est un singleton partagé : le décalage est remis à zéro avant et
 * après chaque test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@ActiveProfiles("e2e")
@Testcontainers
class HorlogePilotableIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/test/horloge";
    static final String AVANCE = CHEMIN + "/avance";
    static final String COURSES = "/api/administration/courses";
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final String MESSAGE_DUREE = "La durée doit être une durée ISO-8601 strictement positive d'au plus 30 jours (ex. PT60M).";

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
    Clock horlogeApplication;

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ApiHttp api;
    Map<String, String> patron;
    Map<String, String> nadia;
    Map<String, String> leo;
    Map<String, String> alice;
    Map<String, String> bruno;
    Map<String, String> chloe;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        api = new ApiHttp(port);
        initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON);
        inserer("Nadia", "ADMIN");
        inserer("Léo", "BENEVOLE");
        inserer("Alice", "COUREUR");
        inserer("Bruno", "COUREUR");
        inserer("Chloé", "COUREUR");
        patron = session("Patron", MOT_DE_PASSE_PATRON);
        nadia = session("Nadia", MOT_DE_PASSE);
        leo = session("Léo", MOT_DE_PASSE);
        alice = session("Alice", MOT_DE_PASSE);
        bruno = session("Bruno", MOT_DE_PASSE);
        chloe = session("Chloé", MOT_DE_PASSE);
        reinitialiser();
    }

    @AfterEach
    void remettre_l_horloge_a_zero() throws Exception {
        reinitialiser();
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : GET /api/test/horloge par Patron : 200, exactement 3 champs, décalage PT0S, instant réel à 2 s, no-store")
    void ca6_lecture_a_decalage_nul() throws Exception {
        HttpResponse<String> reponse = lire(patron);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("maintenant", "decalage", "decalageMillisecondes");
        assertThat(corps.get("decalage").asString()).isEqualTo("PT0S");
        assertThat(corps.get("decalageMillisecondes").asLong()).isZero();
        assertThat(ecartAvecHeureReelle(corps)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("CA6 : POST /avance PT60M puis pt1h : 200, décalage PT1H puis PT2H, instant décalé, identique à celui du bean Clock ; DELETE répété : PT0S")
    void ca6_avance_et_reinitialisation() throws Exception {
        HttpResponse<String> avance = avancer(patron, "{\"duree\":\"PT60M\"}");

        assertThat(avance.statusCode()).isEqualTo(200);
        assertThat(avance.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode corps = json.readTree(avance.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("maintenant", "decalage", "decalageMillisecondes");
        assertThat(corps.get("decalage").asString()).isEqualTo("PT1H");
        assertThat(corps.get("decalageMillisecondes").asLong()).isEqualTo(3_600_000L);
        assertThat(ecartAvecHeureReelle(corps).minus(Duration.ofHours(1)).abs()).isLessThan(Duration.ofSeconds(2));
        assertThat(horlogeApplication).isInstanceOf(HorlogeDecalee.class);
        assertThat(Duration.between(Instant.parse(corps.get("maintenant").asString()), horlogeApplication.instant())
                .abs()).isLessThan(Duration.ofSeconds(2));

        JsonNode minuscules = json.readTree(avancer(patron, "{\"duree\":\"pt1h\"}").body());
        assertThat(minuscules.get("decalage").asString()).isEqualTo("PT2H");

        for (int i = 0; i < 2; i++) {
            HttpResponse<String> reinit = reinitialiserEtLire();
            assertThat(reinit.statusCode()).isEqualTo(200);
            JsonNode apres = json.readTree(reinit.body());
            assertThat(apres.get("decalage").asString()).isEqualTo("PT0S");
            assertThat(apres.get("decalageMillisecondes").asLong()).isZero();
            assertThat(ecartAvecHeureReelle(apres)).isLessThan(Duration.ofSeconds(2));
        }
    }

    @Test
    @DisplayName("CA6 : une ligne INFO par avance et par réinitialisation, sans pseudo ni identifiant de Compte, rien pour les GET")
    void ca6_journal_de_l_horloge() throws Exception {
        List<UUID> idsComptes = jdbc.queryForList("select id from compte", UUID.class);
        try (JournalApplicatif journal = JournalApplicatif.demarrer()) {
            lire(patron);
            avancer(patron, "{\"duree\":\"PT60M\"}");
            lire(patron);
            avancer(patron, "{\"duree\":\"pt1h\"}");
            lire(patron);
            api.requete("DELETE", CHEMIN, patron, null, null);
            api.requete("DELETE", CHEMIN, patron, null, null);
            lire(patron);

            List<ch.qos.logback.classic.spi.ILoggingEvent> horloge = journal.evenementsApplicatifs().stream()
                    .filter(e -> e.getFormattedMessage() != null
                            && e.getFormattedMessage().toLowerCase(Locale.ROOT).contains("horloge")).toList();
            assertThat(horloge).extracting(e -> e.getFormattedMessage()).containsExactly(
                    "Horloge avancée de PT60M (décalage cumulé PT1H).",
                    "Horloge avancée de pt1h (décalage cumulé PT2H).",
                    "Horloge réinitialisée.",
                    "Horloge réinitialisée.");
            assertThat(horloge).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO));
            assertThat(horloge).extracting(e -> e.getFormattedMessage()).allSatisfy(message -> {
                assertThat(message).doesNotContain("Patron");
                idsComptes.forEach(id -> assertThat(message).doesNotContain(id.toString()));
            });
        }
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : durées invalides PT0S, PT-5M, -PT5M, P1M, P1Y, P30DT1S, abc, vide, champ absent, nombre : 400 VALIDATION_ECHOUEE sur le champ duree, décalage inchangé")
    void ca7_durees_invalides() throws Exception {
        List<String> corpsInvalides = List.of("{\"duree\":\"PT0S\"}", "{\"duree\":\"PT-5M\"}", "{\"duree\":\"-PT5M\"}",
                "{\"duree\":\"P1M\"}", "{\"duree\":\"P1Y\"}", "{\"duree\":\"P30DT1S\"}", "{\"duree\":\"abc\"}",
                "{\"duree\":\"\"}", "{}", "{\"duree\":3600}", "{\"duree\":null}");
        for (String corps : corpsInvalides) {
            HttpResponse<String> reponse = avancer(patron, corps);

            assertThat(reponse.statusCode()).as(corps).isEqualTo(400);
            assertThat(reponse.headers().firstValue("content-type")).as(corps).hasValueSatisfying(
                    c -> assertThat(c).contains("application/problem+json"));
            JsonNode probleme = json.readTree(reponse.body());
            assertThat(probleme.get("code").asString()).as(corps).isEqualTo("VALIDATION_ECHOUEE");
            assertThat(probleme.get("status").asInt()).isEqualTo(400);
            JsonNode erreurs = probleme.get("erreurs");
            assertThat(erreurs).as(corps).hasSize(1);
            assertThat(erreurs.get(0).get("champ").asString()).isEqualTo("duree");
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("DUREE_INVALIDE");
            assertThat(erreurs.get(0).get("message").asString()).isEqualTo(MESSAGE_DUREE);
            assertThat(decalage()).as(corps).isEqualTo("PT0S");
        }
    }

    @Test
    @DisplayName("CA7 : JSON invalide et corps absent : 400 CORPS_ILLISIBLE, décalage inchangé")
    void ca7_corps_illisible() throws Exception {
        HttpResponse<String> invalide = api.requete("POST", AVANCE, patron, "application/json", "{\"duree\":");
        HttpResponse<String> absent = api.requete("POST", AVANCE, patron, "application/json", null);

        for (HttpResponse<String> reponse : List.of(invalide, absent)) {
            assertThat(reponse.statusCode()).isEqualTo(400);
            assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("CORPS_ILLISIBLE");
        }
        assertThat(decalage()).isEqualTo("PT0S");
    }

    @Test
    @DisplayName("CA7 : PT1S et P30D acceptés ; 12 avances de P30D acceptées, la 13e refusée en 409 DECALAGE_HORLOGE_EXCESSIF (décalage P360D), puis P5D accepté et PT1S refusé")
    void ca7_bornes_et_cumul() throws Exception {
        assertThat(avancer(patron, "{\"duree\":\"PT1S\"}").statusCode()).isEqualTo(200);
        reinitialiser();
        for (int i = 0; i < 12; i++) {
            assertThat(avancer(patron, "{\"duree\":\"P30D\"}").statusCode()).as("avance " + (i + 1)).isEqualTo(200);
        }

        HttpResponse<String> refusee = avancer(patron, "{\"duree\":\"P30D\"}");

        assertThat(refusee.statusCode()).isEqualTo(409);
        assertThat(refusee.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        JsonNode probleme = json.readTree(refusee.body());
        assertThat(probleme.get("code").asString()).isEqualTo("DECALAGE_HORLOGE_EXCESSIF");
        assertThat(probleme.get("title").asString()).isEqualTo("Conflit");
        assertThat(probleme.get("detail").asString())
                .isEqualTo("Le décalage cumulé de l'horloge ne peut pas dépasser 365 jours.");
        assertThat(decalage()).isEqualTo("PT8640H");
        assertThat(avancer(patron, "{\"duree\":\"P5D\"}").statusCode()).isEqualTo(200);
        assertThat(decalage()).isEqualTo("PT8760H");
        assertThat(avancer(patron, "{\"duree\":\"PT1S\"}").statusCode()).isEqualTo(409);
        assertThat(decalage()).isEqualTo("PT8760H");
    }

    @Test
    @DisplayName("CA7 : un paramètre ?maintenant=... est ignoré")
    void ca7_parametre_maintenant_ignore() throws Exception {
        HttpResponse<String> reponse = api.requete("POST", AVANCE + "?maintenant=2030-01-01T00:00:00Z", patron,
                "application/json", "{\"duree\":\"PT1S\"}");
        JsonNode lecture = json.readTree(api.requete("GET", CHEMIN + "?maintenant=2030-01-01T00:00:00Z",
                Map.of("Cookie", patron.get("Cookie")), null, null).body());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body()).get("decalage").asString()).isEqualTo("PT1S");
        assertThat(ecartAvecHeureReelle(lecture).minusSeconds(1).abs()).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("CA7 : 100 avances concurrentes de PT1S donnent exactement PT100S")
    void ca7_avances_concurrentes() throws Exception {
        ExecutorService executeur = Executors.newFixedThreadPool(100);
        try {
            CountDownLatch depart = new CountDownLatch(1);
            List<Future<Integer>> resultats = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                resultats.add(executeur.submit(() -> {
                    depart.await();
                    return avancer(patron, "{\"duree\":\"PT1S\"}").statusCode();
                }));
            }
            depart.countDown();
            for (Future<Integer> resultat : resultats) {
                assertThat(resultat.get()).isEqualTo(200);
            }
        } finally {
            executeur.shutdownNow();
        }

        assertThat(decalage()).isEqualTo("PT1M40S");
        assertThat(json.readTree(lire(patron).body()).get("decalageMillisecondes").asLong()).isEqualTo(100_000L);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : GET anonyme 401 NON_AUTHENTIFIE ; POST anonyme avec CSRF 401, sans CSRF 403 CSRF_INVALIDE")
    void ca8_anonyme() throws Exception {
        verifierProbleme(api.get(CHEMIN), 401, "NON_AUTHENTIFIE");
        ApiHttp.Jeton jeton = api.jetonValide();
        Map<String, String> csrf = Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie(), "X-XSRF-TOKEN", jeton.entete());

        verifierProbleme(api.requete("POST", AVANCE, csrf, "application/json", "{\"duree\":\"PT1S\"}"), 401,
                "NON_AUTHENTIFIE");
        verifierProbleme(api.requete("DELETE", CHEMIN, csrf, null, null), 401, "NON_AUTHENTIFIE");
        verifierProbleme(api.requete("POST", AVANCE, Map.of(), "application/json", "{\"duree\":\"PT1S\"}"), 403,
                "CSRF_INVALIDE");
        assertThat(decalage()).isEqualTo("PT0S");
    }

    @Test
    @DisplayName("CA8 : Nadia (ADMIN), Léo (BENEVOLE) et Alice (COUREUR) obtiennent 403 ACCES_REFUSE sur GET, POST /avance et DELETE avec CSRF valide, décalage inchangé")
    void ca8_roles_insuffisants() throws Exception {
        avancer(patron, "{\"duree\":\"PT5M\"}");
        for (Map<String, String> session : List.of(nadia, leo, alice)) {
            verifierProbleme(api.requete("GET", CHEMIN, Map.of("Cookie", session.get("Cookie")), null, null), 403,
                    "ACCES_REFUSE");
            verifierProbleme(avancer(session, "{\"duree\":\"PT1S\"}"), 403, "ACCES_REFUSE");
            verifierProbleme(api.requete("DELETE", CHEMIN, session, null, null), 403, "ACCES_REFUSE");
        }

        assertThat(decalage()).isEqualTo("PT5M");
    }

    @Test
    @DisplayName("CA8 : Patron sans X-XSRF-TOKEN sur POST /avance et DELETE : 403 CSRF_INVALIDE, décalage inchangé ; GET sans CSRF accepté ; PUT jamais 2xx")
    void ca8_csrf_et_methodes() throws Exception {
        avancer(patron, "{\"duree\":\"PT5M\"}");
        Map<String, String> sansEntete = Map.of("Cookie", patron.get("Cookie"));

        verifierProbleme(api.requete("POST", AVANCE, sansEntete, "application/json", "{\"duree\":\"PT1S\"}"), 403,
                "CSRF_INVALIDE");
        verifierProbleme(api.requete("DELETE", CHEMIN, sansEntete, null, null), 403, "CSRF_INVALIDE");
        assertThat(decalage()).isEqualTo("PT5M");

        assertThat(lire(patron).statusCode()).isEqualTo(200);
        HttpResponse<String> put = api.requete("PUT", CHEMIN, patron, "application/json", "{\"duree\":\"PT1S\"}");
        assertThat(put.statusCode()).isIn(404, 405);
        HttpResponse<String> getAvance = api.requete("GET", AVANCE, Map.of("Cookie", patron.get("Cookie")), null, null);
        assertThat(getAvance.statusCode()).isIn(404, 405);
        assertThat(decalage()).isEqualTo("PT5M");
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : Course A (60 min, 24 boucles, 2 inscrits) démarrée : Boucle 1 ; après PT60M Boucle 2, durée écoulée de 1 h, heureServeur décalé de 1 h ; après PT60M de plus Boucle 3 ; statuts inchangés")
    void ca9_pilotage_suit_l_horloge() throws Exception {
        String a = creerCourseDemarree("Course A", 60);

        JsonNode initial = pilotage(a);
        assertThat(initial.get("boucleCourante").asInt()).isEqualTo(1);

        avancer(patron, "{\"duree\":\"PT60M\"}");
        JsonNode boucle2 = pilotage(a);
        assertThat(boucle2.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(boucle2.get("dureeEcouleeMillisecondes").asLong()).isBetween(3_600_000L, 3_605_000L);
        assertThat(Duration.between(Instant.now(), Instant.parse(boucle2.get("heureServeur").asString()))
                .minus(Duration.ofHours(1)).abs()).isLessThan(Duration.ofSeconds(5));

        avancer(patron, "{\"duree\":\"PT60M\"}");
        assertThat(pilotage(a).get("boucleCourante").asInt()).isEqualTo(3);

        assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, UUID.fromString(a)))
                .isEqualTo("EN_COURS");
        assertThat(jdbc.queryForList("select statut from inscription where course_id = ?", String.class,
                UUID.fromString(a))).hasSize(2).containsOnly("EN_COURSE");
    }

    @Test
    @DisplayName("CA9 : Courses A (60 min) et B (90 min) démarrées ensemble : après PT60M A en Boucle 2 et B en Boucle 1 ; après PT30M de plus B en Boucle 2 et A toujours en Boucle 2")
    void ca9_plusieurs_courses_en_parallele() throws Exception {
        String a = creerCourseDemarree("Course A", 60);
        String b = creerCourseDemarree("Course B", 90);

        avancer(patron, "{\"duree\":\"PT60M\"}");
        assertThat(pilotage(a).get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(pilotage(b).get("boucleCourante").asInt()).isEqualTo(1);

        avancer(patron, "{\"duree\":\"PT30M\"}");
        assertThat(pilotage(b).get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(pilotage(a).get("boucleCourante").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA9 : Course C démarrée après une avance de PT2H : demarreeLe décalée, Boucle 1 ; après DELETE elle reste lisible (Boucle 1, durée écoulée 0)")
    void ca9_course_demarree_apres_avance_puis_reinitialisation() throws Exception {
        avancer(patron, "{\"duree\":\"PT2H\"}");

        String c = creerCourseDemarree("Course C", 60);

        JsonNode decalee = pilotage(c);
        assertThat(decalee.get("boucleCourante").asInt()).isEqualTo(1);
        assertThat(Duration.between(Instant.now().plus(Duration.ofHours(2)),
                Instant.parse(decalee.get("demarreeLe").asString())).abs()).isLessThan(Duration.ofSeconds(3));

        reinitialiser();
        HttpResponse<String> apres = api.requete("GET", COURSES + "/" + c + "/pilotage",
                Map.of("Cookie", patron.get("Cookie")), null, null);
        assertThat(apres.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(apres.body());
        assertThat(corps.get("boucleCourante").asInt()).isEqualTo(1);
        assertThat(corps.get("dureeEcouleeMillisecondes").asLong()).isZero();
    }

    @Test
    @DisplayName("CA9 : après une avance de P1D, une Course D datée du jour d'origine n'est plus démarrable : 409 COURSE_HORS_DATE")
    void ca9_changement_de_jour() throws Exception {
        String d = creerCourse("Course D", 60);
        avancer(patron, "{\"duree\":\"P1D\"}");

        HttpResponse<String> reponse = api.requete("POST", COURSES + "/" + d + "/demarrage", patron, null, null);

        assertThat(reponse.statusCode()).isEqualTo(409);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("COURSE_HORS_DATE");
        assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, UUID.fromString(d)))
                .isEqualTo("EN_PREPARATION");
    }

    @Test
    @DisplayName("CA9 : l'avance de l'horloge ne démarre aucune Course : le pilotage d'une Course en préparation reste 409 COURSE_NON_DEMARREE")
    void ca9_avance_ne_demarre_rien() throws Exception {
        String e = creerCourse("Course E", 60);
        avancer(patron, "{\"duree\":\"PT60M\"}");

        HttpResponse<String> reponse = api.requete("GET", COURSES + "/" + e + "/pilotage",
                Map.of("Cookie", patron.get("Cookie")), null, null);

        verifierProbleme(reponse, 409, "COURSE_NON_DEMARREE");
    }

    // ---------------------------------------------------------------- utilitaires

    private String creerCourseDemarree(String nom, int dureeMinutes) throws Exception {
        String id = creerCourse(nom, dureeMinutes);
        assertThat(api.requete("POST", "/api/coureur/courses/" + id + "/inscriptions", bruno, null, null)
                .statusCode()).isEqualTo(201);
        assertThat(api.requete("POST", "/api/coureur/courses/" + id + "/inscriptions", chloe, null, null)
                .statusCode()).isEqualTo(201);
        HttpResponse<String> demarrage = api.requete("POST", COURSES + "/" + id + "/demarrage", patron, null, null);
        assertThat(demarrage.statusCode()).as(demarrage.body()).isEqualTo(200);
        return id;
    }

    /** Course datée du jour (Paris) de l'horloge de l'application, décalage compris. */
    private String creerCourse(String nom, int dureeMinutes) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", LocalDate.ofInstant(horlogeApplication.instant(), PARIS).toString());
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", dureeMinutes);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", 10);
        corps.put("nombreMaxBoucles", 24);
        HttpResponse<String> reponse = api.requete("POST", COURSES, patron, "application/json", corps.toString());
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private JsonNode pilotage(String idCourse) throws Exception {
        HttpResponse<String> reponse = api.requete("GET", COURSES + "/" + idCourse + "/pilotage",
                Map.of("Cookie", patron.get("Cookie")), null, null);
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        return json.readTree(reponse.body());
    }

    private HttpResponse<String> lire(Map<String, String> session) throws Exception {
        return api.requete("GET", CHEMIN, Map.of("Cookie", session.get("Cookie")), null, null);
    }

    private HttpResponse<String> avancer(Map<String, String> session, String corps) throws Exception {
        return api.requete("POST", AVANCE, session, "application/json", corps);
    }

    private void reinitialiser() throws Exception {
        assertThat(api.requete("DELETE", CHEMIN, patron, null, null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> reinitialiserEtLire() throws Exception {
        return api.requete("DELETE", CHEMIN, patron, null, null);
    }

    private String decalage() throws Exception {
        return json.readTree(lire(patron).body()).get("decalage").asString();
    }

    /** Écart signé entre l'instant renvoyé et l'heure réelle (positif : l'horloge est en avance). */
    private Duration ecartAvecHeureReelle(JsonNode corps) {
        return Duration.between(Instant.now(), Instant.parse(corps.get("maintenant").asString()));
    }

    private void verifierProbleme(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(statut);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo(code);
    }

    private void inserer(String pseudo, String role) {
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
}
