package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.OctetsDeLogo;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 2.5 : suppression d'une Course par l'admin master (CA4 à CA10). Les attentes de 2.1a RG11 et
 * 2.4 CA10 rendues obsolètes sont mises à jour dans les suites de ces incréments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuppressionCourseIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String CHEMIN_BENEVOLE = "/api/benevole/courses";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String INTROUVABLE_DETAIL = "La course est introuvable.";
    static final String NON_SUPPRIMABLE_DETAIL = "La course n'est plus en préparation : elle ne peut plus être supprimée.";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHANGESETS = List.of("0002-compte", "0003-admin-master-unique", "0004-course",
            "0005-logo-course", "0006-affectation-benevole", "0007-inscription");

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

    @Autowired
    org.springframework.core.env.Environment environnement;

    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    ListAppender<ILoggingEvent> journal;
    Session patron;
    Session nadia;
    Session alice;
    Session leo;
    Session marc;
    UUID idNadia;
    UUID idLeo;
    UUID idMarc;

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
        idNadia = insererCompte("Nadia", "ADMIN");
        insererCompte("Alice", "COUREUR");
        idLeo = insererCompte("Léo", "BENEVOLE");
        idMarc = insererCompte("Marc", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        marc = api.ouvrir("Marc", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Patron supprime X (logo, {Léo, Marc}) : 204 sans corps, X introuvable partout, Y, son logo et {Léo} intacts, Léo et Marc existent, second DELETE 404")
    void ca4_suppression_nominale_et_effets() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String y = creerCourse(patron, "Autre course", "2026-12-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(envoyerLogo(patron, y).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo, idMarc).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, y, idLeo).statusCode()).isEqualTo(200);
        assertThat(ids(lireMesCourses(leo))).containsExactlyInAnyOrder(x, y);

        HttpResponse<String> suppression = supprimer(patron, x);

        assertThat(suppression.statusCode()).isEqualTo(204);
        assertThat(suppression.body()).isEmpty();
        assertErreur(api.requete("GET", CHEMIN + "/" + x, patron.enteteLecture(), null, null), 404,
                "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
        assertThat(ids(api.requete("GET", CHEMIN, patron.enteteLecture(), null, null))).containsExactly(y);
        HttpResponse<String> logoSupprime = lireLogoPublic(x);
        assertErreur(logoSupprime, 404, "LOGO_INTROUVABLE", "Introuvable", "Le logo est introuvable.");
        assertErreur(api.requete("PUT", CHEMIN + "/" + x, patron.entetes(), "application/json",
                corpsCourse("Backyard des Alpes", "2026-12-05").toString()), 404, "COURSE_INTROUVABLE", "Introuvable",
                INTROUVABLE_DETAIL);
        assertErreur(affecter(patron, x, idLeo), 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
        assertErreur(tenterEnvoiLogo(patron, x), 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
        assertErreur(api.requete("DELETE", CHEMIN + "/" + x + "/logo", patron.entetes(), null, null), 404,
                "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
        // Léo et Marc, connectés avant la suppression, sans reconnexion.
        assertThat(ids(lireMesCourses(leo))).containsExactly(y);
        assertThat(ids(lireMesCourses(marc))).isEmpty();
        assertErreur(supprimer(patron, x), 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);

        assertThat(lireLogoPublic(y).statusCode()).isEqualTo(200);
        assertThat(compter("logo_course", y)).isEqualTo(1);
        assertThat(affectationsDe(y)).containsExactly(idLeo);
        assertThat(jdbc.queryForObject("select count(*) from compte where id in (?, ?)", Integer.class, idLeo, idMarc))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("CA4 : une Course sans logo ni bénévole et une Course de Nadia se suppriment en 204 ; une Course de même nom redéclarée n'hérite ni logo ni bénévole")
    void ca4_sans_logo_ni_benevole_course_de_nadia_et_nom_reutilise() throws Exception {
        String nue = creerCourse(patron, "Nue", "2026-11-14");
        String deNadia = creerCourse(nadia, "Course de Nadia", "2026-11-15");
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-16");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo, idMarc).statusCode()).isEqualTo(200);

        assertThat(supprimer(patron, nue).statusCode()).isEqualTo(204);
        assertThat(supprimer(patron, deNadia).statusCode()).isEqualTo(204);
        assertThat(supprimer(patron, x).statusCode()).isEqualTo(204);

        HttpResponse<String> creation = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                corpsCourse("Backyard des Crêtes", "2026-11-16").toString());
        assertThat(creation.statusCode()).isEqualTo(201);
        JsonNode neuve = json.readTree(creation.body());
        assertThat(neuve.get("id").asString()).isNotEqualTo(x);
        assertThat(neuve.get("logoUrl").isNull()).isTrue();
        JsonNode fiche = json.readTree(api.requete("GET", CHEMIN + "/" + neuve.get("id").asString(),
                patron.enteteLecture(), null, null).body());
        assertThat(fiche.get("benevoleIds")).isEmpty();
        JsonNode liste = json.readTree(api.requete("GET", CHEMIN, patron.enteteLecture(), null, null).body());
        assertThat(liste).hasSize(1);
        assertThat(liste.get(0).get("logoUrl").isNull()).isTrue();
        assertThat(affectationsDe(neuve.get("id").asString())).isEmpty();
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : delete from course en SQL direct emporte logo_course et affectation_benevole de X (cascade), pas ceux de Y ; sans changeset ajouté, ddl-auto=validate")
    void ca5_cascade_en_sql_direct() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String y = creerCourse(patron, "Autre course", "2026-12-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(envoyerLogo(patron, y).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo, idMarc).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, y, idLeo).statusCode()).isEqualTo(200);
        assertThat(compter("logo_course", x)).isEqualTo(1);
        assertThat(compter("affectation_benevole", x)).isEqualTo(2);

        jdbc.update("delete from course where id = ?", UUID.fromString(x));

        assertThat(compter("course", x)).isZero();
        assertThat(compter("logo_course", x)).isZero();
        assertThat(compter("affectation_benevole", x)).isZero();
        assertThat(compter("logo_course", y)).isEqualTo(1);
        assertThat(compter("affectation_benevole", y)).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    @DisplayName("CA5 : la suppression par DELETE de l'API laisse 0 ligne course, logo_course et affectation_benevole pour X et conserve celles de Y")
    void ca5_cascade_par_l_api() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String y = creerCourse(patron, "Autre course", "2026-12-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(envoyerLogo(patron, y).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo, idMarc).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, y, idLeo).statusCode()).isEqualTo(200);

        assertThat(supprimer(patron, x).statusCode()).isEqualTo(204);

        assertThat(compter("course", x)).isZero();
        assertThat(compter("logo_course", x)).isZero();
        assertThat(compter("affectation_benevole", x)).isZero();
        assertThat(compter("course", y)).isEqualTo(1);
        assertThat(compter("logo_course", y)).isEqualTo(1);
        assertThat(compter("affectation_benevole", y)).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : anonyme 401, Nadia (créatrice), Alice et Léo (affecté) 403 ACCES_REFUSE ; Nadia sur id inconnu 403 (pas 404) et sur EN_COURS 403 (pas 409) ; X intacte")
    void ca6_securite_roles_et_authentification() throws Exception {
        String x = creerCourse(nadia, "Backyard des Crêtes", "2026-11-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo).statusCode()).isEqualTo(200);
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        assertErreur(api.requete("DELETE", CHEMIN + "/" + x, anonyme, null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
        for (Session session : List.of(nadia, alice, leo)) {
            assertErreur(supprimer(session, x), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        }
        assertErreur(supprimer(nadia, ID_INCONNU), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(supprimer(nadia, "inexistant"), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        changerStatut(x, "EN_COURS");
        assertErreur(supprimer(nadia, x), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        changerStatut(x, "EN_PREPARATION");
        assertIntacte(x, 1);
    }

    @Test
    @DisplayName("CA6 : sans X-XSRF-TOKEN, avec un jeton différent ou sans cookie, Patron reçoit 403 CSRF_INVALIDE avant tout contrôle ; X intacte")
    void ca6_csrf() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo).statusCode()).isEqualTo(200);

        List<Map<String, String>> variantes = List.of(patron.enteteLecture(),
                Map.of("Cookie", "JSESSIONID=" + patron.id() + "; XSRF-TOKEN=" + patron.xsrf(), "X-XSRF-TOKEN",
                        "autre-valeur"),
                Map.of("X-XSRF-TOKEN", patron.xsrf()));
        for (Map<String, String> entetes : variantes) {
            for (String cible : List.of(x, ID_INCONNU)) {
                assertErreur(api.requete("DELETE", CHEMIN + "/" + cible, entetes, null, null), 403, "CSRF_INVALIDE",
                        "Accès refusé", "Jeton CSRF absent ou invalide.");
            }
        }
        assertIntacte(x, 1);
    }

    @Test
    @DisplayName("CA6 : DELETE sans id, PATCH /X et DELETE /X/benevoles par Patron donnent 404 ou 405, jamais 2xx ; X intacte")
    void ca6_autres_chemins_et_methodes() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo).statusCode()).isEqualTo(200);

        HttpResponse<String> sansId = api.requete("DELETE", CHEMIN, patron.entetes(), null, null);
        assertThat(sansId.statusCode()).isIn(404, 405);
        if (sansId.statusCode() == 404) {
            assertErreur(sansId, 404, "RESSOURCE_INTROUVABLE", "Introuvable", "La ressource demandée est introuvable.");
        }
        assertThat(api.requete("PATCH", CHEMIN + "/" + x, patron.entetes(), "application/json", "{}").statusCode())
                .isIn(404, 405);
        assertThat(api.requete("DELETE", CHEMIN + "/" + x + "/benevoles", patron.entetes(), null, null).statusCode())
                .isIn(404, 405);
        assertIntacte(x, 1);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : X EN_COURS puis TERMINEE : 409 COURSE_NON_SUPPRIMABLE, ligne, logo et affectations intacts, GET fiche et logo en 200 ; remise EN_PREPARATION : 204")
    void ca7_course_demarree_ou_terminee_non_supprimable() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String autre = creerCourse(patron, "Autre course", "2026-12-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo, idMarc).statusCode()).isEqualTo(200);
        int coursesAvant = jdbc.queryForObject("select count(*) from course", Integer.class);

        for (String statut : List.of("EN_COURS", "TERMINEE")) {
            changerStatut(x, statut);
            assertErreur(supprimer(patron, x), 409, "COURSE_NON_SUPPRIMABLE", "Conflit", NON_SUPPRIMABLE_DETAIL);
            assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class,
                    UUID.fromString(x))).isEqualTo(statut);
            assertIntacte(x, 2);
            assertThat(api.requete("GET", CHEMIN + "/" + x, patron.enteteLecture(), null, null).statusCode())
                    .isEqualTo(200);
            assertThat(lireLogoPublic(x).statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(coursesAvant);
        }
        changerStatut(x, "EN_PREPARATION");
        assertThat(supprimer(patron, x).statusCode()).isEqualTo(204);
        assertThat(compter("course", autre)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA7 : id inconnu et id non UUID donnent 404 COURSE_INTROUVABLE sans écho, jamais 400 ; aucune ligne créée ni supprimée")
    void ca7_id_inconnu_ou_non_uuid() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        for (String id : List.of(ID_INCONNU, "inexistant")) {
            HttpResponse<String> reponse = supprimer(patron, id);
            assertErreur(reponse, 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
            ObjectNode corps = (ObjectNode) json.readTree(reponse.body());
            corps.remove("instance"); // le chemin appelé figure dans « instance » (comportement des autres 404)
            assertThat(corps.toString()).doesNotContain("inexistant");
        }
        assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(1);
        assertThat(compter("course", x)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : une seule ligne INFO « Course supprimée » avec l'id de X, rien pour 403, 404 et 409, ni nom, ni pseudo, ni UUID de bénévole dans le journal")
    void ca8_journal() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String enCours = creerCourse(patron, "Course démarrée secrète", "2026-12-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, x, idLeo, idMarc).statusCode()).isEqualTo(200);
        changerStatut(enCours, "EN_COURS");
        demarrerJournal();

        assertThat(supprimer(patron, x).statusCode()).isEqualTo(204);
        int apresSucces = journal.list.size();
        assertThat(supprimer(nadia, x).statusCode()).isEqualTo(403);
        assertThat(supprimer(patron, ID_INCONNU).statusCode()).isEqualTo(404);
        assertThat(supprimer(patron, enCours).statusCode()).isEqualTo(409);

        List<ILoggingEvent> suppressions = journal.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Course supprimée")).toList();
        assertThat(suppressions).hasSize(1);
        assertThat(suppressions.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(suppressions.get(0).getFormattedMessage()).contains(x);
        assertThat(journal.list.subList(apresSucces, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        assertThat(journal.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(
                "Backyard des Crêtes", "Patron", "Léo", "Marc", idLeo.toString(), idMarc.toString()));
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : deux DELETE simultanés de X par Patron : exactement un 204 et un 404, jamais de 500 (plusieurs tours)")
    void ca9_delete_delete_concurrents() throws Exception {
        for (int tour = 0; tour < 8; tour++) {
            String x = creerCourse(patron, "Concurrence " + tour, "2026-11-14");
            assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
            assertThat(affecter(patron, x, idLeo, idMarc).statusCode()).isEqualTo(200);

            List<Integer> statuts = lancerEnParallele(() -> supprimer(patron, x).statusCode(),
                    () -> supprimer(patron, x).statusCode());

            assertThat(statuts).as("tour " + tour).containsExactlyInAnyOrder(204, 404);
            assertAbsente(x);
        }
    }

    @Test
    @DisplayName("CA9 : DELETE et PUT /X/benevoles simultanés : DELETE 204, PUT 200 ou 404, jamais de 500, aucune ligne course, logo_course ni affectation_benevole pour X")
    void ca9_delete_et_affectation_concurrents() throws Exception {
        for (int tour = 0; tour < 8; tour++) {
            String x = creerCourse(patron, "Concurrence " + tour, "2026-11-14");
            assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
            assertThat(affecter(patron, x, idMarc).statusCode()).isEqualTo(200);

            List<Integer> statuts = lancerEnParallele(() -> supprimer(patron, x).statusCode(),
                    () -> affecter(patron, x, idLeo).statusCode());

            assertThat(statuts.get(0)).as("DELETE, tour " + tour).isEqualTo(204);
            assertThat(statuts.get(1)).as("PUT benevoles, tour " + tour).isIn(200, 404);
            assertAbsente(x);
        }
    }

    @Test
    @DisplayName("CA9 (RG6) : DELETE et PUT de logo simultanés : DELETE 204, PUT 200 ou 404, jamais de 500, aucune ligne course ni logo_course pour X")
    void ca9_delete_et_envoi_de_logo_concurrents() throws Exception {
        for (int tour = 0; tour < 8; tour++) {
            String x = creerCourse(patron, "Concurrence " + tour, "2026-11-14");
            assertThat(affecter(patron, x, idMarc).statusCode()).isEqualTo(200);

            List<Integer> statuts = lancerEnParallele(() -> supprimer(patron, x).statusCode(),
                    () -> envoiLogoBrut(patron, x).statusCode());

            assertThat(statuts.get(0)).as("DELETE, tour " + tour).isEqualTo(204);
            assertThat(statuts.get(1)).as("PUT logo, tour " + tour).isIn(200, 404);
            assertAbsente(x);
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : /api/csrf et la lecture du logo restent publics, /api/administration/** garde ses règles de rôle (ADMIN accepté hors DELETE), changesets 0002 à 0006")
    void ca10_non_regression() throws Exception {
        String x = creerCourse(nadia, "Backyard des Crêtes", "2026-11-14");
        assertThat(envoyerLogo(nadia, x).statusCode()).isEqualTo(200);

        assertThat(api.requete("GET", "/api/sante", Map.of(), null, null).statusCode()).isNotIn(401, 403);
        assertThat(api.requete("GET", "/api/csrf", Map.of(), null, null).statusCode()).isIn(200, 204);
        assertThat(lireLogoPublic(x).statusCode()).isEqualTo(200);
        assertThat(api.requete("GET", CHEMIN, nadia.enteteLecture(), null, null).statusCode()).isEqualTo(200);
        assertThat(api.requete("GET", CHEMIN + "/" + x, nadia.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
        assertThat(api.requete("PUT", CHEMIN + "/" + x, nadia.entetes(), "application/json",
                corpsCourse("Backyard des Alpes", "2026-12-05").toString()).statusCode()).isEqualTo(200);
        assertThat(affecter(nadia, x, idLeo).statusCode()).isEqualTo(200);
        assertThat(envoiLogoBrut(nadia, x).statusCode()).isEqualTo(200);
        assertThat(api.requete("POST", CHEMIN, nadia.entetes(), "application/json",
                corpsCourse("Seconde", "2026-12-06").toString()).statusCode()).isEqualTo(201);
        assertThat(api.requete("GET", "/api/administration/admins", nadia.enteteLecture(), null, null).statusCode())
                .isEqualTo(403);
        assertErreur(supprimer(nadia, x), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertIntacte(x, 1);
    }

    // ---------------------------------------------------------------- utilitaires

    private List<Integer> lancerEnParallele(Callable<Integer> premier, Callable<Integer> second) throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        ExecutorService executeur = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executeur.submit(() -> {
                depart.await();
                return premier.call();
            });
            Future<Integer> b = executeur.submit(() -> {
                depart.await();
                return second.call();
            });
            depart.countDown();
            List<Integer> statuts = List.of(a.get(), b.get());
            assertThat(statuts).noneMatch(s -> s >= 500);
            return statuts;
        } finally {
            executeur.shutdownNow();
        }
    }

    private void assertAbsente(String idCourse) {
        assertThat(compter("course", idCourse)).isZero();
        assertThat(compter("logo_course", idCourse)).isZero();
        assertThat(compter("affectation_benevole", idCourse)).isZero();
    }

    private void assertIntacte(String idCourse, int affectations) {
        assertThat(compter("course", idCourse)).isEqualTo(1);
        assertThat(compter("logo_course", idCourse)).isEqualTo(1);
        assertThat(compter("affectation_benevole", idCourse)).isEqualTo(affectations);
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
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, session.entetes(), "application/json",
                corpsCourse(nom, date).toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private void changerStatut(String idCourse, String statut) {
        jdbc.update("update course set statut = ? where id = ?", statut, UUID.fromString(idCourse));
    }

    private HttpResponse<String> supprimer(Session session, String idCourse) throws Exception {
        return api.requete("DELETE", CHEMIN + "/" + idCourse, session.entetes(), null, null);
    }

    private HttpResponse<String> affecter(Session session, String idCourse, UUID... benevoles) throws Exception {
        ArrayNode tableau = json.createArrayNode();
        List.of(benevoles).forEach(i -> tableau.add(i.toString()));
        ObjectNode corps = json.createObjectNode();
        corps.set("benevoleIds", tableau);
        return api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles", session.entetes(), "application/json",
                corps.toString());
    }

    private HttpResponse<String> lireMesCourses(Session session) throws Exception {
        return api.requete("GET", CHEMIN_BENEVOLE, session.enteteLecture(), null, null);
    }

    private List<String> ids(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(200);
        List<String> resultat = new ArrayList<>();
        json.readTree(reponse.body()).forEach(n -> resultat.add(n.get("id").asString()));
        return resultat;
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
        HttpResponse<byte[]> reponse = envoiLogoBrut(session, idCourse);
        return reponseTexte(reponse);
    }

    private HttpResponse<String> tenterEnvoiLogo(Session session, String idCourse) throws Exception {
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
