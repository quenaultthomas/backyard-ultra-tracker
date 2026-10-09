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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
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
 * Contrat de l'incrément 2.4 : fiche d'une Course, affectation des bénévoles et Courses du bénévole
 * (CA7 à CA12, CA14, CA15 et les attentes de non-régression de CA16). Le schéma (CA13) est dans
 * {@link AffectationBenevolesSchemaRedemarrageIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class AffectationBenevolesIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String CHEMIN_BENEVOLE = "/api/benevole/courses";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String INTROUVABLE_DETAIL = "La course est introuvable.";
    static final String TERMINEE_DETAIL = "La course est terminée : ses bénévoles ne peuvent plus être modifiés.";
    static final String BENEVOLE_INCONNU_MESSAGE = "Un des comptes choisis n'est pas un bénévole.";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHAMPS_FICHE = List.of("id", "nom", "date", "statut", "distanceBoucleMetres",
            "dureeBoucleMinutes", "denivelePositifBoucleMetres", "nombreMaxParticipants", "nombreMaxBoucles", "logoUrl",
            "benevoleIds", "demarreeLe");
    static final List<String> CHAMPS_COURSE = CHAMPS_FICHE.subList(0, 10);
    static final List<String> CHAMPS_COURSE_BENEVOLE = List.of("id", "nom", "date", "statut", "logoUrl");

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

    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    ListAppender<ILoggingEvent> journal;
    Session patron;
    Session nadia;
    Session alice;
    Session leo;
    UUID idAlice;
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
        idAlice = insererCompte("Alice", "COUREUR");
        idLeo = insererCompte("Léo", "BENEVOLE");
        idMarc = insererCompte("Marc", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : Patron et Nadia affectent [Marc, Léo, Léo] : 200, onze champs, ids triés, GET identique, 2 lignes ; puis [Léo] : 1 ligne ; [] : 0 ligne ; Y reste à []")
    void ca7_affectation_nominale_et_persistance() throws Exception {
        for (Session admin : List.of(patron, nadia)) {
            jdbc.update("delete from course");
            String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
            String y = creerCourse(patron, "Autre course", "2026-12-14");
            List<String> tries = List.of(idLeo.toString(), idMarc.toString()).stream().sorted().toList();

            HttpResponse<String> reponse = affecter(admin, x, ids(idMarc, idLeo, idLeo));

            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                    c -> assertThat(c).contains("application/json"));
            JsonNode fiche = json.readTree(reponse.body());
            assertThat(fiche.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_FICHE);
            assertThat(fiche.get("id").asString()).isEqualTo(x);
            assertThat(textes(fiche.get("benevoleIds"))).containsExactlyElementsOf(tries);
            HttpResponse<String> lecture = lireFiche(admin, x);
            assertThat(lecture.statusCode()).isEqualTo(200);
            assertThat(json.readTree(lecture.body())).isEqualTo(fiche);
            assertThat(affectationsDe(x)).containsExactlyInAnyOrder(idLeo, idMarc);

            assertThat(affecter(admin, x, ids(idLeo)).statusCode()).isEqualTo(200);
            assertThat(affectationsDe(x)).containsExactly(idLeo);
            HttpResponse<String> vide = affecter(admin, x, ids());
            assertThat(vide.statusCode()).isEqualTo(200);
            assertThat(json.readTree(vide.body()).get("benevoleIds")).isEmpty();
            assertThat(affectationsDe(x)).isEmpty();
            assertThat(json.readTree(lireFiche(admin, y).body()).get("benevoleIds")).isEmpty();
        }
    }

    @Test
    @DisplayName("CA7 : deux PUT identiques donnent deux 200 et le même état ; une Course sans affectation a benevoleIds [] ; l'affectation d'une Course n'altère pas l'autre")
    void ca7_idempotence_et_isolation_entre_courses() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String y = creerCourse(patron, "Autre course", "2026-12-14");
        assertThat(json.readTree(lireFiche(patron, x).body()).get("benevoleIds")).isEmpty();
        assertThat(affecter(patron, y, ids(idLeo)).statusCode()).isEqualTo(200);

        HttpResponse<String> premier = affecter(patron, x, ids(idLeo, idMarc));
        HttpResponse<String> second = affecter(patron, x, ids(idLeo, idMarc));

        assertThat(premier.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(second.body())).isEqualTo(json.readTree(premier.body()));
        assertThat(affectationsDe(x)).containsExactlyInAnyOrder(idLeo, idMarc);
        assertThat(affectationsDe(y)).containsExactly(idLeo);
        assertThat(affecter(patron, x, ids()).statusCode()).isEqualTo(200);
        assertThat(affectationsDe(y)).containsExactly(idLeo);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : un id d'Alice, de Nadia ou aléatoire donne 400 BENEVOLE_INCONNU exact sans écho, et X garde {Léo} (Marc non ajouté)")
    void ca8_benevole_inconnu_atomique() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);
        UUID aleatoire = UUID.randomUUID();

        for (String corps : List.of(ids(idMarc, idAlice), ids(idNadia), ids(aleatoire))) {
            HttpResponse<String> reponse = affecter(patron, x, corps);
            JsonNode erreurs = verifierValidation(reponse);
            assertThat(erreurs).hasSize(1);
            assertThat(erreurs.get(0).propertyNames()).containsExactlyInAnyOrder("champ", "code", "message");
            assertThat(erreurs.get(0).get("champ").asString()).isEqualTo("benevoleIds");
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("BENEVOLE_INCONNU");
            assertThat(erreurs.get(0).get("message").asString()).isEqualTo(BENEVOLE_INCONNU_MESSAGE);
            assertThat(reponse.body()).doesNotContain(idAlice.toString()).doesNotContain(idNadia.toString())
                    .doesNotContain(aleatoire.toString()).doesNotContain(idMarc.toString());
            assertThat(affectationsDe(x)).containsExactly(idLeo);
        }
    }

    @Test
    @DisplayName("CA8 : {}, benevoleIds null et [null] donnent BENEVOLES_REQUIS ; 501 identifiants BENEVOLES_TROP_NOMBREUX ; messages exacts ; ensemble inchangé")
    void ca8_format_requis_et_taille() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);

        for (String corps : List.of("{}", "{\"benevoleIds\":null}", "{\"benevoleIds\":[null]}",
                "{\"benevoleIds\":[\"" + idLeo + "\",null]}")) {
            JsonNode erreurs = verifierValidation(affecterBrut(patron, x, corps));
            assertThat(erreurs).hasSize(1);
            assertThat(erreurs.get(0).get("champ").asString()).isEqualTo("benevoleIds");
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("BENEVOLES_REQUIS");
            assertThat(erreurs.get(0).get("message").asString()).isEqualTo("La liste des bénévoles est obligatoire.");
        }
        List<UUID> tropNombreux = IntStream.range(0, 501).mapToObj(i -> UUID.randomUUID()).toList();
        JsonNode erreurs = verifierValidation(affecterBrut(patron, x, idsListe(tropNombreux)));
        assertThat(erreurs).hasSize(1);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo("BENEVOLES_TROP_NOMBREUX");
        assertThat(erreurs.get(0).get("message").asString())
                .isEqualTo("Une course ne peut pas avoir plus de 500 bénévoles.");
        JsonNode cinqCentUnIdentiques = verifierValidation(
                affecterBrut(patron, x, idsListe(Collections.nCopies(501, idLeo))));
        assertThat(cinqCentUnIdentiques.get(0).get("code").asString()).isEqualTo("BENEVOLES_TROP_NOMBREUX");
        assertThat(affectationsDe(x)).containsExactly(idLeo);
    }

    @Test
    @DisplayName("CA8 : 500 fois l'id de Léo donne 200 avec [Léo] ; liste vide acceptée")
    void ca8_cinq_cents_elements_dedoublonnes() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");

        HttpResponse<String> reponse = affecter(patron, x, idsListe(Collections.nCopies(500, idLeo)));

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(textes(json.readTree(reponse.body()).get("benevoleIds"))).containsExactly(idLeo.toString());
        assertThat(affectationsDe(x)).containsExactly(idLeo);
    }

    @Test
    @DisplayName("CA8 : [\"xyz\"], JSON invalide, benevoleIds objet ou chaîne : 400 CORPS_ILLISIBLE sans écho ; text/plain : 415 ; ensemble inchangé")
    void ca8_corps_illisible_et_type_de_contenu() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);

        for (String corps : List.of("{\"benevoleIds\":[\"xyz\"]}", "ceci n'est pas du json", "[]",
                "{\"benevoleIds\":\"" + idLeo + "\"}", "{\"benevoleIds\":{}}", "{\"benevoleIds\":[1]}",
                "{\"benevoleIds\":[\"\"]}")) {
            HttpResponse<String> reponse = affecterBrut(patron, x, corps);
            assertErreur(reponse, 400, "CORPS_ILLISIBLE", "Requête invalide", "Le corps de la requête est illisible.");
            assertThat(reponse.body()).doesNotContain("xyz").doesNotContain("ceci n'est pas");
        }
        assertThat(api.requete("PUT", CHEMIN + "/" + x + "/benevoles", patron.entetes(), "text/plain", ids(idLeo))
                .statusCode()).isEqualTo(415);
        assertThat(affectationsDe(x)).containsExactly(idLeo);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : EN_COURS accepte l'affectation (200) ; GET /X est 200 pour les trois statuts")
    void ca9_affectation_en_cours_et_lecture_tous_statuts() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");

        assertThat(json.readTree(lireFiche(patron, x).body()).get("statut").asString()).isEqualTo("EN_PREPARATION");
        changerStatut(x, "EN_COURS");
        HttpResponse<String> enCours = affecter(patron, x, ids(idLeo));
        assertThat(enCours.statusCode()).isEqualTo(200);
        assertThat(json.readTree(enCours.body()).get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(affectationsDe(x)).containsExactly(idLeo);
        changerStatut(x, "TERMINEE");
        HttpResponse<String> terminee = lireFiche(patron, x);
        assertThat(terminee.statusCode()).isEqualTo(200);
        assertThat(json.readTree(terminee.body()).get("statut").asString()).isEqualTo("TERMINEE");
        assertThat(textes(json.readTree(terminee.body()).get("benevoleIds"))).containsExactly(idLeo.toString());
        assertThat(lireFiche(nadia, x).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA9 : TERMINEE donne 409 COURSE_TERMINEE pour un PUT valide et pour [Alice] (le statut précède l'annuaire), ensemble inchangé")
    void ca9_course_terminee_refuse_l_affectation() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);
        changerStatut(x, "TERMINEE");

        for (Session admin : List.of(patron, nadia)) {
            for (String corps : List.of(ids(idMarc), ids(idAlice), ids())) {
                HttpResponse<String> reponse = affecter(admin, x, corps);
                assertErreur(reponse, 409, "COURSE_TERMINEE", "Conflit", TERMINEE_DETAIL);
                assertThat(reponse.body()).doesNotContain("Backyard des Crêtes");
            }
        }
        assertThat(affectationsDe(x)).containsExactly(idLeo);
    }

    @Test
    @DisplayName("CA9 : PUT et GET sur l'UUID nul ou « inexistant » donnent 404 COURSE_INTROUVABLE (corps valide ou [Alice]), aucune ligne créée ; sans benevoleIds sur Course inconnue : 400")
    void ca9_course_introuvable_et_ordre_des_controles() throws Exception {
        for (String cible : List.of(ID_INCONNU, "inexistant", UUID.randomUUID().toString())) {
            for (String corps : List.of(ids(idLeo), ids(idAlice), ids())) {
                HttpResponse<String> reponse = affecter(patron, cible, corps);
                assertErreur(reponse, 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
                JsonNode probleme = json.readTree(reponse.body());
                assertThat(probleme.get("title").asString() + probleme.get("detail").asString())
                        .doesNotContain("inexistant").doesNotContain(cible);
            }
            assertErreur(lireFiche(patron, cible), 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
            assertErreur(lireFiche(nadia, cible), 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
            JsonNode erreurs = verifierValidation(affecterBrut(patron, cible, "{}"));
            assertThat(erreurs.get(0).get("code").asString()).isEqualTo("BENEVOLES_REQUIS");
            assertErreur(affecterBrut(patron, cible, "ceci n'est pas du json"), 400, "CORPS_ILLISIBLE",
                    "Requête invalide", "Le corps de la requête est illisible.");
        }
        assertThat(jdbc.queryForObject("select count(*) from affectation_benevole", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : anonyme 401, Alice et Léo (affecté) 403 ACCES_REFUSE sur GET et PUT (corps valide, vide, id inconnu) ; ensemble inchangé")
    void ca10_roles_insuffisants() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (String cible : List.of(x, ID_INCONNU)) {
            assertErreur(api.requete("GET", CHEMIN + "/" + cible, Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                    "Authentification requise", "Vous devez être connecté.");
            for (Session session : List.of(alice, leo)) {
                assertErreur(lireFicheSession(session, cible), 403, "ACCES_REFUSE", "Accès refusé",
                        ACCES_REFUSE_DETAIL);
            }
            for (String corps : List.of(ids(idMarc), "{}", "ceci n'est pas du json")) {
                assertErreur(api.requete("PUT", CHEMIN + "/" + cible + "/benevoles", anonyme, "application/json",
                        corps), 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
                for (Session session : List.of(alice, leo)) {
                    assertErreur(affecterBrut(session, cible, corps), 403, "ACCES_REFUSE", "Accès refusé",
                            ACCES_REFUSE_DETAIL);
                }
            }
        }
        // Un bénévole ne peut pas s'affecter lui-même.
        assertErreur(affecter(leo, x, ids(idLeo, idMarc)), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertThat(affectationsDe(x)).containsExactly(idLeo);
    }

    @Test
    @DisplayName("CA10 : sans X-XSRF-TOKEN ou avec un jeton différent, 403 CSRF_INVALIDE pour tous, prioritaire sur 401, 403, 404, 400 ; ensemble inchangé")
    void ca10_csrf_prioritaire() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);

        for (Session session : List.of(alice, leo, nadia, patron)) {
            for (String cible : List.of(x, ID_INCONNU, "inexistant")) {
                Map<String, String> cookie = Map.of("Cookie",
                        "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf());
                assertCsrf(api.requete("PUT", CHEMIN + "/" + cible + "/benevoles", cookie, "application/json",
                        ids(idMarc)));
                assertCsrf(api.requete("PUT", CHEMIN + "/" + cible + "/benevoles", Map.of("Cookie",
                        "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf(),
                        "X-XSRF-TOKEN", "autre-valeur"), "application/json", "{}"));
            }
        }
        assertCsrf(api.requete("PUT", CHEMIN + "/" + x + "/benevoles", Map.of(), "application/json", ids(idMarc)));
        assertThat(affectationsDe(x)).containsExactly(idLeo);
    }

    @Test
    @DisplayName("CA10 (mis à jour par 2.5 RG10 : DELETE /X est un endpoint, 204/403/404/409) : PATCH /X et DELETE /X/benevoles par Patron donnent 404 ou 405, jamais 2xx ; la Course et ses affectations subsistent")
    void ca10_autres_methodes() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);

        List<String[]> appels = List.of(new String[] {"PATCH", CHEMIN + "/" + x},
                new String[] {"DELETE", CHEMIN + "/" + x + "/benevoles"},
                new String[] {"POST", CHEMIN + "/" + x + "/benevoles"},
                new String[] {"GET", CHEMIN + "/" + x + "/benevoles"},
                new String[] {"PATCH", CHEMIN + "/" + x + "/benevoles"});
        for (String[] appel : appels) {
            boolean avecCorps = "PATCH".equals(appel[0]) || "POST".equals(appel[0]);
            HttpResponse<String> reponse = api.requete(appel[0], appel[1], patron.entetes(),
                    avecCorps ? "application/json" : null, avecCorps ? ids(idMarc) : null);
            assertThat(reponse.statusCode()).as(appel[0] + " " + appel[1]).isIn(404, 405);
        }
        assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(1);
        assertThat(affectationsDe(x)).containsExactly(idLeo);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : Léo voit ses Courses (dont la TERMINEE) triées, cinq champs exactement, logoUrl non nul pour X, ni Z ni paramètres ; Marc voit Y seule ; un bénévole sans affectation voit []")
    void ca11_courses_du_benevole() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String y = creerCourse(patron, "Alpha", "2026-11-14");
        String z = creerCourse(patron, "Zeta personne", "2026-11-20");
        String t = creerCourse(patron, "Terminée", "2026-10-20");
        envoyerLogo(patron, x);
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, y, ids(idLeo, idMarc)).statusCode()).isEqualTo(200);
        assertThat(affecter(patron, t, ids(idLeo)).statusCode()).isEqualTo(200);
        changerStatut(t, "TERMINEE");
        insererCompte("Zoé", "BENEVOLE");
        Session zoe = api.ouvrir("Zoé", MOT_DE_PASSE);
        Session marc = api.ouvrir("Marc", MOT_DE_PASSE);

        HttpResponse<String> reponse = lireMesCourses(leo);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json"));
        List<JsonNode> courses = elements(reponse);
        // Date décroissante, puis nom croissant insensible à la casse : Alpha et Backyard le 14/11, puis Terminée.
        assertThat(courses).extracting(c -> c.get("id").asString()).containsExactly(y, x, t);
        courses.forEach(c -> assertThat(c.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COURSE_BENEVOLE));
        assertThat(courses.get(1).get("logoUrl").isNull()).isFalse();
        assertThat(courses.get(0).get("logoUrl").isNull()).isTrue();
        assertThat(courses.get(2).get("statut").asString()).isEqualTo("TERMINEE");
        assertThat(courses.get(0).get("statut").asString()).isEqualTo("EN_PREPARATION");
        assertThat(courses.get(1).get("date").asString()).isEqualTo("2026-11-14");
        assertThat(reponse.body()).doesNotContain(z).doesNotContain("distanceBoucleMetres")
                .doesNotContain("benevoleIds").doesNotContain(idMarc.toString());
        assertThat(elements(lireMesCourses(marc))).extracting(c -> c.get("id").asString()).containsExactly(y);
        assertThat(elements(lireMesCourses(zoe))).isEmpty();
    }

    @Test
    @DisplayName("CA11 : quand Patron retire Léo de X, le même Léo, sans reconnexion, ne voit plus X à l'appel suivant ; l'ajout est aussi immédiat")
    void ca11_effet_immediat_sans_reconnexion() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(elements(lireMesCourses(leo))).isEmpty();
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);
        assertThat(elements(lireMesCourses(leo))).extracting(c -> c.get("id").asString()).containsExactly(x);

        assertThat(affecter(patron, x, ids(idMarc)).statusCode()).isEqualTo(200);

        assertThat(elements(lireMesCourses(leo))).isEmpty();
    }

    @Test
    @DisplayName("CA11 : Patron, Nadia et Alice reçoivent 403 ACCES_REFUSE sur /api/benevole/courses, l'anonyme 401 ; POST, PUT, DELETE par Léo (CSRF) : 404 ou 405")
    void ca11_acces_et_methodes() throws Exception {
        for (Session session : List.of(patron, nadia, alice)) {
            assertErreur(lireMesCourses(session), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        }
        assertErreur(api.requete("GET", CHEMIN_BENEVOLE, Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
        for (String methode : List.of("POST", "PUT", "DELETE", "PATCH")) {
            boolean avecCorps = !"DELETE".equals(methode);
            HttpResponse<String> reponse = api.requete(methode, CHEMIN_BENEVOLE, leo.entetes(),
                    avecCorps ? "application/json" : null, avecCorps ? "{}" : null);
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        // Aucun paramètre ne permet de lire les Courses d'un autre bénévole.
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idMarc)).statusCode()).isEqualTo(200);
        HttpResponse<String> avecParametre = api.requete("GET", CHEMIN_BENEVOLE + "?benevoleId=" + idMarc,
                leo.enteteLecture(), null, null);
        assertThat(elements(avecParametre)).isEmpty();
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : PUT /X (2.2) avec benevoleIds, PUT de logo et POST avec benevoleIds laissent les affectations de X inchangées ; la nouvelle Course a []")
    void ca12_les_autres_endpoints_ignorent_les_affectations() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        assertThat(affecter(patron, x, ids(idLeo)).statusCode()).isEqualTo(200);

        ObjectNode modification = corpsCourse("Backyard des Alpes", "2026-12-05");
        modification.putArray("benevoleIds").add("autre");
        HttpResponse<String> modifiee = api.requete("PUT", CHEMIN + "/" + x, patron.entetes(), "application/json",
                modification.toString());
        assertThat(modifiee.statusCode()).isEqualTo(200);
        assertThat(affectationsDe(x)).containsExactly(idLeo);
        ObjectNode avecIds = corpsCourse("Backyard des Alpes", "2026-12-05");
        avecIds.putArray("benevoleIds").add(idMarc.toString());
        assertThat(api.requete("PUT", CHEMIN + "/" + x, patron.entetes(), "application/json", avecIds.toString())
                .statusCode()).isEqualTo(200);
        assertThat(affectationsDe(x)).containsExactly(idLeo);

        HttpResponse<byte[]> logo = envoyerLogo(patron, x);
        assertThat(logo.statusCode()).isEqualTo(200);
        assertThat(affectationsDe(x)).containsExactly(idLeo);

        ObjectNode creation = corpsCourse("Nouvelle course", "2027-01-01");
        creation.putArray("benevoleIds").add(idMarc.toString());
        HttpResponse<String> creee = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                creation.toString());
        assertThat(creee.statusCode()).isEqualTo(201);
        String idNouvelle = json.readTree(creee.body()).get("id").asString();
        assertThat(affectationsDe(idNouvelle)).isEmpty();
        assertThat(json.readTree(lireFiche(patron, idNouvelle).body()).get("benevoleIds")).isEmpty();
        assertThat(affectationsDe(x)).containsExactly(idLeo);
    }

    @Test
    @DisplayName("CA12 : un PUT .../benevoles ne change aucune colonne de la ligne course ni le logoUrl ; les réponses de POST, GET liste, PUT /{id} et logo restent à dix champs")
    void ca12_contrat_a_dix_champs_et_ligne_course_intacte() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        HttpResponse<byte[]> logo = envoyerLogo(patron, x);
        assertThat(json.readTree(new String(logo.body(), StandardCharsets.UTF_8)).propertyNames())
                .containsExactlyInAnyOrderElementsOf(CHAMPS_COURSE);
        JsonNode avant = json.readTree(lireFiche(patron, x).body());
        String logoAvant = avant.get("logoUrl").asString();
        Map<String, Object> ligneAvant = jdbc.queryForMap("select * from course where id = ?", UUID.fromString(x));

        HttpResponse<String> reponse = affecter(patron, x, ids(idLeo, idMarc));

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body()).get("logoUrl").asString()).isEqualTo(logoAvant);
        assertThat(jdbc.queryForMap("select * from course where id = ?", UUID.fromString(x))).isEqualTo(ligneAvant);
        assertThat(jdbc.queryForObject("select count(*) from logo_course where course_id = ?", Integer.class,
                UUID.fromString(x))).isEqualTo(1);

        HttpResponse<String> creee = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                corpsCourse("Autre", "2027-01-01").toString());
        assertThat(json.readTree(creee.body()).propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COURSE);
        HttpResponse<String> modifiee = api.requete("PUT", CHEMIN + "/" + x, patron.entetes(), "application/json",
                corpsCourse("Backyard des Alpes", "2026-12-05").toString());
        assertThat(json.readTree(modifiee.body()).propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COURSE);
        HttpResponse<String> liste = api.requete("GET", CHEMIN, patron.enteteLecture(), null, null);
        elements(liste).forEach(c -> assertThat(c.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COURSE));
        // Une Course à deux bénévoles n'apparaît qu'une fois dans la liste d'administration.
        assertThat(elements(liste)).extracting(c -> c.get("id").asString()).doesNotHaveDuplicates().hasSize(2);
        assertThat(affectationsDe(x)).containsExactlyInAnyOrder(idLeo, idMarc);
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : une ligne INFO « Bénévoles de la course affectés » avec l'id de X et 2 ; aucune ligne INFO ou supérieure pour 400, 404 et 409 ; ni nom de Course, ni pseudo, ni UUID de bénévole dans le journal")
    void ca14_journal() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes", "2026-11-14");
        String terminee = creerCourse(patron, "Course finie", "2026-11-20");
        changerStatut(terminee, "TERMINEE");
        demarrerJournal();

        assertThat(affecter(patron, x, ids(idLeo, idMarc)).statusCode()).isEqualTo(200);
        int apresSucces = journal.list.size();
        assertThat(affecter(patron, x, ids(idAlice)).statusCode()).isEqualTo(400);
        assertThat(affecter(patron, ID_INCONNU, ids(idLeo)).statusCode()).isEqualTo(404);
        assertThat(affecter(patron, terminee, ids(idLeo)).statusCode()).isEqualTo(409);

        List<ILoggingEvent> affectations = journal.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Bénévoles de la course affectés")).toList();
        assertThat(affectations).hasSize(1);
        assertThat(affectations.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(affectations.get(0).getFormattedMessage()).contains(x).contains("2");
        assertThat(journal.list.subList(apresSucces, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        assertThat(journal.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(
                "Backyard des Crêtes", "Léo", "Marc", idLeo.toString(), idMarc.toString()));
        List<String> messagesDExceptions = journal.list.stream()
                .filter(e -> e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null)
                .map(e -> e.getThrowableProxy().getMessage()).toList();
        assertThat(messagesDExceptions).noneMatch(m -> m.contains(idLeo.toString()) || m.contains(idMarc.toString())
                || m.contains("Backyard des Crêtes"));
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : deux PUT simultanés [Léo] et [Marc] répondent 200 (jamais 500) et l'ensemble final est exactement {Léo} ou {Marc}")
    void ca15_ecritures_concurrentes() throws Exception {
        for (int tour = 0; tour < 5; tour++) {
            String x = creerCourse(patron, "Concurrence " + tour, "2026-11-14");
            CountDownLatch depart = new CountDownLatch(1);
            ExecutorService executeur = Executors.newFixedThreadPool(2);
            try {
                Callable<HttpResponse<String>> pourLeo = () -> {
                    depart.await();
                    return affecter(patron, x, ids(idLeo));
                };
                Callable<HttpResponse<String>> pourMarc = () -> {
                    depart.await();
                    return affecter(nadia, x, ids(idMarc));
                };
                Future<HttpResponse<String>> a = executeur.submit(pourLeo);
                Future<HttpResponse<String>> b = executeur.submit(pourMarc);
                depart.countDown();

                assertThat(a.get().statusCode()).isEqualTo(200);
                assertThat(b.get().statusCode()).isEqualTo(200);
            } finally {
                executeur.shutdownNow();
            }
            List<UUID> finale = affectationsDe(x);
            assertThat(finale).hasSize(1);
            assertThat(finale.get(0)).isIn(idLeo, idMarc);
            assertThat(textes(json.readTree(lireFiche(patron, x).body()).get("benevoleIds")))
                    .containsExactly(finale.get(0).toString());
        }
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : /api/sante et /api/csrf restent publics ; /api/administration/** garde ses règles de rôle ; /api/benevole/** est réservé aux bénévoles ; changesets 0002 à 0006 et tables métier à jour")
    void ca16_non_regression() throws Exception {
        // /api/sante est servi par Caddy : côté API il est seulement non protégé (jamais 401 ni 403).
        assertThat(api.requete("GET", "/api/sante", Map.of(), null, null).statusCode()).isNotIn(401, 403);
        assertThat(api.requete("GET", "/api/csrf", Map.of(), null, null).statusCode()).isIn(200, 204);
        assertThat(api.requete("GET", "/api/administration/acces", patron.enteteLecture(), null, null).statusCode())
                .isEqualTo(204);
        assertThat(api.requete("GET", "/api/administration/acces", nadia.enteteLecture(), null, null).statusCode())
                .isEqualTo(204);
        assertErreur(api.requete("GET", "/api/administration/acces", leo.enteteLecture(), null, null), 403,
                "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertThat(api.requete("GET", "/api/administration/admins", patron.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
        assertErreur(api.requete("GET", "/api/administration/admins", nadia.enteteLecture(), null, null), 403,
                "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertThat(api.requete("GET", "/api/administration/benevoles", nadia.enteteLecture(), null, null)
                .statusCode()).isEqualTo(200);
        assertThat(api.requete("GET", CHEMIN, nadia.enteteLecture(), null, null).statusCode()).isEqualTo(200);
        assertThat(lireMesCourses(leo).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole", "0007-inscription",
                "0008-demarrage-course");
        assertThat(jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public'",
                String.class)).containsExactlyInAnyOrder("databasechangelog", "databasechangeloglock", "compte",
                "course", "logo_course", "affectation_benevole", "inscription");
    }

    // ---------------------------------------------------------------- utilitaires

    private void demarrerJournal() {
        journal = new ListAppender<>();
        journal.list = new java.util.concurrent.CopyOnWriteArrayList<>(); // liste sûre face aux threads qui journalisent
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

    private HttpResponse<byte[]> envoyerLogo(Session session, String idCourse) throws Exception {
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
        HttpResponse<byte[]> reponse = client.send(requete.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(reponse.statusCode()).isEqualTo(200);
        return reponse;
    }

    private String ids(UUID... identifiants) {
        return idsListe(List.of(identifiants));
    }

    private String idsListe(List<UUID> identifiants) {
        ArrayNode tableau = json.createArrayNode();
        identifiants.forEach(i -> tableau.add(i.toString()));
        ObjectNode corps = json.createObjectNode();
        corps.set("benevoleIds", tableau);
        return corps.toString();
    }

    private List<String> textes(JsonNode tableau) {
        List<String> valeurs = new ArrayList<>();
        tableau.forEach(n -> valeurs.add(n.asString()));
        return valeurs;
    }

    private List<UUID> affectationsDe(String idCourse) {
        return jdbc.queryForList("select benevole_id from affectation_benevole where course_id = ?", UUID.class,
                UUID.fromString(idCourse));
    }

    private List<JsonNode> elements(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(200);
        List<JsonNode> resultat = new ArrayList<>();
        json.readTree(reponse.body()).forEach(resultat::add);
        return resultat;
    }

    private HttpResponse<String> affecter(Session session, String idCourse, String corps) throws Exception {
        return affecterBrut(session, idCourse, corps);
    }

    private HttpResponse<String> affecterBrut(Session session, String idCourse, String corps) throws Exception {
        return api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles", session.entetes(), "application/json",
                corps);
    }

    private HttpResponse<String> lireFiche(Session session, String idCourse) throws Exception {
        return lireFicheSession(session, idCourse);
    }

    private HttpResponse<String> lireFicheSession(Session session, String idCourse) throws Exception {
        return api.requete("GET", CHEMIN + "/" + idCourse, session.enteteLecture(), null, null);
    }

    private HttpResponse<String> lireMesCourses(Session session) throws Exception {
        return api.requete("GET", CHEMIN_BENEVOLE, session.enteteLecture(), null, null);
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
