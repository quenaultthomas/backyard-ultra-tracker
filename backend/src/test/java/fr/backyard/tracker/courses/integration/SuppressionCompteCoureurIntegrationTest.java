package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.application.LirePseudos;
import fr.backyard.tracker.comptes.infrastructure.CompteJpaAdapter;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 3.6 : POST /api/comptes/moi/suppression (CA4 à CA12). Jeu de référence : Alice inscrite à
 * P1, P2, C, T ; Bruno à P1 ; Chloé à P1 et C (C et T passées EN_COURS / TERMINEE par SQL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuppressionCompteCoureurIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String SUPPRESSION = "/api/comptes/moi/suppression";
    static final String CHEMIN_ADMIN = "/api/administration/courses";
    static final String CHEMIN_COURSES = "/api/coureur/courses";
    static final String ANONYME = "Coureur anonyme";
    static final Instant CREE_LE = Instant.parse("2026-09-01T08:30:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHANGESETS = List.of("0002-compte", "0003-admin-master-unique", "0004-course",
            "0005-logo-course", "0006-affectation-benevole", "0007-inscription");
    static final String NOM_P1 = "Backyard Quartz Premier";
    static final String NOM_P2 = "Backyard Quartz Second";
    static final String NOM_C = "Backyard Quartz Courant";
    static final String NOM_T = "Backyard Quartz Termine";

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
    LirePseudos lirePseudos;

    @MockitoSpyBean
    CompteJpaAdapter depotComptes;

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
    UUID idNadia;
    String p1;
    String p2;
    String c;
    String t;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(CoursesIntegrationTest.MIDI_PARIS);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        String empreinte = encodeur.encode(MOT_DE_PASSE);
        idNadia = insererCompte("Nadia", "ADMIN", empreinte);
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

        p1 = creerCourse(NOM_P1, 3);
        p2 = creerCourse(NOM_P2, 10);
        c = creerCourse(NOM_C, 10);
        t = creerCourse(NOM_T, 10);
        inscrire(p1, alice);
        inscrire(p2, alice);
        inscrire(c, alice);
        inscrire(t, alice);
        inscrire(p1, bruno);
        inscrire(p1, chloe);
        inscrire(c, chloe);
        jdbc.update("update course set statut = 'EN_COURS' where id = ?", UUID.fromString(c));
        jdbc.update("update course set statut = 'TERMINEE' where id = ?", UUID.fromString(t));
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Alice supprime son compte : 204 sans corps, compte anonymisé en base, inscriptions P1/P2 annulées, C/T conservées, autres coureurs et courses inchangés")
    void ca4_suppression_nominale() throws Exception {
        Map<String, Object> compteAvant = jdbc.queryForMap("select * from compte where id = ?", idAlice);
        List<Map<String, Object>> inscriptionsAlice = jdbc.queryForList(
                "select * from inscription where compte_id = ? and course_id in (?, ?) order by dossard", idAlice,
                UUID.fromString(c), UUID.fromString(t));
        List<String> jetonsAnnules = jdbc.queryForList(
                "select jeton_qr from inscription where compte_id = ? and course_id in (?, ?)", String.class, idAlice,
                UUID.fromString(p1), UUID.fromString(p2));
        List<Map<String, Object>> autresAvant = jdbc.queryForList(
                "select * from inscription where compte_id <> ? order by id", idAlice);
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");
        assertThat(jetonsAnnules).hasSize(2);
        assertThat(inscriptionsAlice).hasSize(2);

        HttpResponse<String> reponse = supprimer(alice, MOT_DE_PASSE);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        Map<String, Object> apres = jdbc.queryForMap("select * from compte where id = ?", idAlice);
        assertThat(apres.get("id")).isEqualTo(idAlice);
        assertThat(apres.get("pseudo")).isEqualTo(ANONYME);
        String cle = (String) apres.get("pseudo_normalise");
        assertThat(cle).startsWith("#").hasSize(30).isNotEqualTo("alice");
        assertThat(cle.substring(1)).isEqualTo(idAlice.toString().replace("-", "").substring(0, 29));
        assertThat(apres.get("empreinte_mot_de_passe")).isNull();
        assertThat(apres.get("role")).isEqualTo("COUREUR");
        assertThat(apres.get("cree_le")).isEqualTo(compteAvant.get("cree_le"));
        assertThat(jdbc.queryForObject("select count(*) from compte where pseudo = 'Alice' or pseudo_normalise = "
                + "'alice'", Integer.class)).isZero();

        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ? and course_id in (?, ?)",
                Integer.class, idAlice, UUID.fromString(p1), UUID.fromString(p2))).isZero();
        for (String jeton : jetonsAnnules) {
            assertThat(jdbc.queryForObject("select count(*) from inscription where jeton_qr = ?", Integer.class,
                    jeton)).isZero();
        }
        assertThat(jdbc.queryForList("select * from inscription where compte_id = ? order by dossard", idAlice))
                .isEqualTo(inscriptionsAlice);
        assertThat(jdbc.queryForList("select * from inscription where compte_id <> ? order by id", idAlice))
                .isEqualTo(autresAvant);
        assertThat(jdbc.queryForList("select dossard from inscription where compte_id = ? and course_id = ?",
                Integer.class, idBruno, UUID.fromString(p1))).containsExactly(2);
        assertThat(jdbc.queryForList("select dossard from inscription where compte_id = ? and course_id = ?",
                Integer.class, idChloe, UUID.fromString(p1))).containsExactly(3);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);

        JsonNode liste = json.readTree(listerInscrits(patron, p1).body());
        assertThat(liste.get("nombreInscrits").asInt()).isEqualTo(2);
        assertThat(liste.get("complete").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : après suppression, connexion 401 IDENTIFIANTS_INVALIDES identique à un pseudo inconnu (ancien pseudo et clé #), ancienne session 401, Bruno intact")
    void ca5_connexion_impossible_et_session_fermee() throws Exception {
        assertThat(supprimer(alice, MOT_DE_PASSE).statusCode()).isEqualTo(204);
        String cle = jdbc.queryForObject("select pseudo_normalise from compte where id = ?", String.class, idAlice);

        HttpResponse<String> ancien = connecter("Alice", MOT_DE_PASSE);
        HttpResponse<String> inconnu = connecter("Inconnu", MOT_DE_PASSE);
        HttpResponse<String> parCle = connecter(cle, MOT_DE_PASSE);
        HttpResponse<String> parCleAutre = connecter(cle, "autre-mot-de-passe-1");

        for (HttpResponse<String> refus : List.of(ancien, parCle, parCleAutre)) {
            assertThat(refus.statusCode()).isEqualTo(401);
            assertThat(json.readTree(refus.body()).get("code").asString()).isEqualTo("IDENTIFIANTS_INVALIDES");
            assertThat(refus.body()).isEqualTo(inconnu.body());
        }
        assertThat(inconnu.statusCode()).isEqualTo(401);

        for (String chemin : List.of("/api/comptes/moi", "/api/coureur/inscriptions")) {
            HttpResponse<String> residuelle = api.requete("GET", chemin, alice.enteteLecture(), null, null);
            assertThat(residuelle.statusCode()).as(chemin).isEqualTo(401);
            assertThat(json.readTree(residuelle.body()).get("code").asString()).isEqualTo("NON_AUTHENTIFIE");
        }
        assertThat(api.requete("GET", "/api/comptes/moi", bruno.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
        assertThat(connecter("Bruno", MOT_DE_PASSE).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : deux sessions d'Alice et une de Bruno : S1 supprime (204, cookie effacé), S2 reçoit 401, S3 reste valide")
    void ca6_toutes_les_sessions_sont_invalidees() throws Exception {
        Session s2 = api.ouvrir("Alice", MOT_DE_PASSE);
        assertThat(api.requete("GET", "/api/coureur/inscriptions", s2.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);

        HttpResponse<String> reponse = supprimer(alice, MOT_DE_PASSE);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.headers().allValues("set-cookie")).anySatisfy(cookie -> {
            assertThat(cookie).startsWith("JSESSIONID=");
            assertThat(cookie).containsIgnoringCase("Max-Age=0");
        });
        assertThat(api.requete("GET", "/api/coureur/inscriptions", s2.enteteLecture(), null, null).statusCode())
                .isEqualTo(401);
        assertThat(api.requete("GET", "/api/coureur/inscriptions", alice.enteteLecture(), null, null).statusCode())
                .isEqualTo(401);
        assertThat(api.requete("GET", "/api/coureur/inscriptions", bruno.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : pseudo libéré réutilisable (Alice puis ALICE), nouveau compte sans inscription, deux comptes anonymisés coexistent, « Coureur anonyme » refusé à la création")
    void ca7_pseudo_libere_et_reutilisable() throws Exception {
        assertThat(supprimer(alice, MOT_DE_PASSE).statusCode()).isEqualTo(204);
        Map<String, Object> ancien = jdbc.queryForMap("select * from compte where id = ?", idAlice);

        HttpResponse<String> creation = creerCompte("Alice", "nouveau-mot-de-passe-1");

        assertThat(creation.statusCode()).isEqualTo(201);
        UUID idNouveau = jdbc.queryForObject("select id from compte where pseudo = 'Alice'", UUID.class);
        assertThat(idNouveau).isNotEqualTo(idAlice);
        Session nouvelle = api.ouvrir("Alice", "nouveau-mot-de-passe-1");
        HttpResponse<String> inscriptions = api.requete("GET", "/api/coureur/inscriptions",
                nouvelle.enteteLecture(), null, null);
        assertThat(inscriptions.statusCode()).isEqualTo(200);
        assertThat(json.readTree(inscriptions.body()).size()).isZero();
        assertThat(jdbc.queryForMap("select * from compte where id = ?", idAlice)).isEqualTo(ancien);
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                idAlice)).isEqualTo(2);

        assertThat(supprimer(nouvelle, "nouveau-mot-de-passe-1").statusCode()).isEqualTo(204);
        assertThat(creerCompte("ALICE", "troisieme-mot-de-passe-1").statusCode()).isEqualTo(201);

        List<String> cles = jdbc.queryForList("select pseudo_normalise from compte where pseudo = ?", String.class,
                ANONYME);
        assertThat(cles).hasSize(2).doesNotHaveDuplicates().allSatisfy(cle -> assertThat(cle).startsWith("#")
                .hasSize(30));
        HttpResponse<String> interdit = creerCompte(ANONYME, "un-mot-de-passe-12");
        assertThat(interdit.statusCode()).isEqualTo(400);
        assertThat(json.readTree(interdit.body()).get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : mot de passe faux, vide, absent, de 129 caractères ou corps invalide : 400 et base inchangée, sans écho du mot de passe")
    void ca8_erreurs_de_validation_sans_modification() throws Exception {
        List<Map<String, Object>> comptesAvant = jdbc.queryForList("select * from compte order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        String secret = "mauvais-secret-surveille";
        demarrerJournal();

        HttpResponse<String> faux = supprimer(alice, secret);
        HttpResponse<String> vide = supprimer(alice, "");
        HttpResponse<String> absent = envoyer(alice, "{}");
        HttpResponse<String> trop = supprimer(alice, "x".repeat(129));
        HttpResponse<String> illisible = envoyer(alice, "{pas du json");

        assertThat(json.readTree(faux.body()).get("code").asString()).isEqualTo("MOT_DE_PASSE_ACTUEL_INCORRECT");
        assertThat(faux.statusCode()).isEqualTo(400);
        assertThat(trop.statusCode()).isEqualTo(400);
        assertThat(json.readTree(trop.body()).get("code").asString()).isEqualTo("MOT_DE_PASSE_ACTUEL_INCORRECT");
        assertThat(trop.body()).isEqualTo(faux.body());
        for (HttpResponse<String> requise : List.of(vide, absent)) {
            assertThat(requise.statusCode()).isEqualTo(400);
            JsonNode corps = json.readTree(requise.body());
            assertThat(corps.get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
            assertThat(corps.get("erreurs").get(0).get("champ").asString()).isEqualTo("motDePasseActuel");
            assertThat(corps.get("erreurs").get(0).get("code").asString()).isEqualTo("MOT_DE_PASSE_ACTUEL_REQUIS");
        }
        assertThat(illisible.statusCode()).isEqualTo(400);
        assertThat(json.readTree(illisible.body()).get("code").asString()).isEqualTo("CORPS_ILLISIBLE");

        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(comptesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        for (HttpResponse<String> reponse : List.of(faux, vide, absent, trop, illisible)) {
            assertThat(reponse.body()).doesNotContain(secret).doesNotContain("x".repeat(129));
        }
        assertThat(messagesDuJournal()).noneMatch(m -> m.contains(secret) || m.contains("x".repeat(129)));
    }

    @Test
    @DisplayName("CA8 : cinq mots de passe faux puis 429 TENTATIVES_EXCESSIVES avec Retry-After, même avec le bon mot de passe ; compte intact")
    void ca8_blocage_apres_echecs_repetes() throws Exception {
        List<Map<String, Object>> comptesAvant = jdbc.queryForList("select * from compte order by id");

        for (int i = 0; i < 5; i++) {
            assertThat(supprimer(alice, "faux-mot-de-passe-" + i).statusCode()).as("échec " + i).isEqualTo(400);
        }
        HttpResponse<String> bloquee = supprimer(alice, MOT_DE_PASSE);

        assertThat(bloquee.statusCode()).isEqualTo(429);
        JsonNode corps = json.readTree(bloquee.body());
        assertThat(corps.get("code").asString()).isEqualTo("TENTATIVES_EXCESSIVES");
        int secondes = corps.get("reessayerDansSecondes").asInt();
        assertThat(secondes).isPositive();
        assertThat(bloquee.headers().firstValue("retry-after")).hasValue(String.valueOf(secondes));
        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(comptesAvant);
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                idAlice)).isEqualTo(4);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : anonyme 401 ; Alice sans CSRF 403 CSRF_INVALIDE, base inchangée")
    void ca9_anonyme_et_csrf() throws Exception {
        List<Map<String, Object>> comptesAvant = jdbc.queryForList("select * from compte order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        String jeton = api.jetonValide();

        HttpResponse<String> anonyme = api.requete("POST", SUPPRESSION,
                Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton), "application/json", corps(MOT_DE_PASSE));
        HttpResponse<String> sansCsrf = api.requete("POST", SUPPRESSION,
                Map.of("Cookie", "JSESSIONID=" + alice.id()), "application/json", corps(MOT_DE_PASSE));
        HttpResponse<String> csrfInvalide = api.requete("POST", SUPPRESSION,
                Map.of("Cookie", "JSESSIONID=" + alice.id() + "; XSRF-TOKEN=" + alice.xsrf(), "X-XSRF-TOKEN",
                        "invalide"), "application/json", corps(MOT_DE_PASSE));

        assertThat(anonyme.statusCode()).isEqualTo(401);
        assertThat(json.readTree(anonyme.body()).get("code").asString()).isEqualTo("NON_AUTHENTIFIE");
        for (HttpResponse<String> refus : List.of(sansCsrf, csrfInvalide)) {
            assertThat(refus.statusCode()).isEqualTo(403);
            assertThat(json.readTree(refus.body()).get("code").asString()).isEqualTo("CSRF_INVALIDE");
        }
        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(comptesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(api.requete("GET", "/api/comptes/moi", alice.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("CA9 : Patron, Nadia et Léo reçoivent 403 ACCES_REFUSE, comptes intacts, toujours connectables")
    void ca9_roles_non_coureur_refuses() throws Exception {
        UUID idPatron = jdbc.queryForObject("select id from compte where pseudo = 'Patron'", UUID.class);
        UUID idCourse = UUID.fromString(p1);
        jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", idCourse, idLeo);
        List<Map<String, Object>> comptesAvant = jdbc.queryForList("select * from compte order by id");
        List<Map<String, Object>> affectationsAvant = jdbc.queryForList("select * from affectation_benevole");

        for (Session session : List.of(patron, nadia, leo)) {
            HttpResponse<String> refus = supprimer(session, motDePasseDe(session));
            assertThat(refus.statusCode()).isEqualTo(403);
            assertThat(json.readTree(refus.body()).get("code").asString()).isEqualTo("ACCES_REFUSE");
        }

        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(comptesAvant);
        assertThat(jdbc.queryForList("select * from affectation_benevole")).isEqualTo(affectationsAvant);
        assertThat(jdbc.queryForObject("select empreinte_mot_de_passe is not null from compte where id = ?",
                Boolean.class, idPatron)).isTrue();
        assertThat(connecter("Patron", MOT_DE_PASSE_PATRON).statusCode()).isEqualTo(200);
        assertThat(connecter("Nadia", MOT_DE_PASSE).statusCode()).isEqualTo(200);
        assertThat(connecter("Léo", MOT_DE_PASSE).statusCode()).isEqualTo(200);
        assertThat(idNadia).isNotNull();
    }

    @Test
    @DisplayName("CA9 : GET, PUT, PATCH, DELETE sur /api/comptes/moi/suppression : 404 ou 405, aucune modification")
    void ca9_autres_methodes_refusees() throws Exception {
        List<Map<String, Object>> comptesAvant = jdbc.queryForList("select * from compte order by id");

        for (String methode : List.of("GET", "PUT", "PATCH", "DELETE")) {
            boolean corps = !methode.equals("GET") && !methode.equals("DELETE");
            HttpResponse<String> reponse = api.requete(methode, SUPPRESSION, alice.entetes(),
                    corps ? "application/json" : null, corps ? corps(MOT_DE_PASSE) : null);
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        HttpResponse<String> deleteAvecCorps = api.requete("DELETE", SUPPRESSION, alice.entetes(),
                "application/json", corps(MOT_DE_PASSE));
        assertThat(deleteAvecCorps.statusCode()).isIn(404, 405);

        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(comptesAvant);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : liste des inscrits (3.5) : ligne « Coureur anonyme » conservée en C et T, annulée absente de P1, sans « Compte inconnu » ; LirePseudos ne lève pas ; listes admins/bénévoles inchangées")
    void ca10_relecture_d_un_compte_anonymise() throws Exception {
        String adminsAvant = api.requete("GET", "/api/administration/admins", patron.enteteLecture(), null, null)
                .body();
        String benevolesAvant = api.requete("GET", "/api/administration/benevoles", nadia.enteteLecture(), null,
                null).body();
        assertThat(supprimer(alice, MOT_DE_PASSE).statusCode()).isEqualTo(204);

        JsonNode inscritsC = json.readTree(listerInscrits(patron, c).body()).get("inscrits");
        assertThat(inscritsC).hasSize(2);
        assertThat(inscritsC.get(0).get("dossard").asInt()).isEqualTo(1);
        assertThat(inscritsC.get(0).get("pseudo").asString()).isEqualTo(ANONYME);
        assertThat(inscritsC.get(0).get("statut").asString()).isEqualTo("EN_COURSE");
        assertThat(inscritsC.get(1).get("dossard").asInt()).isEqualTo(2);
        assertThat(inscritsC.get(1).get("pseudo").asString()).isEqualTo("Chloé");

        JsonNode inscritsT = json.readTree(listerInscrits(nadia, t).body()).get("inscrits");
        assertThat(inscritsT).hasSize(1);
        assertThat(inscritsT.get(0).get("pseudo").asString()).isEqualTo(ANONYME);
        assertThat(inscritsT.get(0).get("dossard").asInt()).isEqualTo(1);

        HttpResponse<String> reponseP1 = listerInscrits(patron, p1);
        JsonNode inscritsP1 = json.readTree(reponseP1.body()).get("inscrits");
        assertThat(inscritsP1).extracting(n -> n.get("pseudo").asString()).containsExactly("Bruno", "Chloé");
        assertThat(inscritsP1).extracting(n -> n.get("dossard").asInt()).containsExactly(2, 3);
        assertThat(reponseP1.body()).doesNotContain(ANONYME).doesNotContain("Compte inconnu");
        assertThat(listerInscrits(patron, c).body()).doesNotContain("Compte inconnu");

        // Un second compte anonymisé dans C : deux lignes distinguées par le dossard.
        assertThat(supprimer(chloe, MOT_DE_PASSE).statusCode()).isEqualTo(204);
        JsonNode deuxAnonymes = json.readTree(listerInscrits(patron, c).body()).get("inscrits");
        assertThat(deuxAnonymes).extracting(n -> n.get("pseudo").asString()).containsExactly(ANONYME, ANONYME);
        assertThat(deuxAnonymes).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2);

        assertThat(lirePseudos.executer(Set.of(idAlice, idChloe, idBruno)))
                .containsEntry(idAlice, ANONYME).containsEntry(idChloe, ANONYME).containsEntry(idBruno, "Bruno");
        assertThat(api.requete("GET", "/api/administration/admins", patron.enteteLecture(), null, null).body())
                .isEqualTo(adminsAvant);
        assertThat(api.requete("GET", "/api/administration/benevoles", nadia.enteteLecture(), null, null).body())
                .isEqualTo(benevolesAvant);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : panne à l'enregistrement du compte anonymisé : 5xx générique, rien n'a changé (compte, inscriptions P1/P2, sessions)")
    void ca11_atomicite_en_cas_de_panne() throws Exception {
        List<Map<String, Object>> comptesAvant = jdbc.queryForList("select * from compte order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        doThrow(new IllegalStateException("panne simulée secret-technique")).when(depotComptes).mettreAJour(any());

        HttpResponse<String> reponse = supprimer(alice, MOT_DE_PASSE);

        assertThat(reponse.statusCode()).isBetween(500, 599);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        assertThat(reponse.body()).doesNotContain("secret-technique").doesNotContain(MOT_DE_PASSE);
        assertThat(jdbc.queryForList("select * from compte order by id")).isEqualTo(comptesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(api.requete("GET", "/api/comptes/moi", alice.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("CA11 : deux POST simultanés du même compte : un seul 204, l'autre refusé, jamais de 5xx")
    void ca11_deux_suppressions_simultanees() throws Exception {
        List<Integer> statuts = enParallele(List.of(
                () -> supprimer(alice, MOT_DE_PASSE).statusCode(),
                () -> supprimer(alice, MOT_DE_PASSE).statusCode()));

        assertThat(statuts).containsExactlyInAnyOrder(204, 401);
        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, idAlice))
                .isEqualTo(ANONYME);
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                idAlice)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA11 : suppression simultanée de la course P2 par Patron : jamais de 500, état final cohérent")
    void ca11_suppression_concurrente_d_une_course() throws Exception {
        List<Integer> statuts = enParallele(List.of(
                () -> supprimer(alice, MOT_DE_PASSE).statusCode(),
                () -> api.requete("DELETE", CHEMIN_ADMIN + "/" + p2, patron.entetes(), null, null).statusCode()));

        assertThat(statuts).allSatisfy(s -> assertThat(s).isLessThan(500));
        assertThat(statuts.get(0)).isEqualTo(204);
        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, idAlice))
                .isEqualTo(ANONYME);
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                idAlice)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                UUID.fromString(p2))).isZero();
    }

    @Test
    @DisplayName("CA11 : passage de P1 à EN_COURS en parallèle : inscription supprimée ou conservée, jamais d'état intermédiaire")
    void ca11_demarrage_concurrent_de_la_course() throws Exception {
        List<Integer> statuts = enParallele(List.of(
                () -> supprimer(alice, MOT_DE_PASSE).statusCode(),
                () -> jdbc.update("update course set statut = 'EN_COURS' where id = ?", UUID.fromString(p1))));

        assertThat(statuts.get(0)).isEqualTo(204);
        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, idAlice))
                .isEqualTo(ANONYME);
        int restantes = jdbc.queryForObject("select count(*) from inscription where compte_id = ? and course_id = ?",
                Integer.class, idAlice, UUID.fromString(p1));
        assertThat(restantes).isIn(0, 1);
        if (restantes == 1) {
            assertThat(jdbc.queryForObject("select statut from course where id = ?", String.class,
                    UUID.fromString(p1))).isEqualTo("EN_COURS");
        }
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ? and course_id = ?",
                Integer.class, idAlice, UUID.fromString(p2))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ? and course_id = ?",
                Integer.class, idAlice, UUID.fromString(c))).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : journal INFO sur succès et sur échec, sans pseudo, mot de passe, jeton QR ni nom de course")
    void ca12_journal_sans_donnee_sensible() throws Exception {
        List<String> jetons = jdbc.queryForList("select jeton_qr from inscription", String.class);
        demarrerJournal();

        HttpResponse<String> echec = supprimer(bruno, "faux-mot-de-passe-1");
        HttpResponse<String> succes = supprimer(alice, MOT_DE_PASSE);

        assertThat(echec.statusCode()).isEqualTo(400);
        assertThat(succes.statusCode()).isEqualTo(204);
        List<ILoggingEvent> evenements = new ArrayList<>(journal.list);
        assertThat(evenements).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage())
                    .isEqualTo("Compte supprimé (compte " + idAlice + ", 2 inscription(s) annulée(s))");
        });
        assertThat(evenements).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).isEqualTo("Échec de suppression de compte (compte " + idBruno + ")");
        });
        List<String> interdits = new ArrayList<>(jetons);
        interdits.addAll(List.of("Alice", "Bruno", "faux-mot-de-passe-1", MOT_DE_PASSE, NOM_P1, NOM_P2, NOM_C, NOM_T));
        assertThat(messagesDuJournal()).allSatisfy(m -> assertThat(m).doesNotContain(interdits));
        assertThat(succes.body() + echec.body()).doesNotContain(interdits).doesNotContain(idAlice.toString());
    }

    @Test
    @DisplayName("CA12 : aucune migration ajoutée (databasechangelog 0002 à 0007), schéma valide")
    void ca12_aucune_migration() {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .isEqualTo(CHANGESETS);
    }

    // ---------------------------------------------------------------- utilitaires

    private String motDePasseDe(Session session) {
        return session == patron ? MOT_DE_PASSE_PATRON : MOT_DE_PASSE;
    }

    private List<Integer> enParallele(List<Callable<Object>> actions) throws Exception {
        ExecutorService executeur = Executors.newFixedThreadPool(actions.size());
        try {
            java.util.concurrent.CountDownLatch depart = new java.util.concurrent.CountDownLatch(1);
            List<Future<Object>> futurs = new ArrayList<>();
            for (Callable<Object> action : actions) {
                futurs.add(executeur.submit(() -> {
                    depart.await();
                    return action.call();
                }));
            }
            depart.countDown();
            List<Integer> resultats = new ArrayList<>();
            for (Future<Object> futur : futurs) {
                resultats.add((Integer) futur.get());
            }
            return resultats;
        } finally {
            executeur.shutdownNow();
        }
    }

    private String corps(String motDePasse) {
        ObjectNode corps = json.createObjectNode();
        corps.put("motDePasseActuel", motDePasse);
        return corps.toString();
    }

    private HttpResponse<String> supprimer(Session session, String motDePasse) throws Exception {
        return envoyer(session, corps(motDePasse));
    }

    private HttpResponse<String> envoyer(Session session, String corps) throws Exception {
        return api.requete("POST", SUPPRESSION, session.entetes(), "application/json", corps);
    }

    private HttpResponse<String> connecter(String pseudo, String motDePasse) throws Exception {
        String jeton = api.jetonValide();
        ObjectNode corps = json.createObjectNode();
        corps.put("pseudo", pseudo);
        corps.put("motDePasse", motDePasse);
        return api.requete("POST", "/api/connexion", Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton),
                "application/json", corps.toString());
    }

    private HttpResponse<String> creerCompte(String pseudo, String motDePasse) throws Exception {
        String jeton = api.jetonValide();
        ObjectNode corps = json.createObjectNode();
        corps.put("pseudo", pseudo);
        corps.put("motDePasse", motDePasse);
        return api.requete("POST", "/api/comptes", Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton),
                "application/json", corps.toString());
    }

    private HttpResponse<String> listerInscrits(Session session, String idCourse) throws Exception {
        return api.requete("GET", CHEMIN_ADMIN + "/" + idCourse + "/inscriptions", session.enteteLecture(), null,
                null);
    }

    private void inscrire(String idCourse, Session coureur) throws Exception {
        assertThat(api.requete("POST", CHEMIN_COURSES + "/" + idCourse + "/inscriptions", coureur.entetes(), null,
                null).statusCode()).isEqualTo(201);
    }

    private UUID insererCompte(String pseudo, String role, String empreinte) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte, role,
                Timestamp.from(CREE_LE));
        return id;
    }

    private String creerCourse(String nom, int maximum) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", "2026-10-10");
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", maximum);
        corps.put("nombreMaxBoucles", 24);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN_ADMIN, patron.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private List<String> messagesDuJournal() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent e : journal.list) {
            messages.add(e.getFormattedMessage());
            if (e.getThrowableProxy() != null) {
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
}
