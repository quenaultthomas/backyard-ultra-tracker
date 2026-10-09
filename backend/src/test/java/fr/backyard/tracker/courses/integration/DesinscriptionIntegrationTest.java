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
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 3.4 : DELETE /api/coureur/inscriptions/{id} (CA5 à CA11). Les états sont lus en base,
 * indépendamment de l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class DesinscriptionIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN_ADMIN = "/api/administration/courses";
    static final String CHEMIN_COURSES = "/api/coureur/courses";
    static final String CHEMIN = "/api/coureur/inscriptions";
    static final String NOM_X = "Backyard des Crêtes";
    static final String NOM_Y = "Backyard express";
    static final String NOM_P = "Backyard pleine";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHANGESETS = List.of("0002-compte", "0003-admin-master-unique", "0004-course",
            "0005-logo-course", "0006-affectation-benevole", "0007-inscription",
                "0008-demarrage-course");
    static final String INTROUVABLE_DETAIL = "L'inscription est introuvable.";
    static final String IMPOSSIBLE_DETAIL = "La course n'est plus en préparation : la désinscription est impossible.";

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

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    ListAppender<ILoggingEvent> journal;
    Session patron;
    Session nadia;
    Session alice;
    Session bruno;
    Session leo;
    Session chloe;
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
        horloge.fixer(MIDI_PARIS);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        String empreinte = encodeur.encode(MOT_DE_PASSE);
        insererCompte("Nadia", "ADMIN", empreinte);
        idAlice = insererCompte("Alice", "COUREUR", empreinte);
        idBruno = insererCompte("Bruno", "COUREUR", empreinte);
        idLeo = insererCompte("Léo", "BENEVOLE", empreinte);
        idChloe = insererCompte("Chloé", "COUREUR", empreinte);
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        chloe = api.ouvrir("Chloé", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : Alice supprime son inscription à X (204 sans corps) ; les autres lignes sont inchangées ; la liste, les courses et X sont cohérentes ; la réinscription donne un nouvel id, le dossard 4 et un nouveau jeton")
    void ca5_desinscription_nominale_et_reinscription() throws Exception {
        Scenario s = scenarioXY();
        String idAliceX = idInscription(s.x, idAlice);
        String jetonAncien = jeton(s.x, idAlice);
        List<Map<String, Object>> bruno2 = ligneBrute(s.x, idBruno);
        List<Map<String, Object>> chloe3 = ligneBrute(s.x, idChloe);
        List<Map<String, Object>> aliceY = ligneBrute(s.y, idAlice);
        List<Map<String, Object>> courseAvant = jdbc.queryForList("select * from course where id = ?",
                UUID.fromString(s.x));
        List<Map<String, Object>> affectationsAvant = jdbc.queryForList("select * from affectation_benevole");

        HttpResponse<String> reponse = api.requete("DELETE", CHEMIN + "/" + idAliceX + "?compteId=" + idBruno,
                alice.entetes(), "application/json", "{\"compteId\":\"" + idBruno + "\"}");

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        assertThat(compter(s.x, idAlice)).isZero();
        assertThat(ligneBrute(s.x, idBruno)).isEqualTo(bruno2);
        assertThat(ligneBrute(s.x, idChloe)).isEqualTo(chloe3);
        assertThat(ligneBrute(s.y, idAlice)).isEqualTo(aliceY);
        assertThat(jdbc.queryForList("select * from course where id = ?", UUID.fromString(s.x)))
                .isEqualTo(courseAvant);
        assertThat(jdbc.queryForList("select * from affectation_benevole")).isEqualTo(affectationsAvant);

        JsonNode liste = json.readTree(lister(alice).body());
        assertThat(liste).hasSize(1);
        assertThat(liste.get(0).get("courseId").asString()).isEqualTo(s.y);
        JsonNode courseX = courseOuverte(alice, s.x);
        assertThat(courseX.get("monInscription").isNull()).isTrue();

        HttpResponse<String> reinscription = sinscrire(alice, s.x);
        assertThat(reinscription.statusCode()).isEqualTo(201);
        JsonNode nouvelle = json.readTree(reinscription.body());
        assertThat(nouvelle.get("id").asString()).isNotEqualTo(idAliceX);
        assertThat(nouvelle.get("dossard").asInt()).isEqualTo(4);
        assertThat(jeton(s.x, idAlice)).isNotEqualTo(jetonAncien);
        assertThat(jdbc.queryForObject("select count(*) from inscription where jeton_qr = ?", Integer.class,
                jetonAncien)).isZero();
    }

    @Test
    @DisplayName("CA5 : le dossard d'un coureur du milieu n'est pas réattribué ; celui du plus grand l'est ; les autres ne bougent jamais")
    void ca5_dossards_trou_et_plus_grand() throws Exception {
        Scenario s = scenarioXY();

        assertThat(supprimer(bruno, idInscription(s.x, idBruno)).statusCode()).isEqualTo(204);
        assertThat(dossard(s.x, idAlice)).isEqualTo(1);
        assertThat(dossard(s.x, idChloe)).isEqualTo(3);
        Session zoe = creerCoureur("Zoe");
        assertThat(json.readTree(sinscrire(zoe, s.x).body()).get("dossard").asInt()).isEqualTo(4);

        assertThat(supprimer(zoe, idInscription(s.x, idPseudo("Zoe"))).statusCode()).isEqualTo(204);
        assertThat(supprimer(chloe, idInscription(s.x, idChloe)).statusCode()).isEqualTo(204);
        Session yan = creerCoureur("Yan");
        assertThat(json.readTree(sinscrire(yan, s.x).body()).get("dossard").asInt()).isEqualTo(2);

        jdbc.update("delete from inscription where course_id = ?", UUID.fromString(s.x));
        assertThat(json.readTree(sinscrire(bruno, s.x).body()).get("dossard").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 : plusieurs courses en parallèle : seule l'inscription visée est supprimée")
    void ca5_seule_l_inscription_visee_est_supprimee() throws Exception {
        Scenario s = scenarioXY();
        String jetonY = jeton(s.y, idAlice);

        assertThat(supprimer(alice, idInscription(s.x, idAlice)).statusCode()).isEqualTo(204);

        assertThat(compter(s.y, idAlice)).isEqualTo(1);
        assertThat(jeton(s.y, idAlice)).isEqualTo(jetonY);
        assertThat(dossard(s.y, idAlice)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from compte where id = ?", Integer.class, idAlice))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : P pleine (2/2) redevient ouverte après la désinscription d'Alice ; Chloé s'inscrit (201) puis P est de nouveau complète")
    void ca6_course_complete_redevient_ouverte() throws Exception {
        String p = creerCourse(NOM_P, "2026-11-20", 2);
        assertThat(sinscrire(alice, p).statusCode()).isEqualTo(201);
        assertThat(sinscrire(bruno, p).statusCode()).isEqualTo(201);

        assertErreur(sinscrire(chloe, p), 409, "COURSE_COMPLETE", "Conflit", "La course est complète.");
        assertThat(courseOuverte(chloe, p).get("complete").asBoolean()).isTrue();

        assertThat(supprimer(alice, idInscription(p, idAlice)).statusCode()).isEqualTo(204);

        assertThat(courseOuverte(chloe, p).get("complete").asBoolean()).isFalse();
        HttpResponse<String> inscription = sinscrire(chloe, p);
        assertThat(inscription.statusCode()).isEqualTo(201);
        assertThat(json.readTree(inscription.body()).get("dossard").asInt()).isEqualTo(3);
        Session zoe = creerCoureur("Zoe");
        assertErreur(sinscrire(zoe, p), 409, "COURSE_COMPLETE", "Conflit", "La course est complète.");
        assertThat(compterCourse(p)).isEqualTo(2);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : X EN_COURS puis TERMINEE : 409 DESINSCRIPTION_IMPOSSIBLE, ligne conservée, corps sans identifiant ni jeton ; Y reste désinscriptible")
    void ca7_course_non_en_preparation() throws Exception {
        Scenario s = scenarioXY();
        String idAliceX = idInscription(s.x, idAlice);
        String jeton = jeton(s.x, idAlice);
        List<Map<String, Object>> avant = ligneBrute(s.x, idAlice);

        for (String statut : List.of("EN_COURS", "TERMINEE")) {
            jdbc.update("update course set statut = ? where id = ?", statut, UUID.fromString(s.x));
            HttpResponse<String> reponse = supprimer(alice, idAliceX);
            assertErreur(reponse, 409, "DESINSCRIPTION_IMPOSSIBLE", "Conflit", IMPOSSIBLE_DETAIL);
            assertThat(json.readTree(reponse.body()).get("detail").asString())
                    .doesNotContain(idAliceX, jeton, s.x, NOM_X, "Alice");
            assertThat(reponse.body()).doesNotContain(jeton, s.x, NOM_X, "Alice");
            assertThat(ligneBrute(s.x, idAlice)).isEqualTo(avant);
        }

        assertThat(supprimer(alice, idInscription(s.y, idAlice)).statusCode()).isEqualTo(204);
        assertThat(compter(s.y, idAlice)).isZero();
    }

    @Test
    @DisplayName("CA7 : l'inscription d'autrui sur une course EN_COURS donne 404 (pas de révélation), pas 409")
    void ca7_ordre_des_refus() throws Exception {
        Scenario s = scenarioXY();
        jdbc.update("update course set statut = 'EN_COURS' where id = ?", UUID.fromString(s.x));

        assertErreur(supprimer(bruno, idInscription(s.x, idAlice)), 404, "INSCRIPTION_INTROUVABLE", "Introuvable",
                INTROUVABLE_DETAIL);
        assertThat(compter(s.x, idAlice)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : UUID inconnu, valeur non UUID et inscription de Bruno : trois 404 identiques, Bruno intact ; second DELETE 404 ; course supprimée : 404")
    void ca8_introuvable() throws Exception {
        Scenario s = scenarioXY();
        String idBrunoX = idInscription(s.x, idBruno);
        List<Map<String, Object>> brunoAvant = ligneBrute(s.x, idBruno);

        HttpResponse<String> inconnu = supprimer(alice, UUID.randomUUID().toString());
        HttpResponse<String> nonUuid = supprimer(alice, "pas-un-uuid");
        HttpResponse<String> autrui = supprimer(alice, idBrunoX);

        for (HttpResponse<String> reponse : List.of(inconnu, nonUuid, autrui)) {
            assertErreur(reponse, 404, "INSCRIPTION_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
            assertThat(json.readTree(reponse.body()).get("detail").asString()).doesNotContain(idBrunoX);
            assertThat(json.readTree(reponse.body()).get("title")).isEqualTo(json.readTree(inconnu.body()).get("title"));
            assertThat(json.readTree(reponse.body()).get("detail")).isEqualTo(json.readTree(inconnu.body()).get("detail"));
            assertThat(json.readTree(reponse.body()).get("code")).isEqualTo(json.readTree(inconnu.body()).get("code"));
        }
        assertThat(ligneBrute(s.x, idBruno)).isEqualTo(brunoAvant);

        String idAliceX = idInscription(s.x, idAlice);
        assertThat(supprimer(alice, idAliceX).statusCode()).isEqualTo(204);
        assertErreur(supprimer(alice, idAliceX), 404, "INSCRIPTION_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);

        String idAliceY = idInscription(s.y, idAlice);
        assertThat(api.requete("DELETE", CHEMIN_ADMIN + "/" + s.y, patron.entetes(), null, null).statusCode())
                .isEqualTo(204);
        assertErreur(supprimer(alice, idAliceY), 404, "INSCRIPTION_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : anonyme 401 ; Nadia, Patron et Léo (affecté) 403 ACCES_REFUSE ; Alice sans CSRF ou avec un jeton erroné 403 CSRF_INVALIDE ; inscription toujours là")
    void ca9_authentification_roles_et_csrf() throws Exception {
        Scenario s = scenarioXY();
        assertThat(api.requete("PUT", CHEMIN_ADMIN + "/" + s.x + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + idLeo + "\"]}").statusCode()).isEqualTo(200);
        String idAliceX = idInscription(s.x, idAlice);
        String chemin = CHEMIN + "/" + idAliceX;
        String jeton = jeton(s.x, idAlice);

        HttpResponse<String> anonyme = api.requete("DELETE", chemin, enteteAnonyme(), null, null);
        assertThat(anonyme.statusCode()).isEqualTo(401);
        assertThat(json.readTree(anonyme.body()).get("code").asString()).isEqualTo("NON_AUTHENTIFIE");
        for (Session session : List.of(nadia, patron, leo)) {
            HttpResponse<String> refus = supprimer(session, idAliceX);
            assertErreur(refus, 403, "ACCES_REFUSE", "Accès refusé", "Vous n'avez pas les droits nécessaires.");
            assertThat(refus.body()).doesNotContain(jeton);
        }
        HttpResponse<String> sansCsrf = api.requete("DELETE", chemin, alice.enteteLecture(), null, null);
        assertThat(sansCsrf.statusCode()).isEqualTo(403);
        assertThat(json.readTree(sansCsrf.body()).get("code").asString()).isEqualTo("CSRF_INVALIDE");
        HttpResponse<String> mauvais = api.requete("DELETE", chemin, Map.of(
                "Cookie", "JSESSIONID=" + alice.id() + "; XSRF-TOKEN=" + alice.xsrf(),
                "X-XSRF-TOKEN", "jeton-errone"), null, null);
        assertThat(mauvais.statusCode()).isEqualTo(403);
        assertThat(json.readTree(mauvais.body()).get("code").asString()).isEqualTo("CSRF_INVALIDE");
        assertThat(compter(s.x, idAlice)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA9 : DELETE sur la collection, GET/POST/PUT/PATCH sur /inscriptions/{id} : 404 ou 405, aucune modification ; la liste d'Alice est inchangée")
    void ca9_methodes_non_prevues() throws Exception {
        Scenario s = scenarioXY();
        String chemin = CHEMIN + "/" + idInscription(s.x, idAlice);
        List<Map<String, Object>> avant = jdbc.queryForList("select * from inscription order by id");
        String listeAvant = lister(alice).body();

        assertThat(api.requete("DELETE", CHEMIN, alice.entetes(), null, null).statusCode()).isIn(404, 405);
        assertThat(api.requete("GET", chemin, alice.enteteLecture(), null, null).statusCode()).isIn(404, 405);
        for (String methode : List.of("POST", "PUT", "PATCH")) {
            assertThat(api.requete(methode, chemin, alice.entetes(), "application/json", "{}").statusCode())
                    .as(methode).isIn(404, 405);
        }

        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(avant);
        HttpResponse<String> liste = lister(alice);
        assertThat(liste.statusCode()).isEqualTo(200);
        assertThat(liste.body()).isEqualTo(listeAvant);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : deux DELETE simultanés de la même inscription : un 204 et un 404, jamais 500")
    void ca10_deux_delete_simultanes() throws Exception {
        Scenario s = scenarioXY();
        String id = idInscription(s.x, idAlice);

        List<HttpResponse<String>> reponses = lancerEnParallele(List.of(
                () -> supprimer(alice, id), () -> supprimer(alice, id)));

        assertThat(reponses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(204, 404);
        HttpResponse<String> perdant = reponses.stream().filter(r -> r.statusCode() == 404).findFirst().orElseThrow();
        assertErreur(perdant, 404, "INSCRIPTION_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
        assertThat(compter(s.x, idAlice)).isZero();
    }

    @Test
    @DisplayName("CA10 : désinscription d'Alice et inscription de Chloé simultanées sur P pleine : DELETE 204, POST 201 ou 409, jamais plus de 2 inscrits")
    void ca10_desinscription_et_inscription_simultanees() throws Exception {
        String p = creerCourse(NOM_P, "2026-11-20", 2);
        assertThat(sinscrire(alice, p).statusCode()).isEqualTo(201);
        assertThat(sinscrire(bruno, p).statusCode()).isEqualTo(201);
        String id = idInscription(p, idAlice);

        List<HttpResponse<String>> reponses = lancerEnParallele(List.of(
                () -> supprimer(alice, id), () -> sinscrire(chloe, p)));

        assertThat(reponses.get(0).statusCode()).isEqualTo(204);
        assertThat(reponses.get(1).statusCode()).isIn(201, 409);
        if (reponses.get(1).statusCode() == 409) {
            assertThat(json.readTree(reponses.get(1).body()).get("code").asString()).isEqualTo("COURSE_COMPLETE");
        }
        assertThat(compterCourse(p)).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("CA10 : désinscription et suppression de la course simultanées : 204 ou 404 pour le coureur, jamais 500")
    void ca10_desinscription_et_suppression_de_course_simultanees() throws Exception {
        Scenario s = scenarioXY();
        String id = idInscription(s.x, idAlice);

        List<HttpResponse<String>> reponses = lancerEnParallele(List.of(
                () -> supprimer(alice, id),
                () -> api.requete("DELETE", CHEMIN_ADMIN + "/" + s.x, patron.entetes(), null, null)));

        assertThat(reponses.get(0).statusCode()).isIn(204, 404);
        if (reponses.get(0).statusCode() == 404) {
            assertThat(json.readTree(reponses.get(0).body()).get("code").asString())
                    .isEqualTo("INSCRIPTION_INTROUVABLE");
        }
        assertThat(reponses.get(1).statusCode()).isEqualTo(204);
        assertThat(compter(s.x, idAlice)).isZero();
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : une seule ligne INFO « Désinscription enregistrée (course X, dossard 1) » ; rien en INFO pour les refus ; ni pseudo, ni UUID, ni jeton, ni nom de course dans le journal")
    void ca11_journal() throws Exception {
        Scenario s = scenarioXY();
        String idAliceX = idInscription(s.x, idAlice);
        String idBrunoX = idInscription(s.x, idBruno);
        List<String> jetons = jdbc.queryForList("select jeton_qr from inscription", String.class);
        demarrerJournal();

        HttpResponse<String> succes = supprimer(alice, idAliceX);
        int apresSucces = journal.list.size();
        HttpResponse<String> introuvable = supprimer(alice, idAliceX);
        jdbc.update("update course set statut = 'EN_COURS' where id = ?", UUID.fromString(s.x));
        HttpResponse<String> conflit = supprimer(bruno, idBrunoX);
        HttpResponse<String> refus = supprimer(nadia, idBrunoX);
        HttpResponse<String> anonyme = api.requete("DELETE", CHEMIN + "/" + idBrunoX, enteteAnonyme(), null, null);

        assertThat(succes.statusCode()).isEqualTo(204);
        assertThat(introuvable.statusCode()).isEqualTo(404);
        assertThat(conflit.statusCode()).isEqualTo(409);
        assertThat(refus.statusCode()).isEqualTo(403);
        assertThat(anonyme.statusCode()).isEqualTo(401);
        assertThat(journal.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Désinscription enregistrée")).toList())
                .singleElement().satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.INFO);
                    assertThat(e.getFormattedMessage())
                            .isEqualTo("Désinscription enregistrée (course " + s.x + ", dossard 1)");
                });
        assertThat(journal.list.subList(apresSucces, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<String> interdits = new ArrayList<>(List.of("Alice", idAlice.toString(), idAliceX, NOM_X));
        interdits.addAll(jetons);
        assertThat(journal.list).filteredOn(e -> e.getLoggerName().startsWith("fr.backyard")
                        || e.getLevel().isGreaterOrEqual(Level.INFO))
                .noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(interdits.toArray(new String[0])));
        for (HttpResponse<String> reponse : List.of(introuvable, conflit, refus, anonyme)) {
            // "instance" reprend le chemin demandé par l'appelant lui-même : on contrôle les autres champs
            assertThat(json.readTree(reponse.body()).get("detail").asString())
                    .doesNotContain(interdits.toArray(new String[0]));
            assertThat(json.readTree(reponse.body()).get("title").asString())
                    .doesNotContain(interdits.toArray(new String[0]));
        }
    }

    @Test
    @DisplayName("CA11 : aucun nouveau changeset (0002 à 0008), ddl-auto=validate ; logo, /api/sante, /api/csrf publics ; administration et bénévole gardent leurs rôles")
    void ca11_aucune_migration_et_non_regression() throws Exception {
        Scenario s = scenarioXY();
        assertThat(supprimer(alice, idInscription(s.x, idAlice)).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(api.requete("GET", "/api/courses/" + s.x + "/logo", Map.of(), null, null).statusCode())
                .isNotIn(401, 403);
        assertThat(api.requete("GET", "/api/sante", Map.of(), null, null).statusCode()).isNotIn(401, 403);
        assertThat(api.requete("GET", "/api/csrf", Map.of(), null, null).statusCode()).isIn(200, 204);
        assertThat(api.requete("GET", CHEMIN_ADMIN, nadia.enteteLecture(), null, null).statusCode()).isEqualTo(200);
        assertThat(api.requete("GET", CHEMIN_ADMIN, alice.enteteLecture(), null, null).statusCode()).isEqualTo(403);
        assertThat(api.requete("GET", CHEMIN_ADMIN, Map.of(), null, null).statusCode()).isEqualTo(401);
        assertThat(api.requete("GET", "/api/benevole/courses", leo.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
        assertThat(api.requete("GET", "/api/benevole/courses", alice.enteteLecture(), null, null).statusCode())
                .isEqualTo(403);
    }

    // ---------------------------------------------------------------- utilitaires

    private record Scenario(String x, String y) {
    }

    /** X : Alice (1), Bruno (2), Chloé (3) ; Y : Alice (1). */
    private Scenario scenarioXY() throws Exception {
        String x = creerCourse(NOM_X, "2026-10-10", 3);
        String y = creerCourse(NOM_Y, "2026-11-15", 50);
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(chloe, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(alice, y).statusCode()).isEqualTo(201);
        return new Scenario(x, y);
    }

    private <T> List<T> lancerEnParallele(List<Callable<T>> taches) throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        ExecutorService executeur = Executors.newFixedThreadPool(taches.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> tache : taches) {
                futures.add(executeur.submit(() -> {
                    depart.await();
                    return tache.call();
                }));
            }
            depart.countDown();
            List<T> resultats = new ArrayList<>();
            for (Future<T> future : futures) {
                resultats.add(future.get());
            }
            assertThat(resultats).noneMatch(r -> r instanceof HttpResponse<?> h && h.statusCode() >= 500);
            return resultats;
        } finally {
            executeur.shutdownNow();
        }
    }

    private Map<String, String> enteteAnonyme() throws Exception {
        String jeton = api.jetonValide();
        return Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);
    }

    private Session creerCoureur(String pseudo) throws Exception {
        insererCompte(pseudo, "COUREUR", encodeur.encode(MOT_DE_PASSE));
        return api.ouvrir(pseudo, MOT_DE_PASSE);
    }

    private UUID idPseudo(String pseudo) {
        return jdbc.queryForObject("select id from compte where pseudo = ?", UUID.class, pseudo);
    }

    private HttpResponse<String> supprimer(Session session, String idInscription) throws Exception {
        return api.requete("DELETE", CHEMIN + "/" + idInscription, session.entetes(), null, null);
    }

    private HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", CHEMIN_COURSES + "/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    private HttpResponse<String> lister(Session session) throws Exception {
        return api.requete("GET", CHEMIN, session.enteteLecture(), null, null);
    }

    private JsonNode courseOuverte(Session session, String idCourse) throws Exception {
        HttpResponse<String> reponse = api.requete("GET", CHEMIN_COURSES, session.enteteLecture(), null, null);
        assertThat(reponse.statusCode()).isEqualTo(200);
        for (JsonNode course : json.readTree(reponse.body())) {
            if (idCourse.equals(course.get("id").asString())) {
                return course;
            }
        }
        throw new AssertionError("Course " + idCourse + " absente de la liste des courses ouvertes");
    }

    private String idInscription(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                UUID.fromString(idCourse), idCompte).toString();
    }

    private String jeton(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select jeton_qr from inscription where course_id = ? and compte_id = ?",
                String.class, UUID.fromString(idCourse), idCompte);
    }

    private int dossard(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select dossard from inscription where course_id = ? and compte_id = ?",
                Integer.class, UUID.fromString(idCourse), idCompte);
    }

    private int compter(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select count(*) from inscription where course_id = ? and compte_id = ?",
                Integer.class, UUID.fromString(idCourse), idCompte);
    }

    private int compterCourse(String idCourse) {
        return jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                UUID.fromString(idCourse));
    }

    private List<Map<String, Object>> ligneBrute(String idCourse, UUID idCompte) {
        return jdbc.queryForList("select * from inscription where course_id = ? and compte_id = ?",
                UUID.fromString(idCourse), idCompte);
    }

    private UUID insererCompte(String pseudo, String role, String empreinte) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte, role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private String creerCourse(String nom, String date, int nombreMaxParticipants) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", date);
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", nombreMaxParticipants);
        corps.put("nombreMaxBoucles", 24);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN_ADMIN, patron.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
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
