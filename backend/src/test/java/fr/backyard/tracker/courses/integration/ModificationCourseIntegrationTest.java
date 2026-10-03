package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
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
 * Contrat de l'incrément 2.2 : PUT /api/administration/courses/{id} (CA7 à CA15, CA17, CA18). L'horloge de
 * l'application est la même horloge de test pilotable que pour l'incrément 2.1a.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class ModificationCourseIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String INTROUVABLE_DETAIL = "La course est introuvable.";
    static final String NON_MODIFIABLE_DETAIL =
            "La course n'est plus en préparation : elle ne peut plus être modifiée.";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHAMPS_REPONSE = List.of("id", "nom", "date", "statut", "distanceBoucleMetres",
            "dureeBoucleMinutes", "denivelePositifBoucleMetres", "nombreMaxParticipants", "nombreMaxBoucles");

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
        journal.start();
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.addAppender(journal);
        racine.setLevel(Level.DEBUG);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(Level.INFO);
    }

    @AfterEach
    void nettoyer() {
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.detachAppender(journal);
        racine.setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(null);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : Patron et Nadia modifient la Course (200), champs injectés ignorés, neuf champs, GET la renvoie seule, ligne mise à jour en place")
    void ca7_modification_par_les_admins() throws Exception {
        for (Session session : List.of(patron, nadia)) {
            jdbc.update("delete from course");
            String id = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
            ObjectNode corps = modification();
            corps.put("nom", "  Backyard des Alpes  ");
            corps.put("statut", "EN_COURS");
            corps.put("id", "autre");
            corps.put("logo", "y");
            corps.putArray("benevoles");
            corps.put("demarreeLe", "z");
            corps.put("inconnu", 1);

            HttpResponse<String> reponse = modifier(session, id, corps.toString());

            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                    c -> assertThat(c).contains("application/json"));
            JsonNode course = json.readTree(reponse.body());
            assertThat(course.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_REPONSE);
            assertThat(course.get("id").asString()).isEqualTo(id);
            assertThat(course.get("statut").asString()).isEqualTo("EN_PREPARATION");
            assertThat(course.get("nom").asString()).isEqualTo("Backyard des Alpes");
            assertThat(course.get("date").asString()).isEqualTo("2026-12-05");
            assertThat(course.get("distanceBoucleMetres").asInt()).isEqualTo(8000);
            assertThat(course.get("dureeBoucleMinutes").asInt()).isEqualTo(45);
            assertThat(course.get("denivelePositifBoucleMetres").asInt()).isZero();
            assertThat(course.get("nombreMaxParticipants").asInt()).isEqualTo(80);
            assertThat(course.get("nombreMaxBoucles").asInt()).isEqualTo(12);
            assertThat(lireListe(lister(session))).containsExactly(course);
            Map<String, Object> ligne = jdbc.queryForMap("select * from course where id = ?", UUID.fromString(id));
            assertThat(ligne.get("duree_boucle_minutes")).isEqualTo(45);
            assertThat(ligne.get("denivele_positif_boucle_metres")).isEqualTo(0);
            assertThat(ligne.get("date_course").toString()).isEqualTo("2026-12-05");
            assertThat(ligne.get("statut")).isEqualTo("EN_PREPARATION");
            assertThat(ligne.get("nom")).isEqualTo("Backyard des Alpes");
            assertThat(compterCourses()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CA7 : le PUT met à jour la ligne existante (même id, une seule ligne), ne touche pas l'autre Course et deux PUT identiques donnent le même état")
    void ca7_mise_a_jour_en_place() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String autre = creerCourse(patron, "Autre", "2027-01-01");
        Map<String, Object> ligneAutre = ligne(autre);

        HttpResponse<String> premier = modifier(patron, x, modification().toString());
        HttpResponse<String> second = modifier(patron, x, modification().toString());

        assertThat(premier.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body())).isEqualTo(json.readTree(premier.body()));
        assertThat(compterCourses()).isEqualTo(2);
        assertThat(ligne(autre)).isEqualTo(ligneAutre);
        assertThat(jdbc.queryForList("select id from course", UUID.class)).contains(UUID.fromString(x));
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : anonyme 401, Alice et Léo 403 ACCES_REFUSE pour un corps valide, {} et un id inconnu (jamais 400 ni 404), Course intacte")
    void ca8_securite_roles() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (String cible : List.of(x, ID_INCONNU)) {
            for (String corps : List.of(modification().toString(), "{}")) {
                assertErreur(api.requete("PUT", CHEMIN + "/" + cible, anonyme, "application/json", corps), 401,
                        "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
                for (Session session : List.of(alice, leo)) {
                    assertErreur(modifier(session, cible, corps), 403, "ACCES_REFUSE", "Accès refusé",
                            ACCES_REFUSE_DETAIL);
                }
            }
        }
        assertThat(ligne(x)).isEqualTo(avant);
        assertThat(compterCourses()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA8 : sans X-XSRF-TOKEN ou avec un jeton différent, 403 CSRF_INVALIDE pour tous (anonyme compris), prioritaire sur 401, 403, 404, 400")
    void ca8_csrf_prioritaire() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);
        String valide = modification().toString();

        for (Session session : List.of(alice, leo, nadia, patron)) {
            for (String cible : List.of(x, ID_INCONNU, "inexistant")) {
                Map<String, String> cookie = Map.of("Cookie",
                        "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf());
                assertCsrf(api.requete("PUT", CHEMIN + "/" + cible, cookie, "application/json", valide));
                assertCsrf(api.requete("PUT", CHEMIN + "/" + cible, Map.of("Cookie",
                        "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf(),
                        "X-XSRF-TOKEN", "autre-valeur"), "application/json", "{}"));
            }
        }
        assertCsrf(api.requete("PUT", CHEMIN + "/" + x, Map.of(), "application/json", valide));
        assertThat(ligne(x)).isEqualTo(avant);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : corps {} donne 7 erreurs dans l'ordre du contrat avec messages exacts, Course intacte")
    void ca9_toutes_les_violations_ensemble() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);

        JsonNode erreurs = verifierValidation(modifier(patron, x, "{}"));

        List<String> obtenus = new ArrayList<>();
        erreurs.forEach(e -> obtenus.add(e.get("champ").asString() + "|" + e.get("code").asString() + "|"
                + e.get("message").asString()));
        assertThat(obtenus).containsExactly(
                "nom|NOM_REQUIS|Le nom est obligatoire.",
                "date|DATE_REQUISE|La date est obligatoire.",
                "distanceBoucleMetres|DISTANCE_REQUISE|La distance d'une boucle est obligatoire.",
                "dureeBoucleMinutes|DUREE_REQUISE|La durée d'une boucle est obligatoire.",
                "denivelePositifBoucleMetres|DENIVELE_REQUIS|Le dénivelé positif d'une boucle est obligatoire.",
                "nombreMaxParticipants|NOMBRE_MAX_PARTICIPANTS_REQUIS|Le nombre maximum de participants est obligatoire.",
                "nombreMaxBoucles|NOMBRE_MAX_BOUCLES_REQUIS|Le nombre maximum de boucles est obligatoire.");
        assertThat(ligne(x)).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA9 : distance, durée, dénivelé, participants et boucles hors bornes (0, -1, borne haute + 1) avec messages exacts ; dénivelé 0 sans erreur ; Course intacte")
    void ca9_nombres_hors_bornes() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);

        verifierBornes(x, "distanceBoucleMetres", "DISTANCE_HORS_BORNES",
                "La distance d'une boucle doit être comprise entre 1 et 50000 m.", List.of(0, -1, 50001));
        verifierBornes(x, "dureeBoucleMinutes", "DUREE_HORS_BORNES",
                "La durée d'une boucle doit être comprise entre 1 et 1440 min.", List.of(0, -1, 1441));
        verifierBornes(x, "denivelePositifBoucleMetres", "DENIVELE_HORS_BORNES",
                "Le dénivelé positif d'une boucle doit être compris entre 0 et 10000 m.", List.of(-1, 10001));
        verifierBornes(x, "nombreMaxParticipants", "NOMBRE_MAX_PARTICIPANTS_HORS_BORNES",
                "Le nombre maximum de participants doit être compris entre 1 et 5000.", List.of(0, -1, 5001));
        verifierBornes(x, "nombreMaxBoucles", "NOMBRE_MAX_BOUCLES_HORS_BORNES",
                "Le nombre maximum de boucles doit être compris entre 1 et 500.", List.of(0, -1, 501));
        assertThat(ligne(x)).isEqualTo(avant);

        ObjectNode plat = modification();
        plat.put("denivelePositifBoucleMetres", 0);
        plat.put("distanceBoucleMetres", 0);
        JsonNode erreurs = verifierValidation(modifier(patron, x, plat.toString()));
        assertThat(erreurs).hasSize(1);
        assertThat(erreurs.get(0).get("champ").asString()).isEqualTo("distanceBoucleMetres");
    }

    @Test
    @DisplayName("CA9 : nom de 2 et 101 caractères NOM_LONGUEUR, avec tabulation NOM_CARACTERES, vide NOM_REQUIS, sans écho ; Course intacte")
    void ca9_nom_invalide() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);

        for (String nom : List.of("ab", "x".repeat(101))) {
            ObjectNode corps = modification();
            corps.put("nom", nom);
            HttpResponse<String> reponse = modifier(patron, x, corps.toString());
            JsonNode erreurs = verifierValidation(reponse);
            assertThat(erreurs).hasSize(1);
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("NOM_LONGUEUR");
            assertThat(erreurs.get(0).get("message").asString())
                    .isEqualTo("Le nom doit faire entre 3 et 100 caractères.");
            assertThat(reponse.body()).doesNotContain("xxxxx");
        }
        ObjectNode tabulation = modification();
        tabulation.put("nom", "a\tb c");
        JsonNode caracteres = verifierValidation(modifier(patron, x, tabulation.toString()));
        assertThat(caracteres.get(0).get("code").asString()).isEqualTo("NOM_CARACTERES");
        assertThat(caracteres.get(0).get("message").asString())
                .isEqualTo("Le nom ne doit pas contenir de caractère de contrôle.");
        ObjectNode vide = modification();
        vide.put("nom", "   ");
        assertThat(verifierValidation(modifier(patron, x, vide.toString())).get(0).get("code").asString())
                .isEqualTo("NOM_REQUIS");
        assertThat(ligne(x)).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA9 : date 2026-10-02 (différente de l'enregistrée) DATE_PASSEE, 2031-10-05 DATE_TROP_LOINTAINE, messages exacts sans écho ; plusieurs violations dans l'ordre")
    void ca9_dates_hors_limites_et_ordre() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);

        ObjectNode passee = modification();
        passee.put("date", "2026-10-02");
        HttpResponse<String> reponsePassee = modifier(patron, x, passee.toString());
        JsonNode erreursPassee = verifierValidation(reponsePassee);
        assertThat(erreursPassee).hasSize(1);
        assertThat(erreursPassee.get(0).get("champ").asString()).isEqualTo("date");
        assertThat(erreursPassee.get(0).get("code").asString()).isEqualTo("DATE_PASSEE");
        assertThat(erreursPassee.get(0).get("message").asString()).isEqualTo("La date ne peut pas être dans le passé.");
        assertThat(reponsePassee.body()).doesNotContain("2026-10-02");

        ObjectNode lointaine = modification();
        lointaine.put("date", "2031-10-05");
        HttpResponse<String> reponseLointaine = modifier(patron, x, lointaine.toString());
        JsonNode erreursLointaine = verifierValidation(reponseLointaine);
        assertThat(erreursLointaine.get(0).get("code").asString()).isEqualTo("DATE_TROP_LOINTAINE");
        assertThat(erreursLointaine.get(0).get("message").asString())
                .isEqualTo("La date ne peut pas dépasser de plus de 5 ans la date du jour.");
        assertThat(reponseLointaine.body()).doesNotContain("2031-10-05");

        ObjectNode plusieurs = modification();
        plusieurs.put("nom", "ab");
        plusieurs.put("distanceBoucleMetres", 0);
        plusieurs.put("date", "2026-10-02");
        HttpResponse<String> reponse = modifier(patron, x, plusieurs.toString());
        JsonNode erreurs = verifierValidation(reponse);
        assertThat(erreurs).hasSize(3);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo("NOM_LONGUEUR");
        assertThat(erreurs.get(1).get("code").asString()).isEqualTo("DATE_PASSEE");
        assertThat(erreurs.get(2).get("code").asString()).isEqualTo("DISTANCE_HORS_BORNES");
        assertThat(reponse.body()).doesNotContain("2026-10-02").doesNotContain("\"ab\"");
        assertThat(ligne(x)).isEqualTo(avant);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : corps non JSON, tableau, décimal, chaîne, booléen, entier de 25 chiffres, dates mal formées, nom numérique : 400 CORPS_ILLISIBLE sans écho ; Course intacte")
    void ca10_corps_illisibles() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);
        List<String> corpsIllisibles = List.of(
                "ceci n'est pas du json",
                "[]",
                remplacer("distanceBoucleMetres", "1.5"),
                remplacer("distanceBoucleMetres", "\"12\""),
                remplacer("distanceBoucleMetres", "true"),
                remplacer("distanceBoucleMetres", "1234567890123456789012345"),
                remplacer("date", "\"14/11/2026\""),
                remplacer("date", "\"2026-02-30\""),
                remplacer("nom", "123"));

        for (String corps : corpsIllisibles) {
            HttpResponse<String> reponse = modifier(patron, x, corps);
            assertErreur(reponse, 400, "CORPS_ILLISIBLE", "Requête invalide", "Le corps de la requête est illisible.");
            assertThat(reponse.body()).doesNotContain("14/11/2026").doesNotContain("2026-02-30")
                    .doesNotContain("1234567890123456789012345").doesNotContain("ceci n'est pas");
        }
        assertThat(ligne(x)).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA10 : entier 64 bits hors bornes (99999999999) donne DISTANCE_HORS_BORNES ; Content-Type text/plain donne 415 ; corps illisible sur un id inconnu ou non UUID donne 400 CORPS_ILLISIBLE")
    void ca10_cas_particuliers() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);

        JsonNode erreurs = verifierValidation(modifier(patron, x, remplacer("distanceBoucleMetres", "99999999999")));
        assertThat(erreurs).hasSize(1);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo("DISTANCE_HORS_BORNES");

        assertThat(api.requete("PUT", CHEMIN + "/" + x, patron.entetes(), "text/plain", modification().toString())
                .statusCode()).isEqualTo(415);
        assertThat(api.requete("PUT", CHEMIN + "/" + ID_INCONNU, patron.entetes(), "text/plain",
                modification().toString()).statusCode()).isEqualTo(415);
        // Le rôle précède le type de contenu : Alice reçoit 403 même avec text/plain.
        assertErreur(api.requete("PUT", CHEMIN + "/" + x, alice.entetes(), "text/plain", "x"), 403, "ACCES_REFUSE",
                "Accès refusé", ACCES_REFUSE_DETAIL);

        for (String cible : List.of(ID_INCONNU, "inexistant")) {
            assertErreur(modifier(patron, cible, "ceci n'est pas du json"), 400, "CORPS_ILLISIBLE",
                    "Requête invalide", "Le corps de la requête est illisible.");
        }
        assertThat(ligne(x)).isEqualTo(avant);
        assertThat(compterCourses()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : date inchangée passée (2026-10-01) acceptée, 2026-10-02 DATE_PASSEE, 2026-10-03 acceptée, 2031-10-03 (borne) acceptée, 2031-10-04 et 2031-10-05 DATE_TROP_LOINTAINE, pour Patron et Nadia")
    void ca11_date_inchangee_et_modifiee() throws Exception {
        for (Session session : List.of(patron, nadia)) {
            jdbc.update("delete from course");
            String y = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
            jdbc.update("update course set date_course = ? where id = ?", java.sql.Date.valueOf("2026-10-01"),
                    UUID.fromString(y));
            ObjectNode corps = modification();
            corps.put("dureeBoucleMinutes", 45);

            corps.put("date", "2026-10-01");
            HttpResponse<String> inchangee = modifier(session, y, corps.toString());
            assertThat(inchangee.statusCode()).isEqualTo(200);
            JsonNode relue = lireListe(lister(session)).get(0);
            assertThat(relue.get("dureeBoucleMinutes").asInt()).isEqualTo(45);
            assertThat(relue.get("date").asString()).isEqualTo("2026-10-01");

            Map<String, Object> avant = ligne(y);
            corps.put("date", "2026-10-02");
            JsonNode erreurs = verifierValidation(modifier(session, y, corps.toString()));
            assertThat(erreurs).hasSize(1);
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("DATE_PASSEE");
            assertThat(ligne(y)).isEqualTo(avant);

            corps.put("date", "2026-10-03");
            assertThat(modifier(session, y, corps.toString()).statusCode()).isEqualTo(200);
            // Borne haute : date du jour (2026-10-03) + 5 ans = 2031-10-03 (RG4 ; la spec CA11 écrit 2031-10-04).
            corps.put("date", "2031-10-03");
            assertThat(modifier(session, y, corps.toString()).statusCode()).isEqualTo(200);
            Map<String, Object> avantLointaine = ligne(y);
            for (String trop : List.of("2031-10-04", "2031-10-05")) {
                corps.put("date", trop);
                JsonNode lointaine = verifierValidation(modifier(session, y, corps.toString()));
                assertThat(lointaine.get(0).get("code").asString()).isEqualTo("DATE_TROP_LOINTAINE");
                assertThat(ligne(y)).isEqualTo(avantLointaine);
            }
        }
    }

    @Test
    @DisplayName("CA11 : une Course déclarée à date future refuse 2026-10-02 (DATE_PASSEE) et l'absence de date (DATE_REQUISE) ; une date inchangée au-delà de 5 ans reste acceptée")
    void ca11_date_future_et_exemption_haute() throws Exception {
        String z = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        ObjectNode passee = modification();
        passee.put("date", "2026-10-02");
        assertThat(verifierValidation(modifier(patron, z, passee.toString())).get(0).get("code").asString())
                .isEqualTo("DATE_PASSEE");
        ObjectNode sansDate = modification();
        sansDate.remove("date");
        JsonNode erreurs = verifierValidation(modifier(patron, z, sansDate.toString()));
        assertThat(erreurs).hasSize(1);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo("DATE_REQUISE");

        jdbc.update("update course set date_course = ? where id = ?", java.sql.Date.valueOf("2031-10-05"),
                UUID.fromString(z));
        ObjectNode inchangee = modification();
        inchangee.put("date", "2031-10-05");
        assertThat(modifier(patron, z, inchangee.toString()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA11 : la date du jour est celle de Paris (22:30Z = 4 octobre) : date inchangée 2026-10-03 acceptée, 2026-10-03 pour une Course datée autrement refusée")
    void ca11_bascule_de_jour_a_paris() throws Exception {
        horloge.fixer(Instant.parse("2026-10-03T22:30:00Z"));
        String exemptee = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        jdbc.update("update course set date_course = ? where id = ?", java.sql.Date.valueOf("2026-10-03"),
                UUID.fromString(exemptee));
        ObjectNode inchangee = modification();
        inchangee.put("date", "2026-10-03");
        assertThat(modifier(patron, exemptee, inchangee.toString()).statusCode()).isEqualTo(200);

        String autre = creerCourse(patron, "Autre", "2026-11-01");
        JsonNode erreurs = verifierValidation(modifier(patron, autre, inchangee.toString()));
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo("DATE_PASSEE");
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : Course EN_COURS puis TERMINEE : 409 COURSE_NON_MODIFIABLE même avec {}, ligne identique, Nadia 409, Alice 403, corps illisible 400")
    void ca12_course_non_modifiable() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        for (String statut : List.of("EN_COURS", "TERMINEE")) {
            jdbc.update("update course set statut = ? where id = ?", statut, UUID.fromString(x));
            Map<String, Object> avant = ligne(x);

            for (Session admin : List.of(patron, nadia)) {
                assertErreur(modifier(admin, x, modification().toString()), 409, "COURSE_NON_MODIFIABLE", "Conflit",
                        NON_MODIFIABLE_DETAIL);
            }
            HttpResponse<String> invalide = modifier(patron, x, "{}");
            assertErreur(invalide, 409, "COURSE_NON_MODIFIABLE", "Conflit", NON_MODIFIABLE_DETAIL);
            assertThat(invalide.body()).doesNotContain(statut).doesNotContain("Backyard des Crêtes");
            assertErreur(modifier(patron, x, "ceci n'est pas du json"), 400, "CORPS_ILLISIBLE", "Requête invalide",
                    "Le corps de la requête est illisible.");
            assertErreur(modifier(alice, x, modification().toString()), 403, "ACCES_REFUSE", "Accès refusé",
                    ACCES_REFUSE_DETAIL);
            assertThat(ligne(x)).isEqualTo(avant);
        }
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : id inconnu (UUID nul) ou non UUID : 404 COURSE_INTROUVABLE sans écho du chemin, y compris avec {}, aucune ligne créée")
    void ca13_course_introuvable() throws Exception {
        for (String cible : List.of(ID_INCONNU, "inexistant", UUID.randomUUID().toString())) {
            for (String corps : List.of(modification().toString(), "{}")) {
                HttpResponse<String> reponse = modifier(patron, cible, corps);
                assertErreur(reponse, 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
                // Le chemin n'est jamais repris dans title ni detail (le champ standard « instance » le porte).
                JsonNode probleme = json.readTree(reponse.body());
                assertThat(probleme.get("title").asString() + probleme.get("detail").asString())
                        .doesNotContain("inexistant").doesNotContain(cible);
            }
        }
        assertThat(compterCourses()).isZero();
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : deux PUT identiques sur C (200, même état), puis A au nom de B : 200 ; l'ordre de GET suit les dates, B intacte, 3 lignes")
    void ca14_idempotence_ordre_et_noms_identiques() throws Exception {
        String a = creerCourse(patron, "Alpha", "2026-12-01");
        String b = creerCourse(patron, "Beta", "2027-01-10");
        String c = creerCourse(patron, "Gamma", "2026-11-01");
        Map<String, Object> ligneB = ligne(b);
        ObjectNode corpsC = modification();
        corpsC.put("nom", "Gamma");
        corpsC.put("date", "2027-03-01");

        HttpResponse<String> premier = modifier(patron, c, corpsC.toString());
        HttpResponse<String> second = modifier(patron, c, corpsC.toString());

        assertThat(premier.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body())).isEqualTo(json.readTree(premier.body()));
        ObjectNode corpsA = modification();
        corpsA.put("nom", "Beta");
        corpsA.put("date", "2026-12-01");
        assertThat(modifier(patron, a, corpsA.toString()).statusCode()).isEqualTo(200);

        List<JsonNode> liste = lireListe(lister(patron));
        assertThat(liste).extracting(e -> e.get("id").asString()).containsExactly(c, b, a);
        assertThat(liste).extracting(e -> e.get("date").asString())
                .containsExactly("2027-03-01", "2027-01-10", "2026-12-01");
        assertThat(liste.get(1).get("nom").asString()).isEqualTo("Beta");
        assertThat(liste.get(2).get("nom").asString()).isEqualTo("Beta");
        assertThat(ligne(b)).isEqualTo(ligneB);
        assertThat(compterCourses()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : GET, PATCH, DELETE /{id} et PUT sans id : 404 ou 405 pour Patron et Nadia, 403 pour Alice, 401 pour un anonyme ; PUT /{id} valide ne répond plus 404")
    void ca15_autres_chemins_et_methodes() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        Map<String, Object> avant = ligne(x);
        String corps = modification().toString();
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);
        List<String[]> appels = List.of(new String[] {"GET", CHEMIN + "/" + x}, new String[] {"PATCH", CHEMIN + "/" + x},
                new String[] {"DELETE", CHEMIN + "/" + x}, new String[] {"PUT", CHEMIN},
                new String[] {"GET", CHEMIN + "/" + x + "/xxx"});

        for (String[] appel : appels) {
            boolean avecCorps = !"GET".equals(appel[0]) && !"DELETE".equals(appel[0]);
            String contenu = avecCorps ? corps : null;
            String type = avecCorps ? "application/json" : null;
            for (Session admin : List.of(patron, nadia)) {
                HttpResponse<String> reponse = api.requete(appel[0], appel[1], admin.entetes(), type, contenu);
                assertThat(reponse.statusCode()).as(appel[0] + " " + appel[1]).isIn(404, 405);
            }
            assertErreur(api.requete(appel[0], appel[1], alice.entetes(), type, contenu), 403, "ACCES_REFUSE",
                    "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(api.requete(appel[0], appel[1], anonyme, type, contenu), 401, "NON_AUTHENTIFIE",
                    "Authentification requise", "Vous devez être connecté.");
        }
        assertThat(ligne(x)).isEqualTo(avant);
        assertThat(modifier(patron, x, corps).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : une ligne INFO « Course modifiée » avec l'id seul, aucune ligne INFO ou supérieure pour 400, 404 et 409, ni nom ni corps dans le journal")
    void ca17_journal_sans_contenu_saisi() throws Exception {
        String x = creerCourse(patron, "Nom-initial-secret-xyz", "2026-11-14");
        String enCours = creerCourse(patron, "Autre-course-secrete-xyz", "2026-11-15");
        jdbc.update("update course set statut = 'EN_COURS' where id = ?", UUID.fromString(enCours));
        ObjectNode corps = modification();
        corps.put("nom", "Backyard des Alpes");
        corps.put("inconnu", "valeur-secrete-journal-xyz");

        assertThat(modifier(patron, x, corps.toString()).statusCode()).isEqualTo(200);
        int apresSucces = journal.list.size();
        ObjectNode refuse = modification();
        refuse.put("nom", "Nom-refuse-secret-xyz");
        refuse.put("distanceBoucleMetres", 0);
        assertThat(modifier(patron, x, refuse.toString()).statusCode()).isEqualTo(400);
        assertThat(modifier(patron, ID_INCONNU, refuse.toString()).statusCode()).isEqualTo(404);
        assertThat(modifier(patron, enCours, refuse.toString()).statusCode()).isEqualTo(409);

        List<ILoggingEvent> modifications = journal.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Course modifiée")).toList();
        assertThat(modifications).hasSize(1);
        assertThat(modifications.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(modifications.get(0).getFormattedMessage()).contains(x);
        assertThat(journal.list.subList(apresSucces, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        assertThat(journal.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(
                "Backyard des Alpes", "Nom-initial-secret-xyz", "Nom-refuse-secret-xyz",
                "valeur-secrete-journal-xyz"));
        List<String> messagesDExceptions = journal.list.stream()
                .filter(e -> e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null)
                .map(e -> e.getThrowableProxy().getMessage()).toList();
        assertThat(messagesDExceptions).noneMatch(m -> m.contains("Backyard des Alpes")
                || m.contains("Nom-refuse-secret-xyz") || m.contains("valeur-secrete-journal-xyz"));
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : non-régression, POST et GET /courses, /acces, /admins (ADMIN_MASTER seul) et /benevoles se comportent comme avant ; changesets inchangés")
    void ca18_non_regression() throws Exception {
        assertThat(api.requete("POST", CHEMIN, patron.entetes(), "application/json", modification().toString())
                .statusCode()).isEqualTo(201);
        assertThat(lister(nadia).statusCode()).isEqualTo(200);
        assertThat(get(patron, "/api/administration/acces").statusCode()).isEqualTo(204);
        assertThat(get(nadia, "/api/administration/acces").statusCode()).isEqualTo(204);
        assertErreur(get(alice, "/api/administration/acces"), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
        assertThat(get(patron, "/api/administration/admins").statusCode()).isEqualTo(200);
        assertErreur(get(nadia, "/api/administration/admins"), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
        assertThat(get(nadia, "/api/administration/benevoles").statusCode()).isEqualTo(200);
        assertErreur(get(leo, "/api/administration/benevoles"), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course");
    }

    // ---------------------------------------------------------------- utilitaires

    /** Corps valide de modification : tous les champs changent par rapport à la Course de référence. */
    private ObjectNode modification() {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", "Backyard des Alpes");
        corps.put("date", "2026-12-05");
        corps.put("distanceBoucleMetres", 8000);
        corps.put("dureeBoucleMinutes", 45);
        corps.put("denivelePositifBoucleMetres", 0);
        corps.put("nombreMaxParticipants", 80);
        corps.put("nombreMaxBoucles", 12);
        return corps;
    }

    private String remplacer(String champ, String litteralJson) {
        ObjectNode corps = modification();
        corps.put(champ, "@@");
        return corps.toString().replace("\"@@\"", litteralJson);
    }

    private String creerCourse(Session session, String nom, String date) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", date);
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", 50);
        corps.put("nombreMaxBoucles", 24);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, session.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private void verifierBornes(String id, String champ, String code, String message, List<Integer> valeurs)
            throws Exception {
        for (int valeur : valeurs) {
            ObjectNode corps = modification();
            corps.put(champ, valeur);
            JsonNode erreurs = verifierValidation(modifier(patron, id, corps.toString()));
            assertThat(erreurs).as(champ + "=" + valeur).hasSize(1);
            assertThat(erreurs.get(0).get("champ").asString()).isEqualTo(champ);
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo(code);
            assertThat(erreurs.get(0).get("message").asString()).isEqualTo(message);
        }
    }

    private Map<String, Object> ligne(String id) {
        return jdbc.queryForMap("select * from course where id = ?", UUID.fromString(id));
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

    private int compterCourses() {
        return jdbc.queryForObject("select count(*) from course", Integer.class);
    }

    private HttpResponse<String> modifier(Session session, String id, String corps) throws Exception {
        return api.requete("PUT", CHEMIN + "/" + id, session.entetes(), "application/json", corps);
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

    private void assertCsrf(HttpResponse<String> reponse) throws Exception {
        assertErreur(reponse, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
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
