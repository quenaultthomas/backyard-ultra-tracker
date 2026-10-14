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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * Contrat de l'incrément 4.4a : POST /api/benevole/courses/{courseId}/passages (CA7 à CA14). Le contrat vient de la
 * spec. La Course X (Backyard des Crêtes, boucles de 1 min, 5 boucles max) est démarrée par l'API à
 * 14:03:27.654Z, soit t0 = 14:03:27Z après troncature ; l'horloge de l'application est ensuite déplacée.
 * Bruno (dossard 1) et Chloé (dossard 2) sont inscrits, Léo est affecté. Les jetons sont fixés en base avec des
 * majuscules (la casse est significative).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class PassageIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final String NOM_X = "Backyard des Crêtes";
    static final String NOM_W = "Backyard de la Vallée";
    static final Instant DEMARRAGE = Instant.parse("2026-10-12T14:03:27.654Z");
    static final Instant T0 = Instant.parse("2026-10-12T14:03:27Z");
    static final String JOUR = "2026-10-12";
    static final String JB = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    static final String JC = new StringBuilder(JB).reverse().toString();
    static final List<String> CHAMPS_PASSAGE = List.of("passageId", "inscriptionId", "dossard", "pseudo",
            "numeroBoucle", "scanneLe", "origine");
    /** Les 17 champs d'origine (4.2) plus les 2 champs de bouclage de 4.7 (RG9, contrat additif). */
    static final List<String> CHAMPS_PILOTAGE = List.of("courseId", "nom", "logoUrl", "statut", "heureServeur",
            "demarreeLe", "dureeBoucleMinutes", "nombreMaxBoucles", "boucleCourante", "derniereBoucle",
            "boucleMaxTerminee", "debutBoucleCourante", "finBoucleCourante", "tempsAvantFinBoucleMillisecondes",
            "dureeEcouleeMillisecondes", "nombreInscrits", "inscritsEnCourse", "nombreAyantBoucle",
            "nombreEnBoucle");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles) "
            + "values (?, ?, ?, ?, 400, 1, 5, 10, 5)";
    static final String INSERTION_PASSAGE = "insert into passage (id, inscription_id, numero_boucle, scanne_le, "
            + "origine) values (?, ?, ?, ?, ?)";

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
    Environment environnement;

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    ListAppender<ILoggingEvent> journal;
    Session patron;
    Session nadia;
    Session alice;
    Session leo;
    Session marc;
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
        insererCompte("Marc", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        marc = api.ouvrir("Marc", MOT_DE_PASSE);
        ouvrirSessionsCoureurs();
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    Session bruno;
    Session chloe;

    private void ouvrirSessionsCoureurs() throws Exception {
        bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        chloe = api.ouvrir("Chloé", MOT_DE_PASSE);
    }

    /** X démarrée par l'API (t0 = 14:03:27Z), Bruno dossard 1 (jeton JB) et Chloé dossard 2 (jeton JC), Léo affecté. */
    private String courseXDemarree() throws Exception {
        String x = creerCourse(NOM_X);
        assertThat(affecter(x, List.of(idLeo)).statusCode()).isEqualTo(200);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, x).statusCode()).isEqualTo(201);
        fixerJeton(x, idBruno, JB);
        fixerJeton(x, idChloe, JC);
        horloge.fixer(DEMARRAGE);
        demarrer(x);
        return x;
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : Léo scanne Bruno à t0 + 30 s : 201 no-store, 7 champs exacts, valeurs de la spec, une ligne passage ; second scan 409 PASSAGE_DEJA_ENREGISTRE avec extensions ; statuts inchangés")
    void ca7_scan_nominal_persiste() throws Exception {
        String x = courseXDemarree();
        horloge.fixer(T0.plusSeconds(30));
        UUID u1 = UUID.randomUUID();

        HttpResponse<String> reponse = scanner(leo, x, corps(JB, u1.toString(), null));

        assertThat(reponse.statusCode()).isEqualTo(201);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json").doesNotContain("problem"));
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_PASSAGE);
        assertThat(corps.get("passageId").asString()).isEqualTo(u1.toString());
        assertThat(corps.get("inscriptionId").asString()).isEqualTo(idInscription(x, idBruno));
        assertThat(corps.get("dossard").asInt()).isEqualTo(1);
        assertThat(corps.get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(corps.get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(corps.get("scanneLe").asString()).isEqualTo("2026-10-12T14:03:57.000Z");
        assertThat(corps.get("origine").asString()).isEqualTo("SCAN");
        assertThat(reponse.body()).doesNotContain(JB).doesNotContain(idBruno.toString());

        List<Map<String, Object>> lignes = jdbc.queryForList("select * from passage");
        assertThat(lignes).hasSize(1);
        Map<String, Object> ligne = lignes.get(0);
        assertThat(ligne.get("id")).isEqualTo(u1);
        assertThat(ligne.get("inscription_id").toString()).isEqualTo(idInscription(x, idBruno));
        assertThat(ligne.get("numero_boucle")).isEqualTo(1);
        assertThat(ligne.get("origine")).isEqualTo("SCAN");
        assertThat(ligne.get("scanne_le")).isNotNull();
        assertThat(jdbc.queryForObject("select scanne_le from passage", Timestamp.class).toInstant())
                .isEqualTo(T0.plusSeconds(30));

        HttpResponse<String> second = scanner(leo, x, corps(JB, UUID.randomUUID().toString(), null));
        assertErreur(second, 409, "PASSAGE_DEJA_ENREGISTRE", "Conflit", "Ce coureur a déjà un passage sur la boucle 1.");
        JsonNode erreur = json.readTree(second.body());
        assertThat(erreur.get("dossard").asInt()).isEqualTo(1);
        assertThat(erreur.get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(erreur.get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(second.body()).doesNotContain(JB).doesNotContain(NOM_X);
        assertThat(compterPassages()).isEqualTo(1);

        assertThat(jdbc.queryForObject("select statut from inscription where compte_id = ?", String.class, idBruno))
                .isEqualTo("EN_COURSE");
        assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class, UUID.fromString(x)))
                .isEqualTo("EN_COURS");
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : rejeu du même identifiantScan : 200 au corps identique, même après la Boucle 2 et la Course TERMINEE ; nouveau scan 409 COURSE_TERMINEE ; identifiant d'une autre inscription ou Course : 409 IDENTIFIANT_SCAN_DEJA_UTILISE sans donnée")
    void ca8_rejeu_idempotent() throws Exception {
        String x = courseXDemarree();
        String w = creerCourseEtDemarrer(NOM_W, JB.replace('A', 'W'), idAlice);
        horloge.fixer(T0.plusSeconds(30));
        UUID u1 = UUID.randomUUID();
        HttpResponse<String> creation = scanner(leo, x, corps(JB, u1.toString(), null));
        assertThat(creation.statusCode()).isEqualTo(201);

        HttpResponse<String> rejeu = scanner(leo, x, corps(JB, u1.toString(), null));
        assertThat(rejeu.statusCode()).isEqualTo(200);
        assertThat(rejeu.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(json.readTree(rejeu.body())).isEqualTo(json.readTree(creation.body()));
        assertThat(compterPassages()).isEqualTo(1);

        horloge.fixer(T0.plusSeconds(90));
        HttpResponse<String> rejeuBoucle2 = scanner(leo, x, corps(JB, u1.toString(), null));
        assertThat(rejeuBoucle2.statusCode()).isEqualTo(200);
        assertThat(json.readTree(rejeuBoucle2.body()).get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(json.readTree(rejeuBoucle2.body())).isEqualTo(json.readTree(creation.body()));

        HttpResponse<String> autreInscription = scanner(leo, x, corps(JC, u1.toString(), null));
        assertErreur(autreInscription, 409, "IDENTIFIANT_SCAN_DEJA_UTILISE", "Conflit",
                "Cet identifiant de scan correspond à un autre passage.");
        assertThat(autreInscription.body()).doesNotContain("Bruno").doesNotContain("dossard")
                .doesNotContain("numeroBoucle").doesNotContain("scanneLe");

        HttpResponse<String> autreCourse = scanner(leo, w, corps(JB.replace('A', 'W'), u1.toString(), null));
        assertErreur(autreCourse, 409, "IDENTIFIANT_SCAN_DEJA_UTILISE", "Conflit",
                "Cet identifiant de scan correspond à un autre passage.");
        assertThat(autreCourse.body()).doesNotContain("Bruno").doesNotContain("Alice");
        assertThat(compterPassages()).isEqualTo(1);

        jdbc.update("update course set statut = 'TERMINEE' where id = ?", UUID.fromString(x));
        HttpResponse<String> rejeuTermine = scanner(leo, x, corps(JB, u1.toString(), null));
        assertThat(rejeuTermine.statusCode()).isEqualTo(200);
        assertThat(json.readTree(rejeuTermine.body())).isEqualTo(json.readTree(creation.body()));
        assertErreur(scanner(leo, x, corps(JC, UUID.randomUUID().toString(), null)), 409, "COURSE_TERMINEE",
                "Conflit", "La course est terminée.");
        assertThat(compterPassages()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : Boucle calculée sur scanneLe (frontières, âge du scan), continuité RG7, dernière Boucle, temps écoulé avant déjà passé, scan antérieur au départ")
    void ca9_boucles_et_continuite() throws Exception {
        String x = courseXDemarree();
        String insBruno = idInscription(x, idBruno);

        horloge.fixer(T0.plusMillis(59_999));
        HttpResponse<String> boucle1 = scanner(leo, x, corps(JB, UUID.randomUUID().toString(), null));
        assertThat(boucle1.statusCode()).isEqualTo(201);
        assertThat(json.readTree(boucle1.body()).get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(json.readTree(boucle1.body()).get("scanneLe").asString()).isEqualTo("2026-10-12T14:04:26.999Z");

        // Fixture (RG14 de 4.6, anti-doublon) : le Passage de la Boucle 1 est reculé à t0 + 5 s pour que le scan de la
        // Boucle 2 à la frontière (t0 + 60 s) soit éloigné de plus du délai effectif (15 s à Boucle de 1 min).
        jdbc.update("update passage set scanne_le = ?", Timestamp.from(T0.plusSeconds(5)));

        horloge.fixer(T0.plusMillis(60_000));
        HttpResponse<String> manquant = scanner(leo, x, corps(JC, UUID.randomUUID().toString(), null));
        assertErreur(manquant, 409, "PASSAGE_PRECEDENT_MANQUANT", "Conflit",
                "Ce coureur n'a pas de passage sur la boucle précédente.");
        JsonNode erreur = json.readTree(manquant.body());
        assertThat(erreur.get("numeroBoucle").asInt()).isEqualTo(2);
        assertThat(erreur.get("dossard").asInt()).isEqualTo(2);
        assertThat(erreur.get("pseudo").asString()).isEqualTo("Chloé");
        assertThat(compterPassagesDe(idChloe)).isZero();

        HttpResponse<String> boucle2 = scanner(leo, x, corps(JB, UUID.randomUUID().toString(), null));
        assertThat(boucle2.statusCode()).isEqualTo(201);
        assertThat(json.readTree(boucle2.body()).get("numeroBoucle").asInt()).isEqualTo(2);
        assertThat(json.readTree(boucle2.body()).get("scanneLe").asString()).isEqualTo("2026-10-12T14:04:27.000Z");

        // Dernière Boucle autorisée : Passages 1 à 4 posés, scan de la Boucle 5 à t0 + 240 s.
        jdbc.update("delete from passage");
        for (int boucle = 1; boucle <= 4; boucle++) {
            poserPassage(insBruno, boucle);
        }
        horloge.fixer(T0.plusMillis(4 * 60_000L));
        HttpResponse<String> boucle5 = scanner(leo, x, corps(JB, UUID.randomUUID().toString(), null));
        assertThat(boucle5.statusCode()).isEqualTo(201);
        assertThat(json.readTree(boucle5.body()).get("numeroBoucle").asInt()).isEqualTo(5);

        horloge.fixer(T0.plusMillis(300_000));
        HttpResponse<String> ecoule = scanner(leo, x, corps(JB, UUID.randomUUID().toString(), null));
        assertErreur(ecoule, 409, "TEMPS_DE_COURSE_ECOULE", "Conflit", "Le temps de la dernière boucle est écoulé.");
        assertThat(json.readTree(ecoule.body()).get("dossard").asInt()).isEqualTo(1);
        assertThat(json.readTree(ecoule.body()).get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(compterPassagesDe(idBruno)).isEqualTo(5);
        horloge.fixer(T0.plusMillis(299_999));
        assertThat(scanner(leo, x, corps(JB, UUID.randomUUID().toString(), null)).statusCode()).isEqualTo(409);
        assertThat(scanner(leo, x, corps(JB, UUID.randomUUID().toString(), null)).body())
                .contains("PASSAGE_DEJA_ENREGISTRE");

        // Âge du scan : la Boucle vient de scanneLe, pas de l'heure de réception.
        jdbc.update("delete from passage");
        horloge.fixer(T0.plusMillis(60_500));
        HttpResponse<String> avecAge = scanner(leo, x, corps(JC, UUID.randomUUID().toString(), 1000L));
        assertThat(avecAge.statusCode()).isEqualTo(201);
        assertThat(json.readTree(avecAge.body()).get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(json.readTree(avecAge.body()).get("scanneLe").asString()).isEqualTo("2026-10-12T14:04:26.500Z");

        horloge.fixer(T0.plusSeconds(10));
        assertErreur(scanner(leo, x, corps(JB, UUID.randomUUID().toString(), 20_000L)), 409,
                "SCAN_ANTERIEUR_AU_DEPART", "Conflit", "Le scan est antérieur au départ de la course.");
        assertThat(compterPassagesDe(idBruno)).isZero();
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : Course EN_PREPARATION, TERMINEE, EN_COURS sans heure ; inscription en ABANDON ou VAINQUEUR ; jeton inconnu ou d'une autre Course : 404 ; ordre RG10 ; aucune ligne créée")
    void ca10_etats_refuses_et_jetons() throws Exception {
        String x = courseXDemarree();
        UUID y = insererCourse("Prépa", "EN_PREPARATION");
        UUID z = insererCourse("Terminée", "TERMINEE");
        UUID u = insererCourse("Sans heure", "EN_COURS");
        for (UUID course : List.of(y, z, u)) {
            jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", course, idLeo);
        }
        String jy = "Yy" + JB.substring(2);
        String jz = "Zz" + JB.substring(2);
        String ju = "Uu" + JB.substring(2);
        insererInscription(y, idAlice, 1, jy, "EN_COURSE");
        insererInscription(z, idAlice, 1, jz, "EN_COURSE");
        insererInscription(u, idAlice, 1, ju, "EN_COURSE");
        horloge.fixer(T0.plusSeconds(30));

        assertErreur(scanner(leo, y.toString(), corps(jy, id(), null)), 409, "COURSE_NON_DEMARREE", "Conflit",
                "La course n'est pas démarrée.");
        assertErreur(scanner(leo, z.toString(), corps(jz, id(), null)), 409, "COURSE_TERMINEE", "Conflit",
                "La course est terminée.");
        assertErreur(scanner(leo, u.toString(), corps(ju, id(), null)), 409, "HEURE_DE_DEPART_INCONNUE", "Conflit",
                "L'heure de départ de la course est inconnue : le passage ne peut pas être placé sur une boucle.");

        for (String statut : List.of("ABANDON", "VAINQUEUR")) {
            jdbc.update("update inscription set statut = ? where compte_id = ? and course_id = ?", statut, idBruno,
                    UUID.fromString(x));
            HttpResponse<String> reponse = scanner(leo, x, corps(JB, id(), null));
            assertErreur(reponse, 409, "INSCRIPTION_NON_EN_COURSE", "Conflit", "Ce coureur n'est plus en course.");
            JsonNode erreur = json.readTree(reponse.body());
            assertThat(erreur.get("dossard").asInt()).isEqualTo(1);
            assertThat(erreur.get("pseudo").asString()).isEqualTo("Bruno");
            assertThat(erreur.get("statut").asString()).isEqualTo(statut);
        }

        assertErreur(scanner(leo, x, corps("inexistant", id(), null)), 404, "JETON_INCONNU", "Introuvable",
                "Jeton inconnu pour cette course.");
        HttpResponse<String> autreCourse = scanner(leo, x, corps(jy, id(), null));
        assertErreur(autreCourse, 404, "JETON_INCONNU", "Introuvable", "Jeton inconnu pour cette course.");
        assertThat(autreCourse.body()).doesNotContain("dossard").doesNotContain("pseudo").doesNotContain("Alice");
        // RG10 : jeton inconnu avant l'état de la Course ; état de la Course avant l'état de l'inscription.
        assertErreur(scanner(leo, y.toString(), corps("inexistant", id(), null)), 404, "JETON_INCONNU", "Introuvable",
                "Jeton inconnu pour cette course.");
        jdbc.update("update inscription set statut = 'ABANDON' where course_id = ?", y);
        assertErreur(scanner(leo, y.toString(), corps(jy, id(), null)), 409, "COURSE_NON_DEMARREE", "Conflit",
                "La course n'est pas démarrée.");

        assertThat(compterPassages()).isZero();
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : anonyme 401, Alice/Nadia/Patron 403 ACCES_REFUSE, sans CSRF 403 CSRF_INVALIDE, Marc/Course inconnue/non UUID 403 BENEVOLE_NON_AFFECTE identiques, Léo retiré refusé, autres méthodes 404 ou 405")
    void ca11_securite() throws Exception {
        String x = courseXDemarree();
        horloge.fixer(T0.plusSeconds(30));
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);
        String chemin = "/api/benevole/courses/" + x + "/passages";

        assertErreur(api.requete("POST", chemin, anonyme, "application/json", corps(JB, id(), null)), 401,
                "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        for (Session session : List.of(alice, nadia, patron)) {
            HttpResponse<String> reponse = scanner(session, x, corps(JB, id(), null));
            assertErreur(reponse, 403, "ACCES_REFUSE", "Accès refusé", "Vous n'avez pas les droits nécessaires.");
            assertThat(reponse.body()).doesNotContain(JB);
        }
        assertErreur(api.requete("POST", chemin, leo.enteteLecture(), "application/json", corps(JB, id(), null)), 403,
                "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");

        HttpResponse<String> marcSurX = scanner(marc, x, corps(JB, id(), null));
        HttpResponse<String> inconnue = scanner(leo, ID_INCONNU, corps(JB, id(), null));
        HttpResponse<String> nonUuid = scanner(leo, "inexistant", corps(JB, id(), null));
        for (HttpResponse<String> reponse : List.of(marcSurX, inconnue, nonUuid)) {
            assertErreur(reponse, 403, "BENEVOLE_NON_AFFECTE", "Accès refusé", "Vous n'êtes pas affecté à cette course.");
        }
        assertThat(sansInstance(marcSurX)).isEqualTo(sansInstance(inconnue)).isEqualTo(sansInstance(nonUuid));

        for (String methode : List.of("GET", "PUT", "DELETE")) {
            boolean avecCorps = "PUT".equals(methode);
            HttpResponse<String> reponse = api.requete(methode, chemin, leo.entetes(),
                    avecCorps ? "application/json" : null, avecCorps ? corps(JB, id(), null) : null);
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        assertThat(compterPassages()).isZero();

        // Affectation relue à chaque requête : Léo retiré de X est refusé immédiatement.
        assertThat(scanner(leo, x, corps(JB, id(), null)).statusCode()).isEqualTo(201);
        assertThat(affecter(x, List.of()).statusCode()).isEqualTo(200);
        assertErreur(scanner(leo, x, corps(JC, id(), null)), 403, "BENEVOLE_NON_AFFECTE", "Accès refusé",
                "Vous n'êtes pas affecté à cette course.");
        assertThat(compterPassages()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : validation du corps (jeton, identifiantScan, âge) avec toutes les erreurs ensemble, CORPS_ILLISIBLE, jeton nettoyé accepté, casse significative, aucune ligne créée par les 400")
    void ca12_validation_du_corps() throws Exception {
        String x = courseXDemarree();
        horloge.fixer(T0.plusSeconds(30));
        String valide = UUID.randomUUID().toString();

        List<String> jetonsInvalides = new ArrayList<>(List.of("{\"identifiantScan\":\"" + valide + "\"}",
                "{\"jetonQr\":\"\",\"identifiantScan\":\"" + valide + "\"}",
                "{\"jetonQr\":\"   \",\"identifiantScan\":\"" + valide + "\"}",
                "{\"jetonQr\":\"" + "a".repeat(201) + "\",\"identifiantScan\":\"" + valide + "\"}",
                "{\"jetonQr\":123,\"identifiantScan\":\"" + valide + "\"}"));
        for (String corps : jetonsInvalides) {
            verifierErreurChamp(scanner(leo, x, corps), "jetonQr", "JETON_INVALIDE",
                    "Le jeton est obligatoire (200 caractères au plus).");
        }
        for (String identifiant : List.of("", "\"abc\"", "123", "\"" + valide.toUpperCase(Locale.ROOT) + " \"",
                "\"" + valide.replace("-", "") + "\"")) {
            String corps = "{\"jetonQr\":\"" + JB + "\"" + (identifiant.isEmpty() ? "" : ",\"identifiantScan\":" + identifiant) + "}";
            verifierErreurChamp(scanner(leo, x, corps), "identifiantScan", "IDENTIFIANT_SCAN_INVALIDE",
                    "L'identifiant de scan doit être un UUID.");
        }
        for (String age : List.of("-1", "86400001", "\"x\"", "1.5")) {
            String corps = "{\"jetonQr\":\"" + JB + "\",\"identifiantScan\":\"" + valide + "\",\"ageDuScanMillisecondes\":" + age + "}";
            verifierErreurChamp(scanner(leo, x, corps), "ageDuScanMillisecondes", "AGE_DU_SCAN_INVALIDE",
                    "L'âge du scan doit être compris entre 0 et 86400000 millisecondes.");
        }

        HttpResponse<String> toutes = scanner(leo, x, "{\"jetonQr\":\"\",\"identifiantScan\":\"abc\",\"ageDuScanMillisecondes\":-1}");
        assertThat(toutes.statusCode()).isEqualTo(400);
        assertThat(json.readTree(toutes.body()).get("erreurs")).extracting(n -> n.get("champ").asString())
                .containsExactlyInAnyOrder("jetonQr", "identifiantScan", "ageDuScanMillisecondes");

        assertErreurCode(api.requete("POST", cheminScan(x), leo.entetes(), "application/json", null), 400,
                "CORPS_ILLISIBLE");
        assertErreurCode(scanner(leo, x, "{pas du json"), 400, "CORPS_ILLISIBLE");
        assertThat(compterPassages()).isZero();

        // Bornes valides : 0 et 24 h acceptés ; jeton collé sur deux lignes avec espaces nettoyé.
        String collé = " " + JB.substring(0, 20) + "\n" + JB.substring(20) + " ";
        HttpResponse<String> nettoye = scanner(leo, x, corps(collé, valide, null));
        assertThat(nettoye.statusCode()).isEqualTo(201);
        assertThat(json.readTree(nettoye.body()).get("dossard").asInt()).isEqualTo(1);

        assertErreur(scanner(leo, x, corps(JC.toLowerCase(Locale.ROOT), id(), null)), 404, "JETON_INCONNU",
                "Introuvable", "Jeton inconnu pour cette course.");
        assertThat(JC).isNotEqualTo(JC.toLowerCase(Locale.ROOT));
        assertThat(compterPassages()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : 20 POST simultanés du même coureur avec 20 identifiants : exactement un 201 et dix-neuf 409 PASSAGE_DEJA_ENREGISTRE, aucun 500, une seule ligne")
    void ca13_concurrence() throws Exception {
        String x = courseXDemarree();
        horloge.fixer(T0.plusSeconds(30));
        int nombre = 20;
        CountDownLatch depart = new CountDownLatch(1);
        ExecutorService executeur = Executors.newFixedThreadPool(nombre);
        try {
            List<CompletableFuture<HttpResponse<String>>> futurs = new ArrayList<>();
            for (int i = 0; i < nombre; i++) {
                String corps = corps(JB, id(), null);
                futurs.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        depart.await();
                        return scanner(leo, x, corps);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }, executeur));
            }
            depart.countDown();
            List<HttpResponse<String>> reponses = new ArrayList<>();
            for (CompletableFuture<HttpResponse<String>> futur : futurs) {
                reponses.add(futur.get());
            }
            assertThat(reponses).filteredOn(r -> r.statusCode() == 201).hasSize(1);
            List<HttpResponse<String>> conflits = reponses.stream().filter(r -> r.statusCode() == 409).toList();
            assertThat(conflits).hasSize(nombre - 1);
            for (HttpResponse<String> conflit : conflits) {
                assertThat(json.readTree(conflit.body()).get("code").asString()).isEqualTo("PASSAGE_DEJA_ENREGISTRE");
            }
            assertThat(reponses).noneMatch(r -> r.statusCode() >= 500);
        } finally {
            executeur.shutdownNow();
        }
        assertThat(compterPassagesDe(idBruno)).isEqualTo(1);
        assertThat(compterPassages()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA13 : migration 0009-passage en dernier, table passage avec clé primaire, clé étrangère ON DELETE CASCADE, unicité et contraintes ck_passage_* effectives ; ddl-auto=validate ; suppression d'une Course EN_PREPARATION sans passage orphelin")
    void ca13_migration_et_contraintes() throws Exception {
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        List<String> ids = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);
        assertThat(ids).containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course", "0009-passage");

        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'passage'",
                String.class)).containsExactlyInAnyOrder("id", "inscription_id", "numero_boucle", "scanne_le", "origine");
        assertThat(jdbc.queryForList("select constraint_name from information_schema.table_constraints "
                + "where table_name = 'passage' and constraint_type in ('PRIMARY KEY', 'FOREIGN KEY', 'UNIQUE', 'CHECK') "
                + "and constraint_name not like '%not_null'", String.class))
                .contains("pk_passage", "fk_passage_inscription", "uq_passage_inscription_boucle",
                        "ck_passage_numero_boucle", "ck_passage_origine", "ck_passage_scanne_le");
        assertThat(jdbc.queryForObject("select delete_rule from information_schema.referential_constraints "
                + "where constraint_name = 'fk_passage_inscription'", String.class)).isEqualTo("CASCADE");

        UUID course = insererCourse("Prépa", "EN_PREPARATION");
        UUID inscription = insererInscription(course, idBruno, 1, JB, "EN_COURSE");
        Timestamp instant = Timestamp.from(T0);
        jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, 1, instant, "SCAN");

        assertViolation(() -> jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, 1, instant, "SCAN"),
                "uq_passage_inscription_boucle");
        assertViolation(() -> jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, 0, instant, "SCAN"),
                "ck_passage_numero_boucle");
        assertViolation(() -> jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, 2, instant, "X"),
                "ck_passage_origine");
        assertViolation(() -> jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, 2, null, "SCAN"),
                "ck_passage_scanne_le");
        assertViolation(() -> jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, 2, instant, "CORRECTION"),
                "ck_passage_scanne_le");
        assertViolation(() -> jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), UUID.randomUUID(), 3, instant, "SCAN"),
                "fk_passage_inscription");
        jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), inscription, 2, null, "CORRECTION");
        assertThat(compterPassages()).isEqualTo(2);

        HttpResponse<String> suppression = api.requete("DELETE", CHEMIN + "/" + course, patron.entetes(), null, null);
        assertThat(suppression.statusCode()).isIn(200, 204);
        assertThat(compterPassages()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inscription", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : journal silencieux (aucun INFO+, ni jeton, ni pseudo, ni nom de Course, ni UUID de Compte), aucun jeton dans les erreurs, Courses X et W indépendantes, pilotage : 17 champs d'origine + 2 de 4.7")
    void ca14_journal_independance_et_non_regression() throws Exception {
        String x = courseXDemarree();
        String jw = "Ww" + JB.substring(2);
        horloge.fixer(DEMARRAGE.plusSeconds(70));
        String w = creerCourseEtDemarrer(NOM_W, jw, idAlice);
        // X est en Boucle 2 (e = 100 s), W en Boucle 1 (e = 30 s) au même instant.
        horloge.fixer(DEMARRAGE.plusSeconds(100));
        poserPassage(idInscription(x, idBruno), 1);
        demarrerJournal();
        List<HttpResponse<String>> reponses = new ArrayList<>();

        UUID u1 = UUID.randomUUID();
        HttpResponse<String> scanX = scanner(leo, x, corps(JB, u1.toString(), null));
        reponses.add(scanX);
        assertThat(scanX.statusCode()).isEqualTo(201);
        assertThat(json.readTree(scanX.body()).get("numeroBoucle").asInt()).isEqualTo(2);
        HttpResponse<String> scanW = scanner(leo, w, corps(jw, id(), null));
        reponses.add(scanW);
        assertThat(scanW.statusCode()).isEqualTo(201);
        assertThat(json.readTree(scanW.body()).get("numeroBoucle").asInt()).isEqualTo(1);
        assertThat(json.readTree(scanW.body()).get("pseudo").asString()).isEqualTo("Alice");
        reponses.add(scanner(leo, x, corps(JB, u1.toString(), null)));
        assertThat(reponses.get(2).statusCode()).isEqualTo(200);
        reponses.add(scanner(marc, x, corps(JB, id(), null)));
        assertThat(reponses.get(3).statusCode()).isEqualTo(403);
        // Le jeton de W est inconnu sur X et inversement.
        reponses.add(scanner(leo, x, corps(jw, id(), null)));
        reponses.add(scanner(leo, w, corps(JB, id(), null)));
        assertThat(reponses.get(4).statusCode()).isEqualTo(404);
        assertThat(reponses.get(5).statusCode()).isEqualTo(404);
        reponses.add(scanner(leo, x, corps(JB, id(), null)));
        assertThat(reponses.get(6).statusCode()).isEqualTo(409);
        reponses.add(scanner(leo, x, corps(JC, id(), null)));
        assertThat(reponses.get(7).statusCode()).isEqualTo(409);
        reponses.add(scanner(leo, x, corps("", "abc", null)));
        assertThat(reponses.get(8).statusCode()).isEqualTo(400);

        assertThat(journal.list).noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<String> interdits = List.of(JB, JC, jw, "Bruno", "Chloé", "Alice", NOM_X, NOM_W, "Patron", "Nadia",
                idBruno.toString(), idChloe.toString(), idAlice.toString(), idLeo.toString());
        assertThat(messagesDuJournal()).allSatisfy(m -> assertThat(m).doesNotContain(interdits));
        for (HttpResponse<String> reponse : reponses.subList(3, reponses.size())) {
            assertThat(reponse.body()).doesNotContain(JB).doesNotContain(JC).doesNotContain(jw).doesNotContain(NOM_X)
                    .doesNotContain(NOM_W);
        }

        HttpResponse<String> pilotage = api.requete("GET", CHEMIN + "/" + x + "/pilotage", patron.enteteLecture(),
                null, null);
        assertThat(pilotage.statusCode()).isEqualTo(200);
        assertThat(json.readTree(pilotage.body()).propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_PILOTAGE);
    }

    // ---------------------------------------------------------------- utilitaires

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private String cheminScan(String idCourse) {
        return "/api/benevole/courses/" + idCourse + "/passages";
    }

    private HttpResponse<String> scanner(Session session, String idCourse, String corps) throws Exception {
        return api.requete("POST", cheminScan(idCourse), session.entetes(), "application/json", corps);
    }

    /** Corps JSON ; un champ à null est omis. */
    private String corps(String jeton, String identifiantScan, Long age) {
        ObjectNode corps = json.createObjectNode();
        if (jeton != null) {
            corps.put("jetonQr", jeton);
        }
        if (identifiantScan != null) {
            corps.put("identifiantScan", identifiantScan);
        }
        if (age != null) {
            corps.put("ageDuScanMillisecondes", age);
        }
        return corps.toString();
    }

    private void verifierErreurChamp(HttpResponse<String> reponse, String champ, String code, String message)
            throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(400);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
        JsonNode erreurs = corps.get("erreurs");
        assertThat(erreurs).as(reponse.body()).hasSize(1);
        assertThat(erreurs.get(0).get("champ").asString()).isEqualTo(champ);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo(code);
        assertThat(erreurs.get(0).get("message").asString()).isEqualTo(message);
        assertThat(reponse.body()).doesNotContain(JB);
    }

    private ObjectNode sansInstance(HttpResponse<String> reponse) throws Exception {
        ObjectNode corps = (ObjectNode) json.readTree(reponse.body());
        corps.remove("instance");
        return corps;
    }

    private void assertViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String contrainte) {
        assertThatThrownBy(action).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(contrainte);
    }

    private int compterPassages() {
        return jdbc.queryForObject("select count(*) from passage", Integer.class);
    }

    private int compterPassagesDe(UUID idCompte) {
        return jdbc.queryForObject("select count(*) from passage p join inscription i on i.id = p.inscription_id "
                + "where i.compte_id = ?", Integer.class, idCompte);
    }

    private void poserPassage(String idInscription, int boucle) {
        jdbc.update(INSERTION_PASSAGE, UUID.randomUUID(), UUID.fromString(idInscription), boucle,
                Timestamp.from(T0.plusSeconds(60L * (boucle - 1) + 5)), "SCAN");
    }

    private HttpResponse<String> demarrer(String idCourse) throws Exception {
        HttpResponse<String> reponse = api.requete("POST", CHEMIN + "/" + idCourse + "/demarrage", patron.entetes(),
                null, null);
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        return reponse;
    }

    private HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", "/api/coureur/courses/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    private HttpResponse<String> affecter(String idCourse, List<UUID> benevoles) throws Exception {
        ObjectNode corps = json.createObjectNode();
        var liste = corps.putArray("benevoleIds");
        benevoles.forEach(b -> liste.add(b.toString()));
        return api.requete("PUT", CHEMIN + "/" + idCourse + "/benevoles", patron.entetes(), "application/json",
                corps.toString());
    }

    /** Course démarrée à l'instant courant de l'horloge, Léo affecté, Alice inscrite (dossard 1) avec ce jeton. */
    private String creerCourseEtDemarrer(String nom, String jeton, UUID idCoureur) throws Exception {
        String id = creerCourse(nom);
        assertThat(affecter(id, List.of(idLeo)).statusCode()).isEqualTo(200);
        assertThat(sinscrire(alice, id).statusCode()).isEqualTo(201);
        fixerJeton(id, idCoureur, jeton);
        demarrer(id);
        return id;
    }

    private void fixerJeton(String idCourse, UUID idCompte, String jeton) {
        jdbc.update("update inscription set jeton_qr = ? where course_id = ? and compte_id = ?", jeton,
                UUID.fromString(idCourse), idCompte);
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

    private UUID insererInscription(UUID idCourse, UUID idCompte, int dossard, String jeton, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, ?)", id, idCourse, idCompte, dossard, jeton, statut);
        return id;
    }

    private String creerCourse(String nom) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", JOUR);
        corps.put("distanceBoucleMetres", 400);
        corps.put("dureeBoucleMinutes", 1);
        corps.put("denivelePositifBoucleMetres", 5);
        corps.put("nombreMaxParticipants", 10);
        corps.put("nombreMaxBoucles", 5);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN, patron.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private List<String> messagesDuJournal() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent e : journal.list) {
            if (!e.getLoggerName().startsWith("fr.backyard")) {
                continue; // journaux DEBUG des bibliothèques (Spring Security trace le principal), hors application
            }
            if (e.getFormattedMessage() != null) {
                messages.add(e.getFormattedMessage());
            }
            if (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null) {
                messages.add(e.getThrowableProxy().getMessage());
            }
        }
        return messages;
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

    private void assertErreurCode(HttpResponse<String> reponse, int statut, String code) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo(code);
    }

    private void assertErreur(HttpResponse<String> reponse, int statut, String code, String titre, String detail)
            throws Exception {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo(code);
        assertThat(corps.get("title").asString()).isEqualTo(titre);
        assertThat(corps.get("status").asInt()).isEqualTo(statut);
        assertThat(corps.get("detail").asString()).isEqualTo(detail);
    }
}
