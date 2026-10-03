package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 2.1a : déclaration et liste des Courses (CA10 à CA19) et non-régression de
 * l'administration (CA20). L'horloge de l'application est remplacée par une horloge de test pilotable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class CoursesIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final List<String> CHAMPS_REPONSE = List.of("id", "nom", "date", "statut", "distanceBoucleMetres",
            "dureeBoucleMinutes", "denivelePositifBoucleMetres", "nombreMaxParticipants", "nombreMaxBoucles", "logoUrl");

    /** Horloge de l'application remplacée : fixe, déplacée par les tests, et qui respecte le fuseau demandé. */
    static final class HorlogeMutable extends Clock {
        private volatile Instant instant = MIDI_PARIS;

        void fixer(Instant nouvelInstant) {
            instant = nouvelInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HorlogeDeTestConfiguration {
        @Bean
        @Primary
        HorlogeMutable horlogeDeTest() {
            return new HorlogeMutable();
        }
    }

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
    HorlogeMutable horloge;

    @Autowired
    Environment environnement;

    JdbcTemplate jdbc;
    ClientHttp api;
    final JsonMapper json = JsonMapper.builder().build();
    ListAppender<ILoggingEvent> journal;
    Session patron;
    Session nadia;
    Session alice;
    Session leo;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(MIDI_PARIS);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        insererCompte("Nadia", "ADMIN");
        insererCompte("Alice", "COUREUR");
        insererCompte("Léo", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        journal = new ListAppender<>();
        journal.list = new java.util.concurrent.CopyOnWriteArrayList<>(); // liste sûre face aux threads qui journalisent
        journal.start();
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.addAppender(journal);
        racine.setLevel(Level.DEBUG);
        // Le DEBUG interne d'Hibernate affiche les entités persistées (donnée enregistrée, pas le corps de la requête).
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(Level.INFO);
    }

    @AfterEach
    void nettoyer() {
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.detachAppender(journal);
        racine.setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(null);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : Patron et Nadia déclarent la Course de référence (201), champs injectés ignorés, dix champs, ligne en base, GET la renvoie")
    void ca10_declaration_par_les_admins() throws Exception {
        ObjectNode corps = reference();
        corps.put("statut", "EN_COURS");
        corps.put("id", "x");
        corps.put("logo", "y");
        corps.putArray("benevoles");
        corps.put("demarreeLe", "z");
        corps.put("inconnu", 1);

        for (Session session : List.of(patron, nadia)) {
            HttpResponse<String> reponse = creer(session, corps.toString());

            assertThat(reponse.statusCode()).isEqualTo(201);
            assertThat(reponse.headers().firstValue("location")).isEmpty();
            JsonNode course = json.readTree(reponse.body());
            assertThat(course.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_REPONSE);
            UUID id = UUID.fromString(course.get("id").asString());
            assertThat(id).isNotEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
            assertThat(course.get("nom").asString()).isEqualTo("Backyard des Crêtes");
            assertThat(course.get("date").asString()).isEqualTo("2026-11-14");
            assertThat(course.get("statut").asString()).isEqualTo("EN_PREPARATION");
            assertThat(course.get("distanceBoucleMetres").asInt()).isEqualTo(6706);
            assertThat(course.get("dureeBoucleMinutes").asInt()).isEqualTo(60);
            assertThat(course.get("denivelePositifBoucleMetres").asInt()).isEqualTo(120);
            assertThat(course.get("nombreMaxParticipants").asInt()).isEqualTo(50);
            assertThat(course.get("nombreMaxBoucles").asInt()).isEqualTo(24);
            Map<String, Object> ligne = jdbc.queryForMap("select * from course where id = ?", id);
            assertThat(ligne.get("date_course").toString()).isEqualTo("2026-11-14");
            assertThat(ligne.get("statut")).isEqualTo("EN_PREPARATION");
            assertThat(ligne.get("nom")).isEqualTo("Backyard des Crêtes");
            assertThat(lireListe(lister(session))).anySatisfy(
                    element -> assertThat(element.toString()).isEqualTo(course.toString()));
        }
        assertThat(compterCourses()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : anonyme 401, Alice et Léo 403 ACCES_REFUSE, CSRF absent ou différent 403 CSRF_INVALIDE, corps vide non admin 403, chemin inexistant 401/403/403 puis 404")
    void ca11_securite_des_endpoints() throws Exception {
        String valide = reference().toString();
        String jeton = api.jetonValide();

        HttpResponse<String> anonymePost = api.requete("POST", CHEMIN,
                Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton), "application/json", valide);
        assertErreur(anonymePost, 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.requete("GET", CHEMIN, Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.requete("GET", CHEMIN + "/inexistant", Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");

        for (Session session : List.of(alice, leo)) {
            assertErreur(creer(session, valide), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(lister(session), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(creer(session, "{}"), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(get(session, CHEMIN + "/inexistant"), 403, "ACCES_REFUSE", "Accès refusé",
                    ACCES_REFUSE_DETAIL);
        }

        for (Session session : List.of(alice, leo, nadia, patron)) {
            HttpResponse<String> sansEntete = api.requete("POST", CHEMIN,
                    Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf()),
                    "application/json", valide);
            assertErreur(sansEntete, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
            HttpResponse<String> different = api.requete("POST", CHEMIN,
                    Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf(),
                            "X-XSRF-TOKEN", "autre-valeur"), "application/json", valide);
            assertErreur(different, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
        }
        assertErreur(api.requete("POST", CHEMIN, Map.of(), "application/json", valide), 403, "CSRF_INVALIDE",
                "Accès refusé", "Jeton CSRF absent ou invalide.");

        // 2.4 RG6 : GET /{id} existe, un id inconnu ou non UUID donne 404 COURSE_INTROUVABLE.
        assertErreur(get(nadia, CHEMIN + "/inexistant"), 404, "COURSE_INTROUVABLE", "Introuvable",
                "La course est introuvable.");
        assertThat(lister(nadia).statusCode()).isEqualTo(200);
        assertThat(compterCourses()).isZero();
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : corps {} donne 7 erreurs dans l'ordre du contrat, messages exacts, aucune ligne créée")
    void ca12_toutes_les_violations_ensemble() throws Exception {
        JsonNode erreurs = verifierValidation(creer(patron, "{}"));

        assertThat(erreurs).hasSize(7);
        List<String> attendus = List.of(
                "nom|NOM_REQUIS|Le nom est obligatoire.",
                "date|DATE_REQUISE|La date est obligatoire.",
                "distanceBoucleMetres|DISTANCE_REQUISE|La distance d'une boucle est obligatoire.",
                "dureeBoucleMinutes|DUREE_REQUISE|La durée d'une boucle est obligatoire.",
                "denivelePositifBoucleMetres|DENIVELE_REQUIS|Le dénivelé positif d'une boucle est obligatoire.",
                "nombreMaxParticipants|NOMBRE_MAX_PARTICIPANTS_REQUIS|Le nombre maximum de participants est obligatoire.",
                "nombreMaxBoucles|NOMBRE_MAX_BOUCLES_REQUIS|Le nombre maximum de boucles est obligatoire.");
        List<String> obtenus = new ArrayList<>();
        erreurs.forEach(e -> obtenus.add(e.get("champ").asString() + "|" + e.get("code").asString() + "|"
                + e.get("message").asString()));
        assertThat(obtenus).containsExactlyElementsOf(attendus);
        assertThat(compterCourses()).isZero();
    }

    @Test
    @DisplayName("CA12 : champs à null explicite donnent les mêmes erreurs REQUIS")
    void ca12_champs_null_explicites() throws Exception {
        ObjectNode corps = json.createObjectNode();
        CHAMPS_REPONSE.subList(1, 9).forEach(corps::putNull);
        corps.putNull("nom");

        JsonNode erreurs = verifierValidation(creer(patron, corps.toString()));

        assertThat(erreurs).hasSize(7);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo("NOM_REQUIS");
        assertThat(erreurs.get(6).get("code").asString()).isEqualTo("NOMBRE_MAX_BOUCLES_REQUIS");
    }

    @Test
    @DisplayName("CA12 : distance, durée, dénivelé, participants et boucles hors bornes (0, -1, borne haute + 1) avec messages exacts")
    void ca12_nombres_hors_bornes() throws Exception {
        verifierBornes("distanceBoucleMetres", "DISTANCE_HORS_BORNES",
                "La distance d'une boucle doit être comprise entre 1 et 50000 m.", List.of(0, -1, 50001));
        verifierBornes("dureeBoucleMinutes", "DUREE_HORS_BORNES",
                "La durée d'une boucle doit être comprise entre 1 et 1440 min.", List.of(0, -1, 1441));
        verifierBornes("denivelePositifBoucleMetres", "DENIVELE_HORS_BORNES",
                "Le dénivelé positif d'une boucle doit être compris entre 0 et 10000 m.", List.of(-1, 10001));
        verifierBornes("nombreMaxParticipants", "NOMBRE_MAX_PARTICIPANTS_HORS_BORNES",
                "Le nombre maximum de participants doit être compris entre 1 et 5000.", List.of(0, -1, 5001));
        verifierBornes("nombreMaxBoucles", "NOMBRE_MAX_BOUCLES_HORS_BORNES",
                "Le nombre maximum de boucles doit être compris entre 1 et 500.", List.of(0, -1, 501));
        assertThat(compterCourses()).isZero();
    }

    @Test
    @DisplayName("CA12 : dénivelé 0 accepté sans erreur sur ce champ (boucle plate)")
    void ca12_denivele_zero_sans_erreur() throws Exception {
        ObjectNode corps = reference();
        corps.put("denivelePositifBoucleMetres", 0);
        corps.put("distanceBoucleMetres", 0);

        JsonNode erreurs = verifierValidation(creer(patron, corps.toString()));

        assertThat(erreurs).hasSize(1);
        assertThat(erreurs.get(0).get("champ").asString()).isEqualTo("distanceBoucleMetres");
    }

    @Test
    @DisplayName("CA12 : nom de 2 et 101 caractères NOM_LONGUEUR, nom avec tabulation NOM_CARACTERES, messages exacts, sans écho")
    void ca12_nom_invalide() throws Exception {
        for (String nom : List.of("ab", "x".repeat(101), "  ab  ")) {
            ObjectNode corps = reference();
            corps.put("nom", nom);
            HttpResponse<String> reponse = creer(patron, corps.toString());
            JsonNode erreurs = verifierValidation(reponse);
            assertThat(erreurs).hasSize(1);
            assertThat(erreurs.get(0).get("champ").asString()).isEqualTo("nom");
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("NOM_LONGUEUR");
            assertThat(erreurs.get(0).get("message").asString())
                    .isEqualTo("Le nom doit faire entre 3 et 100 caractères.");
            assertThat(reponse.body()).doesNotContain("xxxxx");
        }
        for (String nom : List.of("a\tb c", "Back\nyard")) {
            ObjectNode corps = reference();
            corps.put("nom", nom);
            JsonNode erreurs = verifierValidation(creer(patron, corps.toString()));
            assertThat(erreurs).hasSize(1);
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("NOM_CARACTERES");
            assertThat(erreurs.get(0).get("message").asString())
                    .isEqualTo("Le nom ne doit pas contenir de caractère de contrôle.");
        }
        for (String nom : List.of("", "   ")) {
            ObjectNode corps = reference();
            corps.put("nom", nom);
            JsonNode erreurs = verifierValidation(creer(patron, corps.toString()));
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("NOM_REQUIS");
        }
        assertThat(compterCourses()).isZero();
    }

    @Test
    @DisplayName("CA12 : date passée DATE_PASSEE et au-delà de 5 ans DATE_TROP_LOINTAINE, messages exacts, sans écho")
    void ca12_dates_hors_limites() throws Exception {
        ObjectNode passee = reference();
        passee.put("date", "2026-10-02");
        HttpResponse<String> reponsePassee = creer(patron, passee.toString());
        JsonNode erreursPassee = verifierValidation(reponsePassee);
        assertThat(erreursPassee).hasSize(1);
        assertThat(erreursPassee.get(0).get("champ").asString()).isEqualTo("date");
        assertThat(erreursPassee.get(0).get("code").asString()).isEqualTo("DATE_PASSEE");
        assertThat(erreursPassee.get(0).get("message").asString()).isEqualTo("La date ne peut pas être dans le passé.");
        assertThat(reponsePassee.body()).doesNotContain("2026-10-02");

        ObjectNode lointaine = reference();
        lointaine.put("date", "2031-10-04");
        HttpResponse<String> reponseLointaine = creer(patron, lointaine.toString());
        JsonNode erreursLointaine = verifierValidation(reponseLointaine);
        assertThat(erreursLointaine.get(0).get("code").asString()).isEqualTo("DATE_TROP_LOINTAINE");
        assertThat(erreursLointaine.get(0).get("message").asString())
                .isEqualTo("La date ne peut pas dépasser de plus de 5 ans la date du jour.");
        assertThat(reponseLointaine.body()).doesNotContain("2031-10-04");
        assertThat(compterCourses()).isZero();
    }

    @Test
    @DisplayName("CA12 : plusieurs violations (nom ab, distance 0, date passée) dans l'ordre du tableau, sans écho de la valeur saisie")
    void ca12_plusieurs_violations_dans_l_ordre() throws Exception {
        ObjectNode corps = reference();
        corps.put("nom", "ab");
        corps.put("distanceBoucleMetres", 0);
        corps.put("date", "2026-10-02");

        HttpResponse<String> reponse = creer(patron, corps.toString());

        JsonNode erreurs = verifierValidation(reponse);
        assertThat(erreurs).hasSize(3);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo("NOM_LONGUEUR");
        assertThat(erreurs.get(1).get("code").asString()).isEqualTo("DATE_PASSEE");
        assertThat(erreurs.get(2).get("code").asString()).isEqualTo("DISTANCE_HORS_BORNES");
        assertThat(reponse.body()).doesNotContain("2026-10-02").doesNotContain("\"ab\"");
        assertThat(compterCourses()).isZero();
    }

    @Test
    @DisplayName("CA12 : la date du jour est celle de Paris (horloge à 22:30Z, soit le 4 octobre à Paris) : 2026-10-03 refusée, 2026-10-04 et 2031-10-04 acceptées, 2031-10-05 refusée")
    void ca12_date_du_jour_au_fuseau_de_paris() throws Exception {
        horloge.fixer(Instant.parse("2026-10-03T22:30:00Z"));

        assertThat(codeDate("2026-10-03")).isEqualTo("DATE_PASSEE");
        assertThat(codeDate("2026-10-04")).isNull();
        assertThat(codeDate("2031-10-04")).isNull();
        assertThat(codeDate("2031-10-05")).isEqualTo("DATE_TROP_LOINTAINE");

        horloge.fixer(MIDI_PARIS);
        assertThat(codeDate("2026-10-03")).isNull();
        assertThat(compterCourses()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : corps non JSON, tableau, décimal, chaîne, booléen, entier de 25 chiffres, dates mal formées ou inexistantes, nom numérique : 400 CORPS_ILLISIBLE sans écho")
    void ca13_corps_illisibles() throws Exception {
        List<String> corpsIllisibles = List.of(
                "ceci n'est pas du json",
                "[]",
                "\"texte\"",
                remplacer("distanceBoucleMetres", "1.5"),
                remplacer("distanceBoucleMetres", "\"12\""),
                remplacer("distanceBoucleMetres", "true"),
                remplacer("distanceBoucleMetres", "1234567890123456789012345"),
                remplacer("date", "\"14/11/2026\""),
                remplacer("date", "\"2026-02-30\""),
                remplacer("date", "\"2026-11-14T10:00:00Z\""),
                remplacer("date", "20261114"),
                remplacer("nom", "123"),
                remplacer("dureeBoucleMinutes", "60.0"),
                remplacer("nombreMaxBoucles", "\"24\""));

        for (String corps : corpsIllisibles) {
            HttpResponse<String> reponse = creer(patron, corps);
            assertErreur(reponse, 400, "CORPS_ILLISIBLE", "Requête invalide", "Le corps de la requête est illisible.");
            assertThat(reponse.body()).doesNotContain("14/11/2026").doesNotContain("2026-02-30")
                    .doesNotContain("1234567890123456789012345").doesNotContain("ceci n'est pas");
        }
        assertThat(compterCourses()).isZero();
    }

    @Test
    @DisplayName("CA13 : entier de 64 bits hors bornes (99999999999) donne DISTANCE_HORS_BORNES, pas CORPS_ILLISIBLE")
    void ca13_entier_64_bits_hors_bornes() throws Exception {
        JsonNode erreurs = verifierValidation(creer(patron, remplacer("distanceBoucleMetres", "99999999999")));

        assertThat(erreurs).hasSize(1);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo("DISTANCE_HORS_BORNES");
        assertThat(compterCourses()).isZero();
    }

    @Test
    @DisplayName("CA13 : Content-Type text/plain donne 415, aucune ligne créée")
    void ca13_content_type_non_json() throws Exception {
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, patron.entetes(), "text/plain",
                reference().toString());

        assertThat(reponse.statusCode()).isEqualTo(415);
        assertThat(compterCourses()).isZero();
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : valeurs minimales (dénivelé 0) puis maximales acceptées (201), renvoyées à l'identique et relues par GET, pour Patron et Nadia")
    void ca14_valeurs_aux_bornes() throws Exception {
        ObjectNode minimales = json.createObjectNode();
        minimales.put("nom", "abc");
        minimales.put("date", "2026-10-03");
        minimales.put("distanceBoucleMetres", 1);
        minimales.put("dureeBoucleMinutes", 1);
        minimales.put("denivelePositifBoucleMetres", 0);
        minimales.put("nombreMaxParticipants", 1);
        minimales.put("nombreMaxBoucles", 1);
        ObjectNode maximales = json.createObjectNode();
        maximales.put("nom", "n".repeat(100));
        maximales.put("date", "2031-10-03");
        maximales.put("distanceBoucleMetres", 50000);
        maximales.put("dureeBoucleMinutes", 1440);
        maximales.put("denivelePositifBoucleMetres", 10000);
        maximales.put("nombreMaxParticipants", 5000);
        maximales.put("nombreMaxBoucles", 500);

        for (Session session : List.of(patron, nadia)) {
            for (ObjectNode corps : List.of(minimales, maximales)) {
                HttpResponse<String> reponse = creer(session, corps.toString());
                assertThat(reponse.statusCode()).isEqualTo(201);
                JsonNode course = json.readTree(reponse.body());
                corps.propertyNames().forEach(champ -> assertThat(course.get(champ)).isEqualTo(corps.get(champ)));
                assertThat(course.get("statut").asString()).isEqualTo("EN_PREPARATION");
                assertThat(lireListe(lister(session))).anySatisfy(
                        element -> assertThat(element.toString()).isEqualTo(course.toString()));
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from course where denivele_positif_boucle_metres = 0",
                Integer.class)).isEqualTo(2);
        assertThat(compterCourses()).isEqualTo(4);
    }

    @Test
    @DisplayName("CA14 : nom avec espaces autour conservé après trim, ponctuation et accents acceptés")
    void ca14_nom_apres_trim() throws Exception {
        ObjectNode corps = reference();
        corps.put("nom", "  Backyard d'été 2026 - Édition #2  ");

        HttpResponse<String> reponse = creer(patron, corps.toString());

        assertThat(reponse.statusCode()).isEqualTo(201);
        assertThat(json.readTree(reponse.body()).get("nom").asString()).isEqualTo("Backyard d'été 2026 - Édition #2");
        assertThat(jdbc.queryForObject("select nom from course", String.class))
                .isEqualTo("Backyard d'été 2026 - Édition #2");
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : trois Courses listées par Patron et Nadia, même liste dans l'ordre date décroissante, éléments identiques aux réponses 201 ; base vide : []")
    void ca15_liste_triee() throws Exception {
        HttpResponse<String> vide = lister(patron);
        assertThat(vide.statusCode()).isEqualTo(200);
        assertThat(vide.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json"));
        assertThat(json.readTree(vide.body()).isArray()).isTrue();
        assertThat(json.readTree(vide.body())).isEmpty();

        JsonNode decembre = creerReussie(patron, "Zèbre", "2026-12-01");
        JsonNode janvier = creerReussie(nadia, "Beta", "2027-01-10");
        JsonNode novembre = creerReussie(patron, "Aube", "2026-11-01");

        for (Session session : List.of(patron, nadia)) {
            HttpResponse<String> reponse = lister(session);
            assertThat(reponse.statusCode()).isEqualTo(200);
            List<JsonNode> liste = lireListe(reponse);
            assertThat(liste).hasSize(3);
            assertThat(liste.get(0)).isEqualTo(janvier);
            assertThat(liste.get(1)).isEqualTo(decembre);
            assertThat(liste.get(2)).isEqualTo(novembre);
        }
    }

    @Test
    @DisplayName("CA15 : à date égale, tri par nom insensible à la casse puis par id")
    void ca15_tri_secondaire_par_nom() throws Exception {
        creerReussie(patron, "Zèbre", "2026-12-01");
        creerReussie(patron, "alpha", "2026-12-01");
        creerReussie(patron, "Beta", "2026-12-01");

        List<String> noms = lireListe(lister(patron)).stream().map(c -> c.get("nom").asString()).toList();

        assertThat(noms).containsExactly("alpha", "Beta", "Zèbre");
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : deux envois identiques créent deux Courses d'id distincts, GET en liste deux")
    void ca16_doublon_accepte() throws Exception {
        String corps = reference().toString();

        JsonNode premiere = json.readTree(creer(patron, corps).body());
        HttpResponse<String> seconde = creer(patron, corps);

        assertThat(seconde.statusCode()).isEqualTo(201);
        assertThat(json.readTree(seconde.body()).get("id").asString()).isNotEqualTo(premiere.get("id").asString());
        assertThat(lireListe(lister(patron))).hasSize(2);
        assertThat(compterCourses()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 (mis à jour par 2.2 RG15 : PUT /{id} couvert par la 2.2, et par 2.4 RG6 : GET /{id} existe, 200 pour Patron et Nadia) : PATCH /{id} : 404 ou 405 pour Patron et Nadia (2.5 RG10 : DELETE /{id} est un endpoint, 403 pour Nadia) ; GET, PUT, PATCH, DELETE : 403 pour Alice, 401 pour un anonyme ; Course intacte")
    void ca17_aucun_autre_endpoint() throws Exception {
        JsonNode course = creerReussie(patron, "Backyard des Crêtes", "2026-11-14");
        String chemin = CHEMIN + "/" + course.get("id").asString();
        String corps = reference().toString();
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (String methode : List.of("GET", "PUT", "PATCH", "DELETE")) {
            String contenu = "PUT".equals(methode) || "PATCH".equals(methode) ? corps : null;
            String type = contenu == null ? null : "application/json";
            // PUT /{id} (2.2 RG15) et GET /{id} (2.4 RG6) sont des endpoints : ils sortent de la vérification « 404 ou 405 ».
            if ("GET".equals(methode)) {
                for (Session admin : List.of(patron, nadia)) {
                    HttpResponse<String> fiche = api.requete("GET", chemin, admin.enteteLecture(), null, null);
                    assertThat(fiche.statusCode()).isEqualTo(200);
                    assertThat(json.readTree(fiche.body()).get("id").asString()).isEqualTo(course.get("id").asString());
                }
            }
            if ("DELETE".equals(methode)) {
                // 2.5 RG5/RG10 : DELETE /{id} est un endpoint (204/404/409 pour Patron, couvert par la 2.5) ;
                // pour Nadia (ADMIN) il reste refusé en 403.
                assertErreur(api.requete("DELETE", chemin, nadia.entetes(), null, null), 403, "ACCES_REFUSE",
                        "Accès refusé", ACCES_REFUSE_DETAIL);
            }
            for (Session admin : "PUT".equals(methode) || "GET".equals(methode) || "DELETE".equals(methode)
                    ? List.<Session>of() : List.of(patron, nadia)) {
                HttpResponse<String> reponse = api.requete(methode, chemin, admin.entetes(), type, contenu);
                assertThat(reponse.statusCode()).as(methode + " " + admin).isIn(404, 405);
                if (reponse.statusCode() == 404) {
                    assertErreur(reponse, 404, "RESSOURCE_INTROUVABLE", "Introuvable",
                            "La ressource demandée est introuvable.");
                }
            }
            assertErreur(api.requete(methode, chemin, alice.entetes(), type, contenu), 403, "ACCES_REFUSE",
                    "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(api.requete(methode, chemin, anonyme, type, contenu), 401, "NON_AUTHENTIFIE",
                    "Authentification requise", "Vous devez être connecté.");
        }

        assertThat(compterCourses()).isEqualTo(1);
        assertThat(lireListe(lister(patron))).containsExactly(course);
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 (mis à jour par 2.4 RG14 : 0002 à 0006) : Liquibase applique 0002 à 0006 dans l'ordre, ddl-auto=validate, aucune clé étrangère entre course et compte")
    void ca18_changesets_et_absence_de_cle_etrangere() {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                "0006-affectation-benevole");
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_type = 'FOREIGN KEY' and table_name in ('course', 'compte')", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where contype = 'f' "
                + "and (conrelid = 'course'::regclass or confrelid = 'compte'::regclass "
                + "or (confrelid = 'course'::regclass and conrelid not in ('logo_course'::regclass, 'affectation_benevole'::regclass)))", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("CA18 : la table course a les colonnes et types de RG12 (et rien d'autre), clé primaire pk_course, contraintes ck_*")
    void ca18_structure_de_la_table() {
        List<Map<String, Object>> colonnes = jdbc.queryForList("select column_name, data_type, character_maximum_length, "
                + "is_nullable from information_schema.columns where table_name = 'course' order by column_name");
        assertThat(colonnes).extracting(c -> c.get("column_name")).containsExactlyInAnyOrder("id", "nom",
                "date_course", "statut", "distance_boucle_metres", "duree_boucle_minutes",
                "denivele_positif_boucle_metres", "nombre_max_participants", "nombre_max_boucles");
        Map<String, Map<String, Object>> parNom = new java.util.HashMap<>();
        colonnes.forEach(c -> parNom.put((String) c.get("column_name"), c));
        assertThat(parNom.get("id").get("data_type")).isEqualTo("uuid");
        assertThat(parNom.get("nom").get("data_type")).isEqualTo("character varying");
        assertThat(parNom.get("nom").get("character_maximum_length")).isEqualTo(100);
        assertThat(parNom.get("date_course").get("data_type")).isEqualTo("date");
        assertThat(parNom.get("statut").get("data_type")).isEqualTo("character varying");
        assertThat(parNom.get("statut").get("character_maximum_length")).isEqualTo(20);
        for (String entier : List.of("distance_boucle_metres", "duree_boucle_minutes",
                "denivele_positif_boucle_metres", "nombre_max_participants", "nombre_max_boucles")) {
            assertThat(parNom.get(entier).get("data_type")).isEqualTo("integer");
        }
        colonnes.forEach(c -> assertThat(c.get("is_nullable")).as(c.get("column_name").toString()).isEqualTo("NO"));
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid = 'course'::regclass "
                + "order by conname", String.class)).containsExactlyInAnyOrder("pk_course", "ck_course_statut",
                "ck_course_distance_boucle_metres", "ck_course_duree_boucle_minutes",
                "ck_course_denivele_positif_boucle_metres", "ck_course_nombre_max_participants",
                "ck_course_nombre_max_boucles");
    }

    @Test
    @DisplayName("CA18 : un insert SQL hors contraintes (distance 0, dénivelé -1, statut INCONNU, nom nul, ...) est refusé ; dénivelé 0 accepté")
    void ca18_contraintes_en_base() {
        assertRefuse("ck_course_distance_boucle_metres", "x", "EN_PREPARATION", 0, 1, 1, 1, 1);
        assertRefuse("ck_course_duree_boucle_minutes", "x", "EN_PREPARATION", 1, 0, 1, 1, 1);
        assertRefuse("ck_course_denivele_positif_boucle_metres", "x", "EN_PREPARATION", 1, 1, -1, 1, 1);
        assertRefuse("ck_course_nombre_max_participants", "x", "EN_PREPARATION", 1, 1, 1, 0, 1);
        assertRefuse("ck_course_nombre_max_boucles", "x", "EN_PREPARATION", 1, 1, 1, 1, 0);
        assertRefuse("ck_course_statut", "x", "INCONNU", 1, 1, 1, 1, 1);
        assertRefuse("nom", null, "EN_PREPARATION", 1, 1, 1, 1, 1);
        assertThat(compterCourses()).isZero();

        insererCourse("x", "EN_PREPARATION", 1, 1, 0, 1, 1);
        insererCourse("y", "EN_COURS", 1, 1, 0, 1, 1);
        insererCourse("z", "TERMINEE", 1, 1, 0, 1, 1);
        assertThat(compterCourses()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- CA19

    @Test
    @DisplayName("CA19 : une ligne INFO « Course déclarée » avec l'id, aucune ligne pour un refus, aucun corps de requête dans le journal")
    void ca19_journal_sans_contenu_saisi() throws Exception {
        ObjectNode corps = reference();
        corps.put("nom", "Nom-secret-journal-xyz");
        corps.put("inconnu", "valeur-secrete-journal-xyz");

        JsonNode course = json.readTree(creer(patron, corps.toString()).body());
        corps.put("distanceBoucleMetres", 0);
        assertThat(creer(patron, corps.toString()).statusCode()).isEqualTo(400);

        List<ILoggingEvent> declarations = journal.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Course déclarée")).toList();
        assertThat(declarations).hasSize(1);
        assertThat(declarations.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(declarations.get(0).getFormattedMessage()).contains(course.get("id").asString())
                .doesNotContain("Nom-secret-journal-xyz");
        assertThat(journal.list).noneSatisfy(e -> assertThat(e.getFormattedMessage())
                .containsAnyOf("Nom-secret-journal-xyz", "valeur-secrete-journal-xyz"));
        List<String> messagesDExceptions = journal.list.stream()
                .filter(e -> e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null)
                .map(e -> e.getThrowableProxy().getMessage()).toList();
        assertThat(messagesDExceptions).noneMatch(m -> m.contains("Nom-secret-journal-xyz")
                || m.contains("valeur-secrete-journal-xyz"));
    }

    // ---------------------------------------------------------------- CA20

    @Test
    @DisplayName("CA20 : /acces, /admins (ADMIN_MASTER seul) et /benevoles se comportent comme avant")
    void ca20_non_regression_administration() throws Exception {
        assertThat(get(patron, "/api/administration/acces").statusCode()).isEqualTo(204);
        assertThat(get(nadia, "/api/administration/acces").statusCode()).isEqualTo(204);
        assertErreur(get(alice, "/api/administration/acces"), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
        assertThat(get(patron, "/api/administration/admins").statusCode()).isEqualTo(200);
        assertErreur(get(nadia, "/api/administration/admins"), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
        assertThat(get(patron, "/api/administration/benevoles").statusCode()).isEqualTo(200);
        assertThat(get(nadia, "/api/administration/benevoles").statusCode()).isEqualTo(200);
        assertErreur(get(leo, "/api/administration/benevoles"), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
    }

    // ---------------------------------------------------------------- utilitaires

    private ObjectNode reference() {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", "Backyard des Crêtes");
        corps.put("date", "2026-11-14");
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", 50);
        corps.put("nombreMaxBoucles", 24);
        return corps;
    }

    /** Course de référence dont un champ est remplacé par un littéral JSON brut. */
    private String remplacer(String champ, String litteralJson) {
        ObjectNode corps = reference();
        corps.put(champ, "@@");
        return corps.toString().replace("\"@@\"", litteralJson);
    }

    private void verifierBornes(String champ, String code, String message, List<Integer> valeurs) throws Exception {
        for (int valeur : valeurs) {
            ObjectNode corps = reference();
            corps.put(champ, valeur);
            HttpResponse<String> reponse = creer(patron, corps.toString());
            JsonNode erreurs = verifierValidation(reponse);
            assertThat(erreurs).as(champ + "=" + valeur).hasSize(1);
            assertThat(erreurs.get(0).get("champ").asString()).isEqualTo(champ);
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo(code);
            assertThat(erreurs.get(0).get("message").asString()).isEqualTo(message);
        }
    }

    /** Code d'erreur de la date, ou null si la Course est créée avec cette date. */
    private String codeDate(String date) throws Exception {
        ObjectNode corps = reference();
        corps.put("date", date);
        HttpResponse<String> reponse = creer(patron, corps.toString());
        if (reponse.statusCode() == 201) {
            return null;
        }
        JsonNode erreurs = verifierValidation(reponse);
        assertThat(erreurs).hasSize(1);
        return erreurs.get(0).get("code").asString();
    }

    private JsonNode creerReussie(Session session, String nom, String date) throws Exception {
        ObjectNode corps = reference();
        corps.put("nom", nom);
        corps.put("date", date);
        HttpResponse<String> reponse = creer(session, corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body());
    }

    private List<JsonNode> lireListe(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(200);
        List<JsonNode> elements = new ArrayList<>();
        json.readTree(reponse.body()).forEach(elements::add);
        return elements;
    }

    private void insererCompte(String pseudo, String role) {
        jdbc.update(INSERTION_COMPTE, UUID.randomUUID(), pseudo, pseudo.toLowerCase(Locale.ROOT),
                encodeur.encode(MOT_DE_PASSE), role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
    }

    private void insererCourse(String nom, String statut, int distance, int duree, int denivele, int participants,
                               int boucles) {
        jdbc.update(INSERTION_COURSE, UUID.randomUUID(), nom, Date.valueOf("2026-11-14"), statut, distance, duree,
                denivele, participants, boucles);
    }

    private void assertRefuse(String contrainte, String nom, String statut, int distance, int duree, int denivele,
                              int participants, int boucles) {
        assertThatThrownBy(() -> insererCourse(nom, statut, distance, duree, denivele, participants, boucles))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(contrainte);
    }

    private int compterCourses() {
        return jdbc.queryForObject("select count(*) from course", Integer.class);
    }

    private HttpResponse<String> creer(Session session, String corps) throws Exception {
        return api.requete("POST", CHEMIN, session.entetes(), "application/json", corps);
    }

    private HttpResponse<String> lister(Session session) throws Exception {
        return get(session, CHEMIN);
    }

    private HttpResponse<String> get(Session session, String chemin) throws Exception {
        return api.requete("GET", chemin, session.enteteLecture(), null, null);
    }

    private JsonNode verifierValidation(HttpResponse<String> reponse) throws Exception {
        assertErreur(reponse, 400, "VALIDATION_ECHOUEE", "Requête invalide", "Certains champs sont invalides.");
        return json.readTree(reponse.body()).get("erreurs");
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
