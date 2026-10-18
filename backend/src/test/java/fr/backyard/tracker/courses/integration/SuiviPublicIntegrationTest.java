package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.OctetsDeLogo;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.exposition.SuiviCourseReponse;
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

/**
 * Contrat de l'incrément 6.1 (suivi public d'une Course) : CA6 à CA10, serveur réel. Le contrat est celui de la
 * spec : GET /api/courses/{id}/suivi, public, liste fermée de 11 champs, no-store, 404 COURSE_INTROUVABLE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuiviPublicIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final String NOM_X = "Backyard des Crêtes";
    static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    static final long HEURE = 3_600_000L;
    static final List<String> CHAMPS = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur",
            "nombreMaxBoucles", "boucleCourante", "derniereBoucle", "boucleMaxTerminee",
            "tempsAvantFinBoucleMillisecondes", "dureeEcouleeMillisecondes");
    static final List<String> INTERDITS = List.of("inscrits", "pseudo", "dossard", "jeton", "benevole", "demarreeLe",
            "distance", "denivele", "date", "nombreInscrits", "compte");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, "
            + "demarree_le) values (?, ?, ?, ?, 400, ?, 5, 100, 5, ?)";

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
    Session leoAffecte;
    Session marcNonAffecte;
    Session alice;
    UUID idAlice;
    UUID idLeo;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(T0);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        insererCompte("Nadia", "ADMIN");
        idAlice = insererCompte("Alice", "COUREUR");
        idLeo = insererCompte("Léo", "BENEVOLE");
        insererCompte("Marc", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        leoAffecte = api.ouvrir("Léo", MOT_DE_PASSE);
        marcNonAffecte = api.ouvrir("Marc", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        if (journal != null) {
            journal.fermer();
            journal = null;
        }
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : X en Boucle 2 lue sans session : 200, no-store, aucun Set-Cookie, 11 champs exactement, valeurs conformes au Clock, aucune fuite d'inscrit, aucune écriture")
    void ca6_suivi_public_sans_session() throws Exception {
        UUID x = courseXAvecInscritEtAffectation();
        horloge.fixer(T0.plusMillis(65 * 60_000L + 250));
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");

        HttpResponse<String> reponse = suivi(x.toString(), Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json").doesNotContain("problem"));
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(11).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("courseId").asString()).isEqualTo(x.toString());
        assertThat(corps.get("nom").asString()).isEqualTo(NOM_X);
        assertThat(corps.get("logoUrl").isNull()).isTrue();
        assertThat(corps.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(corps.get("heureServeur").asString()).isEqualTo("2026-10-09T15:08:27.250Z");
        assertThat(corps.get("nombreMaxBoucles").asInt()).isEqualTo(5);
        assertThat(corps.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(corps.get("derniereBoucle").asBoolean()).isFalse();
        assertThat(corps.get("boucleMaxTerminee").asBoolean()).isFalse();
        assertThat(corps.get("tempsAvantFinBoucleMillisecondes").asLong()).isEqualTo(2 * HEURE - 65 * 60_000L - 250);
        assertThat(corps.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(65 * 60_000L + 250);
        assertAucuneFuite(reponse.body());

        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
    }

    @Test
    @DisplayName("CA6 : le paramètre de requête ou l'en-tête de date du client est sans effet sur l'instant (horloge de l'application)")
    void ca6_instant_uniquement_de_l_horloge_serveur() throws Exception {
        UUID x = insererCourse(NOM_X, "EN_COURS", 60, T0);
        horloge.fixer(T0.plusSeconds(30));

        HttpResponse<String> reponse = api.requete("GET", "/api/courses/" + x + "/suivi?maintenant=2030-01-01T00:00:00Z",
                Map.of("Date", "Wed, 01 Jan 2031 00:00:00 GMT"), null, null);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("heureServeur").asString()).isEqualTo("2026-10-09T14:03:57.000Z");
        assertThat(corps.get("boucleCourante").asInt()).isEqualTo(1);
        assertThat(corps.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("CA6 : lectures en 200 et 404 : aucune ligne INFO ou plus dans le journal applicatif, ni nom, pseudo ou identifiant de Compte")
    void ca6_journal_silencieux() throws Exception {
        UUID x = courseXAvecInscritEtAffectation();
        horloge.fixer(T0.plusSeconds(90));
        journal = JournalApplicatif.demarrerEnDebug();

        assertThat(suivi(x.toString(), Map.of()).statusCode()).isEqualTo(200);
        assertThat(suivi(x.toString(), nadia.enteteLecture()).statusCode()).isEqualTo(200);
        assertThat(suivi(ID_INCONNU, Map.of()).statusCode()).isEqualTo(404);
        assertThat(suivi("abc", Map.of()).statusCode()).isEqualTo(404);

        assertThat(journal.evenementsApplicatifs())
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent e : journal.evenements()) {
            if (e.getLoggerName().startsWith("fr.backyard")) {
                messages.add(e.getFormattedMessage());
            }
        }
        assertThat(messages).allSatisfy(m -> assertThat(m).doesNotContain(NOM_X, "Alice", "Nadia", "Léo",
                idAlice.toString(), idLeo.toString()));
    }

    @Test
    @DisplayName("CA6 : toString de la réponse n'affiche que l'identifiant de la Course")
    void ca6_to_string_limite_a_l_identifiant() {
        UUID id = UUID.randomUUID();
        SuiviCourseReponse reponse = new SuiviCourseReponse(id, "Nom secret", "/api/courses/x/logo?v=1",
                StatutCourse.EN_COURS, "2026-10-09T14:03:27.000Z", 5, 2, false, false, 10L, 20L);

        assertThat(reponse.toString()).contains(id.toString()).doesNotContain("Nom secret", "logo", "14:03");
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : X lue avec session ADMIN, ADMIN_MASTER, BENEVOLE affecté, BENEVOLE non affecté et COUREUR : 200 et corps identique à l'anonyme, sans jeton CSRF")
    void ca7_meme_reponse_pour_tous_les_roles() throws Exception {
        UUID x = courseXAvecInscritEtAffectation();
        horloge.fixer(T0.plusSeconds(90));
        HttpResponse<String> anonyme = suivi(x.toString(), Map.of());
        assertThat(anonyme.statusCode()).isEqualTo(200);
        JsonNode attendu = json.readTree(anonyme.body());

        for (Session session : List.of(nadia, patron, leoAffecte, marcNonAffecte, alice)) {
            HttpResponse<String> reponse = suivi(x.toString(), session.enteteLecture());
            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(json.readTree(reponse.body())).isEqualTo(attendu);
        }
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : EN_PREPARATION, TERMINEE et EN_COURS à t0 + 5 x 60 min : 200 pour chacune, chronométrage null pour les deux premières (jamais 409), boucleMaxTerminee true et reste 0 pour la troisième")
    void ca8_etats_de_la_course() throws Exception {
        UUID preparation = insererCourse("En préparation", "EN_PREPARATION", 60, null);
        UUID terminee = insererCourse("Terminée", "TERMINEE", 60, T0);
        UUID sansDepart = insererCourse("Sans départ", "EN_COURS", 60, null);
        UUID fin = insererCourse(NOM_X, "EN_COURS", 60, T0);
        horloge.fixer(T0.plus(java.time.Duration.ofMinutes(300)));

        verifierSansChronometrage(preparation, "EN_PREPARATION");
        verifierSansChronometrage(terminee, "TERMINEE");
        verifierSansChronometrage(sansDepart, "EN_COURS");

        JsonNode corps = json.readTree(suivi(fin.toString(), Map.of()).body());
        assertThat(corps.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(corps.get("boucleMaxTerminee").asBoolean()).isTrue();
        assertThat(corps.get("derniereBoucle").asBoolean()).isTrue();
        assertThat(corps.get("boucleCourante").asInt()).isEqualTo(5);
        assertThat(corps.get("tempsAvantFinBoucleMillisecondes").asLong()).isZero();
        assertThat(corps.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(5 * HEURE);
        assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, fin))
                .isEqualTo("EN_COURS");
    }

    @Test
    @DisplayName("CA8 : Course avec logo : logoUrl commence par /api/courses/<id>/logo?v= ; sans logo : null")
    void ca8_logo_url() throws Exception {
        UUID avecLogo = insererCourse("Avec logo", "EN_COURS", 60, T0);
        UUID sansLogo = insererCourse("Sans logo", "EN_COURS", 60, T0);
        byte[] png = OctetsDeLogo.png();
        jdbc.update("insert into logo_course (course_id, contenu, type_mime, taille_octets, empreinte) "
                + "values (?, ?, 'image/png', ?, ?)", avecLogo, png, png.length, OctetsDeLogo.sha256(png));

        JsonNode avec = json.readTree(suivi(avecLogo.toString(), Map.of()).body());
        JsonNode sans = json.readTree(suivi(sansLogo.toString(), Map.of()).body());

        assertThat(avec.get("logoUrl").asString()).startsWith("/api/courses/" + avecLogo + "/logo?v=");
        assertThat(sans.get("logoUrl").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : UUID inconnu, identifiant non UUID et Course supprimée : 404 problem+json COURSE_INTROUVABLE (gestionnaire de SuiviPublicController), sans donnée d'une autre Course")
    void ca9_course_introuvable() throws Exception {
        UUID supprimee = insererCourse("Supprimée discrète", "EN_PREPARATION", 60, null);
        insererCourse("Autre course secrète", "EN_COURS", 60, T0);
        HttpResponse<String> suppression = api.requete("DELETE", "/api/administration/courses/" + supprimee,
                patron.entetes(), null, null);
        assertThat(suppression.statusCode()).isEqualTo(204);

        for (String id : List.of(ID_INCONNU, "abc", supprimee.toString())) {
            for (Map<String, String> entetes : List.of(Map.<String, String>of(), alice.enteteLecture())) {
                HttpResponse<String> reponse = suivi(id, entetes);
                assertThat(reponse.statusCode()).as(id).isEqualTo(404);
                assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                        c -> assertThat(c).contains("application/problem+json"));
                assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("COURSE_INTROUVABLE");
                assertThat(reponse.body()).doesNotContain("Autre course secrète", "Supprimée discrète");
            }
        }
    }

    @Test
    @DisplayName("CA9 : POST, PUT, DELETE et HEAD sur le chemin ne répondent jamais 2xx ; POST sans CSRF : 403 CSRF_INVALIDE")
    void ca9_autres_methodes_jamais_2xx() throws Exception {
        UUID x = insererCourse(NOM_X, "EN_COURS", 60, T0);
        String chemin = "/api/courses/" + x + "/suivi";

        HttpResponse<String> sansCsrf = api.requete("POST", chemin, Map.of(), null, null);
        assertThat(sansCsrf.statusCode()).isEqualTo(403);
        assertThat(json.readTree(sansCsrf.body()).get("code").asString()).isEqualTo("CSRF_INVALIDE");

        String xsrf = api.jetonValide();
        Map<String, String> anonymeAvecCsrf = Map.of("Cookie", "XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf);
        for (String methode : List.of("POST", "PUT", "DELETE", "PATCH")) {
            assertThat(api.requete(methode, chemin, anonymeAvecCsrf, null, null).statusCode())
                    .as(methode + " anonyme").isNotIn(java.util.stream.IntStream.rangeClosed(200, 299).boxed().toList());
            for (Session session : List.of(patron, nadia, alice)) {
                assertThat(api.requete(methode, chemin, session.entetes(), null, null).statusCode())
                        .as(methode + " connecté").isNotIn(java.util.stream.IntStream.rangeClosed(200, 299).boxed().toList());
            }
        }
        assertThat(api.requete("HEAD", chemin, Map.of(), null, null).statusCode()).as("HEAD anonyme")
                .isNotIn(java.util.stream.IntStream.rangeClosed(200, 299).boxed().toList());
        assertThat(api.requete("HEAD", chemin, nadia.enteteLecture(), null, null).statusCode()).as("HEAD connecté")
                .isNotIn(java.util.stream.IntStream.rangeClosed(200, 299).boxed().toList());
        assertThat(jdbc.queryForObject("select count(*) from course", Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : Courses de 1 min (14:03:27Z) et de 60 min (14:03:57Z) lues à 14:04:27Z : Boucle 2 et Boucle 1, indépendantes, aucune écriture en base")
    void ca10_courses_en_parallele_independantes() throws Exception {
        UUID courte = insererCourse("Courte", "EN_COURS", 1, Instant.parse("2026-10-09T14:03:27Z"));
        UUID longue = insererCourse("Longue", "EN_COURS", 60, Instant.parse("2026-10-09T14:03:57Z"));
        insererInscription(courte, idAlice, 1);
        horloge.fixer(Instant.parse("2026-10-09T14:04:27Z"));
        List<Map<String, Object>> courses = jdbc.queryForList("select * from course order by id");
        List<Map<String, Object>> inscriptions = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> passages = jdbc.queryForList("select * from passage order by id");

        JsonNode a = json.readTree(suivi(courte.toString(), Map.of()).body());
        JsonNode b = json.readTree(suivi(longue.toString(), Map.of()).body());
        JsonNode aEncore = json.readTree(suivi(courte.toString(), Map.of()).body());

        assertThat(a.get("courseId").asString()).isEqualTo(courte.toString());
        assertThat(a.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(a.get("tempsAvantFinBoucleMillisecondes").asLong()).isEqualTo(60_000L);
        assertThat(a.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(60_000L);
        assertThat(b.get("courseId").asString()).isEqualTo(longue.toString());
        assertThat(b.get("boucleCourante").asInt()).isEqualTo(1);
        assertThat(b.get("tempsAvantFinBoucleMillisecondes").asLong()).isEqualTo(HEURE - 30_000L);
        assertThat(b.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(30_000L);
        assertThat(aEncore).isEqualTo(a);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(courses);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptions);
        assertThat(jdbc.queryForList("select * from passage order by id")).isEqualTo(passages);
    }

    // ---------------------------------------------------------------- outils

    private void verifierSansChronometrage(UUID id, String statut) throws Exception {
        HttpResponse<String> reponse = suivi(id.toString(), Map.of());
        assertThat(reponse.statusCode()).as(statut).isEqualTo(200);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(11).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("courseId").asString()).isEqualTo(id.toString());
        assertThat(corps.get("statut").asString()).isEqualTo(statut);
        assertThat(corps.get("boucleCourante").isNull()).isTrue();
        assertThat(corps.get("tempsAvantFinBoucleMillisecondes").isNull()).isTrue();
        assertThat(corps.get("dureeEcouleeMillisecondes").isNull()).isTrue();
        assertThat(corps.get("derniereBoucle").asBoolean()).isFalse();
        assertThat(corps.get("boucleMaxTerminee").asBoolean()).isFalse();
        assertThat(corps.get("heureServeur").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    }

    /** X (60 min, M = 5, démarrée à t0), Alice inscrite, Léo affecté. */
    private UUID courseXAvecInscritEtAffectation() throws Exception {
        UUID x = insererCourse(NOM_X, "EN_PREPARATION", 60, null);
        assertThat(api.requete("PUT", "/api/administration/courses/" + x + "/benevoles", patron.entetes(),
                "application/json", "{\"benevoleIds\":[\"" + idLeo + "\"]}").statusCode()).isEqualTo(200);
        insererInscription(x, idAlice, 7);
        jdbc.update("update course set statut = 'EN_COURS', demarree_le = ? where id = ?", Timestamp.from(T0), x);
        return x;
    }

    private HttpResponse<String> suivi(String id, Map<String, String> entetes) throws Exception {
        return api.requete("GET", "/api/courses/" + id + "/suivi", entetes, null, null);
    }

    private void assertAucuneFuite(String corps) {
        assertThat(corps.toLowerCase(Locale.ROOT)).doesNotContain(INTERDITS.stream()
                .map(s -> "\"" + s.toLowerCase(Locale.ROOT)).toList().toArray(new String[0]));
        assertThat(corps).doesNotContain("Alice", "Léo", idAlice.toString(), idLeo.toString());
    }

    private UUID insererCompte(String pseudo, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(MOT_DE_PASSE),
                role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private UUID insererCourse(String nom, String statut, int dureeMinutes, Instant demarreeLe) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf("2026-10-09"), statut, dureeMinutes,
                demarreeLe == null ? null : Timestamp.from(demarreeLe));
        return id;
    }

    private void insererInscription(UUID idCourse, UUID idCompte, int dossard) {
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, 'EN_COURSE')", UUID.randomUUID(), idCourse, idCompte, dossard,
                UUID.randomUUID().toString());
    }
}
