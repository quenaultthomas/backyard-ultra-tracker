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
import java.util.stream.Stream;
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
 * Contrat de l'incrément 4.2 : GET /api/administration/courses/{id}/pilotage (CA6 à CA12). Le contrat vient de la
 * spec. La Course X (Backyard des Crêtes, boucles de 1 min, 5 boucles max) est démarrée par l'API à
 * 14:03:27.654Z, soit t0 = 14:03:27Z après troncature ; l'horloge est ensuite déplacée. Le schéma (CA12) et les
 * règles ArchUnit sont vérifiés ici pour les changesets et par les tests d'architecture pour les règles.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class PilotageCourseIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final String NOM_X = "Backyard des Crêtes";
    static final Instant DEMARRAGE = Instant.parse("2026-10-09T14:03:27.654Z");
    static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    static final String JOUR = "2026-10-09";
    static final String ANONYME = "Coureur anonyme";
    static final List<String> CHAMPS_4_2 = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur",
            "demarreeLe", "dureeBoucleMinutes", "nombreMaxBoucles", "boucleCourante", "derniereBoucle",
            "boucleMaxTerminee", "debutBoucleCourante", "finBoucleCourante", "tempsAvantFinBoucleMillisecondes",
            "dureeEcouleeMillisecondes", "nombreInscrits", "inscritsEnCourse");
    /** 4.7 RG9 (point ouvert 7) : les 17 champs d'origine plus les 2 champs de bouclage, contrat additif. */
    static final List<String> CHAMPS = Stream.concat(CHAMPS_4_2.stream(),
            Stream.of("nombreAyantBoucle", "nombreEnBoucle", "nombreAbandons", "inscritsEnAbandon", "nombreVainqueurs", "inscritsVainqueurs")).toList();
    static final List<String> CHAMPS_CHRONOMETRAGE = List.of("demarreeLe", "boucleCourante", "debutBoucleCourante",
            "finBoucleCourante", "tempsAvantFinBoucleMillisecondes", "dureeEcouleeMillisecondes");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles) "
            + "values (?, ?, ?, ?, 400, 1, 5, 10, 5)";

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
    Session alice;
    Session bruno;
    Session chloe;
    Session leo;
    UUID idAlice;
    UUID idBruno;
    UUID idChloe;
    UUID idLeo;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(DEMARRAGE);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        insererCompte("Nadia", "ADMIN");
        idAlice = insererCompte("Alice", "COUREUR");
        idBruno = insererCompte("Bruno", "COUREUR");
        idChloe = insererCompte("Chloé", "COUREUR");
        idLeo = insererCompte("Léo", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        chloe = api.ouvrir("Chloé", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    /** X démarrée par l'API (t0 = 14:03:27Z), Bruno dossard 1 et Chloé dossard 2, Léo affecté. */
    private String courseXDemarree() throws Exception {
        String x = creerCourse(NOM_X, 1, 5);
        assertThat(affecter(x, idLeo).statusCode()).isEqualTo(200);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, x).statusCode()).isEqualTo(201);
        horloge.fixer(DEMARRAGE);
        assertThat(api.requete("POST", CHEMIN + "/" + x + "/demarrage", patron.entetes(), null, null).statusCode())
                .isEqualTo(200);
        return x;
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : Patron lit le pilotage de X à t0 + 60 s : 200, no-store, 19 champs exacts (17 d'origine + 2 de 4.7), valeurs de la spec, inscrits Bruno 1 et Chloé 2 sans jeton, aucune ligne modifiée")
    void ca6_instantane_nominal() throws Exception {
        String x = courseXDemarree();
        horloge.fixer(T0.plusMillis(60_000));
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");

        HttpResponse<String> reponse = pilotage(patron, x);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json").doesNotContain("problem"));
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("courseId").asString()).isEqualTo(x);
        assertThat(corps.get("nom").asString()).isEqualTo(NOM_X);
        assertThat(corps.get("logoUrl").isNull()).isTrue();
        assertThat(corps.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(corps.get("heureServeur").asString()).isEqualTo("2026-10-09T14:04:27.000Z");
        assertThat(corps.get("demarreeLe").asString()).isEqualTo("2026-10-09T14:03:27Z");
        assertThat(corps.get("dureeBoucleMinutes").asInt()).isEqualTo(1);
        assertThat(corps.get("nombreMaxBoucles").asInt()).isEqualTo(5);
        assertThat(corps.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(corps.get("derniereBoucle").asBoolean()).isFalse();
        assertThat(corps.get("boucleMaxTerminee").asBoolean()).isFalse();
        assertThat(corps.get("debutBoucleCourante").asString()).isEqualTo("2026-10-09T14:04:27Z");
        assertThat(corps.get("finBoucleCourante").asString()).isEqualTo("2026-10-09T14:05:27Z");
        assertThat(corps.get("tempsAvantFinBoucleMillisecondes").asLong()).isEqualTo(60_000L);
        assertThat(corps.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(60_000L);
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(2);
        JsonNode inscrits = corps.get("inscritsEnCourse");
        assertThat(inscrits).hasSize(2);
        assertThat(inscrits).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2);
        assertThat(inscrits).extracting(n -> n.get("pseudo").asString()).containsExactly("Bruno", "Chloé");
        assertThat(inscrits).extracting(n -> n.get("statut").asString()).containsOnly("EN_COURSE");
        assertThat(inscrits).allSatisfy(n -> assertThat(n.propertyNames()).containsExactlyInAnyOrder(
                "inscriptionId", "dossard", "pseudo", "statut", "nombreBouclesTerminees", "boucleCouranteTerminee",
                "nombreBouclesCorrigees"));
        assertThat(corps.get("nombreAyantBoucle").asInt()).isZero();
        assertThat(corps.get("nombreEnBoucle").asInt()).isEqualTo(2);
        assertThat(inscrits).extracting(n -> n.get("inscriptionId").asString()).containsExactly(
                idInscription(x, idBruno), idInscription(x, idChloe));
        List<String> jetons = jdbc.queryForList("select jeton_qr from inscription", String.class);
        assertThat(reponse.body()).doesNotContain(jetons).doesNotContain("jetonQr")
                .doesNotContain(idBruno.toString()).doesNotContain(idChloe.toString());

        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
    }

    @Test
    @DisplayName("CA6 : Nadia (ADMIN) obtient le même résultat que Patron ; un paramètre ?maintenant=... est ignoré")
    void ca6_admin_et_parametre_ignore() throws Exception {
        String x = courseXDemarree();
        horloge.fixer(T0.plusMillis(60_000));

        HttpResponse<String> parPatron = pilotage(patron, x);
        HttpResponse<String> parNadia = pilotage(nadia, x);
        HttpResponse<String> avecParametre = api.requete("GET",
                CHEMIN + "/" + x + "/pilotage?maintenant=2030-01-01T00:00:00Z", patron.enteteLecture(), null, null);

        assertThat(parNadia.statusCode()).isEqualTo(200);
        assertThat(json.readTree(parNadia.body())).isEqualTo(json.readTree(parPatron.body()));
        assertThat(avecParametre.statusCode()).isEqualTo(200);
        assertThat(json.readTree(avecParametre.body())).isEqualTo(json.readTree(parPatron.body()));
        assertThat(json.readTree(avecParametre.body()).get("heureServeur").asString())
                .isEqualTo("2026-10-09T14:04:27.000Z");
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : frontière de boucle : t0 + 59 999 ms Boucle 1 reste 1 ; t0 + 60 000 ms Boucle 2 reste 60000 ; t0 Boucle 1 écoulé 0 ; t0 - 5 s Boucle 1 reste 60000 écoulé 0")
    void ca7_frontieres_et_instant_anterieur() throws Exception {
        String x = courseXDemarree();

        verifier(x, T0.plusMillis(59_999), 1, 1L, 59_999L);
        verifier(x, T0.plusMillis(60_000), 2, 60_000L, 60_000L);
        verifier(x, T0.plusMillis(60_001), 2, 59_999L, 60_001L);
        verifier(x, T0, 1, 60_000L, 0L);
        verifier(x, T0.minusSeconds(5), 1, 60_000L, 0L);
    }

    @Test
    @DisplayName("CA7 : deux Courses (1 min à 14:03:27Z, 60 min à 14:03:57Z) lues au même Clock 14:04:27Z : Boucle 2 pour la première, Boucle 1 pour la seconde")
    void ca7_courses_independantes() throws Exception {
        String x = courseXDemarree();
        String lente = creerCourse("Backyard lent", 60, 24);
        assertThat(sinscrire(alice, lente).statusCode()).isEqualTo(201);
        horloge.fixer(Instant.parse("2026-10-09T14:03:57Z"));
        assertThat(api.requete("POST", CHEMIN + "/" + lente + "/demarrage", patron.entetes(), null, null)
                .statusCode()).isEqualTo(200);
        horloge.fixer(Instant.parse("2026-10-09T14:04:27Z"));

        JsonNode rapide = json.readTree(pilotage(patron, x).body());
        JsonNode longue = json.readTree(pilotage(patron, lente).body());

        assertThat(rapide.get("boucleCourante").asInt()).isEqualTo(2);
        assertThat(longue.get("boucleCourante").asInt()).isEqualTo(1);
        assertThat(longue.get("dureeBoucleMinutes").asInt()).isEqualTo(60);
        assertThat(longue.get("nombreMaxBoucles").asInt()).isEqualTo(24);
        assertThat(longue.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(30_000L);
        assertThat(longue.get("tempsAvantFinBoucleMillisecondes").asLong()).isEqualTo(3_570_000L);
        assertThat(longue.get("inscritsEnCourse")).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Alice");
        assertThat(rapide.get("inscritsEnCourse")).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Bruno", "Chloé");
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : dernière boucle à t0 + 240 s (Boucle 5, derniereBoucle, reste 60000), temps max écoulé à t0 + 300 s (reste 0) et t0 + 3600 s (jamais Boucle 6) ; X reste EN_COURS et ses Inscriptions EN_COURSE")
    void ca8_derniere_boucle_et_plafond() throws Exception {
        String x = courseXDemarree();

        horloge.fixer(T0.plusMillis(240_000));
        JsonNode derniere = json.readTree(pilotage(patron, x).body());
        assertThat(derniere.get("boucleCourante").asInt()).isEqualTo(5);
        assertThat(derniere.get("derniereBoucle").asBoolean()).isTrue();
        assertThat(derniere.get("boucleMaxTerminee").asBoolean()).isFalse();
        assertThat(derniere.get("tempsAvantFinBoucleMillisecondes").asLong()).isEqualTo(60_000L);

        horloge.fixer(T0.plusMillis(240_000).minusMillis(1));
        JsonNode avant = json.readTree(pilotage(patron, x).body());
        assertThat(avant.get("boucleCourante").asInt()).isEqualTo(4);
        assertThat(avant.get("derniereBoucle").asBoolean()).isFalse();

        horloge.fixer(T0.plusMillis(300_000));
        JsonNode plafond = json.readTree(pilotage(patron, x).body());
        assertThat(plafond.get("boucleCourante").asInt()).isEqualTo(5);
        assertThat(plafond.get("derniereBoucle").asBoolean()).isTrue();
        assertThat(plafond.get("boucleMaxTerminee").asBoolean()).isTrue();
        assertThat(plafond.get("tempsAvantFinBoucleMillisecondes").asLong()).isZero();
        assertThat(plafond.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(300_000L);

        horloge.fixer(T0.plusMillis(3_600_000));
        JsonNode tardif = json.readTree(pilotage(patron, x).body());
        assertThat(tardif.get("boucleCourante").asInt()).isEqualTo(5);
        assertThat(tardif.get("boucleMaxTerminee").asBoolean()).isTrue();
        assertThat(tardif.get("tempsAvantFinBoucleMillisecondes").asLong()).isZero();
        assertThat(tardif.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(3_600_000L);
        assertThat(tardif.get("statut").asString()).isEqualTo("EN_COURS");
        assertThat(tardif.get("inscritsEnCourse")).hasSize(2);

        assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, UUID.fromString(x)))
                .isEqualTo("EN_COURS");
        assertThat(jdbc.queryForList("select statut from inscription where course_id = ?", String.class,
                UUID.fromString(x))).hasSize(2).containsOnly("EN_COURSE");
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : Course EN_PREPARATION : 409 COURSE_NON_DEMARREE (Conflit, « La course n'est pas démarrée. »), sans fuite du nom")
    void ca9_course_non_demarree() throws Exception {
        String y = creerCourse("Secrète en préparation", 1, 5);
        assertThat(sinscrire(bruno, y).statusCode()).isEqualTo(201);

        HttpResponse<String> reponse = pilotage(patron, y);

        assertErreur(reponse, 409, "COURSE_NON_DEMARREE", "Conflit", "La course n'est pas démarrée.");
        assertThat(json.readTree(reponse.body()).get("detail").asString()).doesNotContain("Secrète")
                .doesNotContain(y);
        assertThat(reponse.body()).doesNotContain("Secrète");
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
    }

    @Test
    @DisplayName("CA9 : Course TERMINEE (SQL) : 200, chronométrage à null, booléens faux, heureServeur renseigné ; inscrits EN_COURSE renvoyés tels quels")
    void ca9_course_terminee() throws Exception {
        UUID z = insererCourse("Terminée", "TERMINEE");
        insererInscription(z, idBruno, 1, "EN_COURSE");

        JsonNode corps = verifierSansChronometrage(z, "TERMINEE");

        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(1);
        assertThat(corps.get("inscritsEnCourse")).extracting(n -> n.get("pseudo").asString()).containsExactly("Bruno");
    }

    @Test
    @DisplayName("CA9 : Course EN_COURS sans demarree_le (SQL) : 200, chronométrage à null, booléens faux, heureServeur renseigné, liste des inscrits présente")
    void ca9_course_en_cours_sans_heure_de_depart() throws Exception {
        UUID u = insererCourse("Sans heure", "EN_COURS");
        insererInscription(u, idBruno, 1, "EN_COURSE");
        insererInscription(u, idChloe, 2, "EN_COURSE");

        JsonNode corps = verifierSansChronometrage(u, "EN_COURS");

        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(2);
        assertThat(corps.get("inscritsEnCourse")).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("CA9 : id inconnu et id non UUID : 404 COURSE_INTROUVABLE (Introuvable, « La course est introuvable. »)")
    void ca9_course_introuvable() throws Exception {
        for (String id : List.of(ID_INCONNU, "inexistant")) {
            assertErreur(pilotage(patron, id), 404, "COURSE_INTROUVABLE", "Introuvable", "La course est introuvable.");
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : X avec EN_COURSE (Chloé 2 anonymisée, Bruno 1), ABANDON (3) et VAINQUEUR (4) : inscritsEnCourse = Bruno 1 puis « Coureur anonyme » 2, nombreInscrits 4, aucun jeton ni identifiant de Compte, rien d'une autre Course")
    void ca10_filtre_tri_et_anonymisation() throws Exception {
        String x = creerCourse(NOM_X, 1, 5);
        String autre = creerCourse("Autre course", 1, 5);
        UUID idX = UUID.fromString(x);
        insererInscription(idX, idChloe, 2, "EN_COURSE");
        insererInscription(idX, idBruno, 1, "EN_COURSE");
        insererInscription(idX, idAlice, 3, "ABANDON");
        insererInscription(idX, idLeo, 4, "VAINQUEUR");
        insererInscription(UUID.fromString(autre), idAlice, 1, "EN_COURSE");
        jdbc.update("update course set statut = 'EN_COURS', demarree_le = ? where id in (?, ?)", Timestamp.from(T0),
                idX, UUID.fromString(autre));
        assertThat(supprimerCompte(chloe).statusCode()).isEqualTo(204);
        horloge.fixer(T0.plusSeconds(10));

        HttpResponse<String> reponse = pilotage(patron, x);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(4);
        JsonNode inscrits = corps.get("inscritsEnCourse");
        assertThat(inscrits).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2);
        assertThat(inscrits).extracting(n -> n.get("pseudo").asString()).containsExactly("Bruno", ANONYME);
        assertThat(inscrits).extracting(n -> n.get("statut").asString()).containsOnly("EN_COURSE");
        assertThat(inscrits).extracting(n -> n.get("inscriptionId").asString()).containsExactly(
                idInscription(x, idBruno), idInscription(x, idChloe));
        List<String> jetons = jdbc.queryForList("select jeton_qr from inscription", String.class);
        assertThat(reponse.body()).doesNotContain(jetons).doesNotContain("jetonQr").doesNotContain("compteId")
                .doesNotContain("Chloé").doesNotContain("Compte inconnu");
        for (UUID compte : List.of(idAlice, idBruno, idChloe, idLeo)) {
            assertThat(reponse.body()).doesNotContain(compte.toString());
        }
        JsonNode pourAutre = json.readTree(pilotage(patron, autre).body());
        assertThat(pourAutre.get("nombreInscrits").asInt()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : anonyme 401 NON_AUTHENTIFIE ; Alice (COUREUR) et Léo (BENEVOLE affecté) 403 ACCES_REFUSE, aussi sur id inconnu, non UUID et Course non démarrée (jamais 404 ni 409)")
    void ca11_authentification_et_roles() throws Exception {
        String x = courseXDemarree();
        String preparation = creerCourse("En préparation", 1, 5);

        assertErreur(api.requete("GET", CHEMIN + "/" + x + "/pilotage", Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
        for (Session session : List.of(alice, leo)) {
            for (String cible : List.of(x, ID_INCONNU, "inexistant", preparation)) {
                assertErreur(pilotage(session, cible), 403, "ACCES_REFUSE", "Accès refusé",
                        "Vous n'avez pas les droits nécessaires.");
            }
        }
    }

    @Test
    @DisplayName("CA11 : le GET est accepté pour Patron sans en-tête X-XSRF-TOKEN (pas de CSRF en lecture)")
    void ca11_get_sans_csrf() throws Exception {
        String x = courseXDemarree();

        HttpResponse<String> reponse = api.requete("GET", CHEMIN + "/" + x + "/pilotage",
                Map.of("Cookie", "JSESSIONID=" + patron.id()), null, null);

        assertThat(reponse.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA11 : POST, PUT, DELETE sur .../pilotage : 403 CSRF_INVALIDE sans jeton, sinon 404 ou 405 ; X intacte")
    void ca11_autres_methodes() throws Exception {
        String x = courseXDemarree();
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");

        for (String methode : List.of("POST", "PUT", "DELETE")) {
            boolean corps = !"DELETE".equals(methode);
            HttpResponse<String> sansJeton = api.requete(methode, CHEMIN + "/" + x + "/pilotage",
                    patron.enteteLecture(), corps ? "application/json" : null, corps ? "{}" : null);
            assertErreur(sansJeton, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
            HttpResponse<String> avecJeton = api.requete(methode, CHEMIN + "/" + x + "/pilotage", patron.entetes(),
                    corps ? "application/json" : null, corps ? "{}" : null);
            assertThat(avecJeton.statusCode()).as(methode).isIn(404, 405);
        }
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : après des GET en 200, 403, 404 et 409 (tous les Comptes résolus), aucune ligne INFO ou plus ; ni nom de Course, ni pseudo, ni UUID de Compte au journal")
    void ca12_journal_silencieux() throws Exception {
        String x = courseXDemarree();
        String preparation = creerCourse("Préparation discrète", 1, 5);
        horloge.fixer(T0.plusSeconds(90));
        demarrerJournal();

        assertThat(pilotage(patron, x).statusCode()).isEqualTo(200);
        assertThat(pilotage(nadia, x).statusCode()).isEqualTo(200);
        assertThat(pilotage(alice, x).statusCode()).isEqualTo(403);
        assertThat(pilotage(patron, ID_INCONNU).statusCode()).isEqualTo(404);
        assertThat(pilotage(patron, preparation).statusCode()).isEqualTo(409);

        assertThat(journal.evenementsApplicatifs())
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<String> interdits = List.of(NOM_X, "Préparation discrète", "Bruno", "Chloé", "Patron", "Nadia",
                idBruno.toString(), idChloe.toString(), idAlice.toString(), idLeo.toString());
        assertThat(messagesDuJournal()).allSatisfy(m -> assertThat(m).doesNotContain(interdits));
    }

    @Test
    @DisplayName("CA12 : aucun changeset ajouté : databasechangelog se termine par 0009-passage (les changesets jusqu à 0008 inchangés)")
    void ca12_aucune_migration() {
        List<String> ids = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);

        assertThat(ids).isNotEmpty();
        assertThat(ids.get(ids.size() - 1)).isEqualTo("0010-abandon-inscription");
    }

    // ---------------------------------------------------------------- utilitaires

    private void verifier(String idCourse, Instant instant, int boucle, long reste, long ecoule) throws Exception {
        horloge.fixer(instant);
        JsonNode corps = json.readTree(pilotage(patron, idCourse).body());
        assertThat(corps.get("boucleCourante").asInt()).as(instant.toString()).isEqualTo(boucle);
        assertThat(corps.get("tempsAvantFinBoucleMillisecondes").asLong()).as(instant.toString()).isEqualTo(reste);
        assertThat(corps.get("dureeEcouleeMillisecondes").asLong()).as(instant.toString()).isEqualTo(ecoule);
    }

    private JsonNode verifierSansChronometrage(UUID id, String statut) throws Exception {
        HttpResponse<String> reponse = pilotage(patron, id.toString());
        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS);
        assertThat(corps.get("statut").asString()).isEqualTo(statut);
        for (String champ : CHAMPS_CHRONOMETRAGE) {
            assertThat(corps.get(champ).isNull()).as(champ).isTrue();
        }
        assertThat(corps.get("derniereBoucle").asBoolean()).isFalse();
        assertThat(corps.get("boucleMaxTerminee").asBoolean()).isFalse();
        assertThat(corps.get("nombreAyantBoucle").isNull()).isTrue();
        assertThat(corps.get("nombreEnBoucle").isNull()).isTrue();
        assertThat(corps.get("heureServeur").asString()).isEqualTo("2026-10-09T14:03:27.654Z");
        return corps;
    }

    private HttpResponse<String> pilotage(Session session, String idCourse) throws Exception {
        return api.requete("GET", CHEMIN + "/" + idCourse + "/pilotage", session.enteteLecture(), null, null);
    }

    private HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", "/api/coureur/courses/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    private HttpResponse<String> supprimerCompte(Session session) throws Exception {
        return api.requete("POST", "/api/comptes/moi/suppression", session.entetes(), "application/json",
                "{\"motDePasseActuel\":\"" + MOT_DE_PASSE + "\"}");
    }

    private HttpResponse<String> affecter(String idCourse, UUID benevole) throws Exception {
        return api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + benevole + "\"]}");
    }

    private String idInscription(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                UUID.fromString(idCourse), idCompte).toString();
    }

    private UUID insererCompte(String pseudo, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(MOT_DE_PASSE),
                role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private UUID insererCourse(String nom, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf(JOUR), statut);
        return id;
    }

    private void insererInscription(UUID idCourse, UUID idCompte, int dossard, String statut) {
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), idCourse, idCompte, dossard,
                UUID.randomUUID().toString(), statut);
    }

    private String creerCourse(String nom, int dureeMinutes, int maxBoucles) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", JOUR);
        corps.put("distanceBoucleMetres", 400);
        corps.put("dureeBoucleMinutes", dureeMinutes);
        corps.put("denivelePositifBoucleMetres", 5);
        corps.put("nombreMaxParticipants", 10);
        corps.put("nombreMaxBoucles", maxBoucles);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private List<String> messagesDuJournal() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent e : journal.evenements()) {
            if (!e.getLoggerName().startsWith("fr.backyard")) {
                continue; // journaux DEBUG des bibliothèques (Spring Security trace le principal), hors application
            }
            messages.add(e.getFormattedMessage());
            if (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null) {
                messages.add(e.getThrowableProxy().getMessage());
            }
        }
        return messages;
    }

    private void demarrerJournal() {
        arreterJournal();
        journal = JournalApplicatif.demarrerEnDebug();
    }

    private void arreterJournal() {
        if (journal == null) {
            return;
        }
        journal.fermer();
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
