package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.Connecter;
import fr.backyard.tracker.comptes.application.ConsulterCompteConnecte;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.CompteJpaAdapter;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * Contrat de l'incrément 9.7 : POST /api/administration/coureurs/{id}/passage-admin (CA4 à CA8, CA10 à CA16), réservé
 * à l'admin master. Les états sont lus en base ; les Inscriptions sont posées par SQL, sauf là où l'API est elle-même
 * l'objet du scénario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class PassageEnAdminIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE_COUREUR = "un-mot-de-passe-12";
    static final String BENEVOLES = "/api/administration/benevoles";
    static final String ADMINS = "/api/administration/admins";
    static final String COURSES = "/api/administration/courses";
    static final String RECHERCHE = "/api/administration/coureurs";
    static final String SUPPRESSION_COMPTE = "/api/comptes/moi/suppression";
    static final String ANONYME = "Coureur anonyme";
    static final Instant MAINTENANT = Instant.parse("2026-10-09T14:03:27Z");
    static final String NOM_P = "Preparation un";
    static final String NOM_P2 = "Preparation deux";
    static final String NOM_C = "Backyard C";
    static final String NOM_T = "Course terminee";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, distance_boucle_metres, "
            + "duree_boucle_minutes, denivele_positif_boucle_metres, nombre_max_participants, nombre_max_boucles, "
            + "demarree_le) values (?, ?, ?, ?, 400, 60, 0, 10, 5, ?)";

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

    @MockitoSpyBean
    CompteJpaAdapter depotComptes;

    @MockitoSpyBean
    Connecter connecter;

    @MockitoSpyBean
    ConsulterCompteConnecte consulterCompteConnecte;

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    JournalApplicatif journal;
    String empreinteCoureur;
    Session patron;
    Session nadia;
    Session leo;
    Session alice;
    UUID idPatron;
    UUID idNadia;
    UUID idLeo;
    UUID idAlice;
    UUID p;
    UUID p2;
    UUID c;
    UUID t;
    int compteur;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(MAINTENANT);
        api = new ClientHttp(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        idPatron = jdbc.queryForObject("select id from compte where role = 'ADMIN_MASTER'", UUID.class);
        empreinteCoureur = encodeur.encode(MOT_DE_PASSE_COUREUR);
        idNadia = insererCompte("Nadia", "ADMIN", encodeur.encode(MOT_DE_PASSE_ADMIN));
        idLeo = insererCompte("Léo", "BENEVOLE", encodeur.encode(MOT_DE_PASSE_BENEVOLE));
        idAlice = insererCompte("Alice", "COUREUR", empreinteCoureur);
        p = insererCourse(NOM_P, "2026-10-09", "EN_PREPARATION", null);
        p2 = insererCourse(NOM_P2, "2026-11-01", "EN_PREPARATION", null);
        c = insererCourse(NOM_C, "2026-10-09", "EN_COURS", Instant.parse("2026-10-09T13:30:00Z"));
        t = insererCourse(NOM_T, "2026-10-08", "TERMINEE", Instant.parse("2026-10-08T08:00:00Z"));
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        leo = api.ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE_COUREUR);
        journal = JournalApplicatif.demarrer().niveau("fr.backyard.tracker", Level.DEBUG);
    }

    @AfterEach
    void nettoyer() {
        journal.fermer();
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Patron passe Coureur1 (sans Inscription, deux sessions) en admin : 200 CompteReponse ADMIN actif, date d'origine, sans empreinte ; seul le rôle change en base ; sessions de Coureur1 fermées (401), celles de Léo, de Nadia et de Patron valides ; reconnexion en ADMIN avec accès à /api/administration/benevoles")
    void ca4_passage_nominal_et_sessions() throws Exception {
        UUID coureur1 = coureur("Coureur1");
        Session s1 = api.ouvrir("Coureur1", MOT_DE_PASSE_COUREUR);
        Session s2 = api.ouvrir("Coureur1", MOT_DE_PASSE_COUREUR);
        Session s4 = api.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        assertThat(lire(s1, "/api/coureur/inscriptions").statusCode()).isEqualTo(200);
        Map<String, Object> avant = jdbc.queryForMap("select * from compte where id = ?", coureur1);
        Instant creeLe = ((Timestamp) avant.get("cree_le")).toInstant();

        HttpResponse<String> reponse = promouvoir(patron, coureur1);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("application/json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("id").asString()).isEqualTo(coureur1.toString());
        assertThat(corps.get("pseudo").asString()).isEqualTo("Coureur1");
        assertThat(corps.get("role").asString()).isEqualTo("ADMIN");
        assertThat(corps.get("actif").asBoolean()).isTrue();
        assertThat(Instant.parse(corps.get("creeLe").asString())).isEqualTo(creeLe);
        assertThat(reponse.body()).doesNotContain("empreinte").doesNotContain("motDePasse");
        Map<String, Object> apres = jdbc.queryForMap("select * from compte where id = ?", coureur1);
        Map<String, Object> attendu = new java.util.HashMap<>(avant);
        attendu.put("role", "ADMIN");
        assertThat(apres).isEqualTo(attendu);

        for (Session fermee : List.of(s1, s2)) {
            assertProbleme(lire(fermee, "/api/comptes/moi"), 401, "Authentification requise", "NON_AUTHENTIFIE",
                    "Vous devez être connecté.");
        }
        assertThat(lire(leo, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(s4, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(patron, "/api/comptes/moi").statusCode()).isEqualTo(200);

        registre.vider();
        HttpResponse<String> connexion = connexion("Coureur1", MOT_DE_PASSE_COUREUR);
        assertThat(connexion.statusCode()).isEqualTo(200);
        assertThat(json.readTree(connexion.body()).get("role").asString()).isEqualTo("ADMIN");
        Session nouvelle = new Session(ClientHttp.valeurCookie(connexion, "JSESSIONID"), api.jetonValide());
        assertThat(lire(nouvelle, BENEVOLES).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA4 : le compteur d'échecs de connexion n'est pas remis à zéro par le changement : 3 échecs avant, 2 après, puis 429 même avec le bon mot de passe")
    void ca4_compteur_d_echecs_conserve() throws Exception {
        UUID coureur = coureur("Coureur1c");
        registre.vider();
        for (int i = 0; i < 3; i++) {
            assertThat(connexion("Coureur1c", "mauvais-mot-de-passe-1").statusCode()).as("avant " + i).isEqualTo(401);
        }

        assertThat(promouvoir(patron, coureur).statusCode()).isEqualTo(200);

        for (int i = 0; i < 2; i++) {
            assertThat(connexion("Coureur1c", "mauvais-mot-de-passe-1").statusCode()).as("après " + i).isEqualTo(401);
        }
        assertThat(connexion("Coureur1c", MOT_DE_PASSE_COUREUR).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : Nadia (ADMIN) : 403 ACCES_REFUSE de corps identique pour une cible valide, un identifiant inconnu, pas-un-uuid et une cible refusable (inscrite à C) ; rien modifié, aucune session fermée ; le même coureur est ensuite promu par Patron (200)")
    void ca5_admin_simple_refuse_avant_toute_validation() throws Exception {
        UUID cible = coureur("CibleAdminSimple");
        UUID refusable = coureur("CibleRefusable");
        inscrire(c, refusable, 1, "EN_COURSE");
        Session session = api.ouvrir("CibleAdminSimple", MOT_DE_PASSE_COUREUR);
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> valide = promouvoir(nadia, cible);
        List<HttpResponse<String>> reponses = List.of(valide, promouvoir(nadia, UUID.randomUUID()),
                promouvoir(nadia, "pas-un-uuid"), promouvoir(nadia, refusable), promouvoir(nadia, idPatron),
                promouvoir(nadia, idNadia));

        for (HttpResponse<String> reponse : reponses) {
            assertProbleme(reponse, 403, "Accès refusé", "ACCES_REFUSE", "Vous n'avez pas les droits nécessaires.");
            assertThat(normaliser(reponse.body())).isEqualTo(normaliser(valide.body()));
        }
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);

        assertThat(promouvoir(patron, cible).statusCode()).isEqualTo(200);
        assertThat(roleDe(cible)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("CA5 : Nadia (ADMIN), toutes méthodes sur le chemin (POST, GET, PUT, PATCH, DELETE), y compris avec un identifiant non UUID : 403 ACCES_REFUSE, rien modifié")
    void ca5_admin_simple_toutes_methodes() throws Exception {
        UUID cible = coureur("CibleMethodesAdmin");
        List<Map<String, Object>> avant = etatBase();

        for (String methode : List.of("POST", "GET", "PUT", "PATCH", "DELETE")) {
            for (Object id : List.of(cible, "pas-un-uuid")) {
                HttpResponse<String> reponse = api.requete(methode, chemin(id), nadia.entetes(), null, null);
                assertProbleme(reponse, 403, "Accès refusé", "ACCES_REFUSE", "Vous n'avez pas les droits nécessaires.");
            }
        }
        assertThat(etatBase()).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA5 : Léo (bénévole) et Alice (coureuse) : 403 ACCES_REFUSE identique pour une cible existante, un identifiant inconnu et un non-UUID ; anonyme : 401 NON_AUTHENTIFIE ; cible inchangée")
    void ca5_autres_roles_et_anonyme() throws Exception {
        UUID cible = coureur("CibleRoles");
        List<Map<String, Object>> avant = etatBase();
        String jeton = api.jetonValide();

        HttpResponse<String> anonyme = api.requete("POST", chemin(cible), Map.of("Cookie", "XSRF-TOKEN=" + jeton,
                "X-XSRF-TOKEN", jeton), null, null);
        assertProbleme(anonyme, 401, "Authentification requise", "NON_AUTHENTIFIE", "Vous devez être connecté.");

        for (Session session : List.of(leo, alice)) {
            HttpResponse<String> existante = promouvoir(session, cible);
            HttpResponse<String> inconnue = promouvoir(session, UUID.randomUUID().toString());
            HttpResponse<String> pasUnUuid = promouvoir(session, "pas-un-uuid");
            for (HttpResponse<String> reponse : List.of(existante, inconnue, pasUnUuid)) {
                assertProbleme(reponse, 403, "Accès refusé", "ACCES_REFUSE", "Vous n'avez pas les droits nécessaires.");
                assertThat(normaliser(reponse.body())).isEqualTo(normaliser(existante.body()));
            }
        }
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(roleDe(cible)).isEqualTo("COUREUR");
    }

    @Test
    @DisplayName("CA5 : jeton CSRF absent ou invalide pour Patron : 403 CSRF_INVALIDE, base inchangée, sessions du coureur valides")
    void ca5_csrf_absent_ou_invalide() throws Exception {
        UUID cible = coureur("CibleCsrf");
        Session session = api.ouvrir("CibleCsrf", MOT_DE_PASSE_COUREUR);
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> sansJeton = api.requete("POST", chemin(cible),
                Map.of("Cookie", "JSESSIONID=" + patron.id()), null, null);
        HttpResponse<String> invalide = api.requete("POST", chemin(cible), Map.of("Cookie",
                "JSESSIONID=" + patron.id() + "; XSRF-TOKEN=" + patron.xsrf(), "X-XSRF-TOKEN", "invalide"), null, null);

        for (HttpResponse<String> reponse : List.of(sansJeton, invalide)) {
            assertProbleme(reponse, 403, "Accès refusé", "CSRF_INVALIDE", "Jeton CSRF absent ou invalide.");
        }
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA5 : Patron + GET, PUT, PATCH, DELETE sur le chemin : 404 ou 405, jamais 2xx, rien modifié ; un corps de requête est ignoré")
    void ca5_methodes_admin_master_et_corps() throws Exception {
        UUID cible = coureur("CibleMethodes");
        List<Map<String, Object>> avant = etatBase();

        for (String methode : List.of("GET", "PUT", "PATCH", "DELETE")) {
            HttpResponse<String> reponse = api.requete(methode, chemin(cible), patron.entetes(), null, null);
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        assertThat(etatBase()).isEqualTo(avant);

        HttpResponse<String> avecCorps = api.requete("POST", chemin(cible), patron.entetes(), "application/json",
                "{\"role\":\"ADMIN_MASTER\",\"n'importe\":\"quoi\"}");
        assertThat(avecCorps.statusCode()).isEqualTo(200);
        assertThat(json.readTree(avecCorps.body()).get("role").asString()).isEqualTo("ADMIN");
        assertThat(roleDe(cible)).isEqualTo("ADMIN");
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : Coureur2 inscrit à P et P2 (EN_PREPARATION) : 409 COUREUR_INSCRIT_COURSE_EN_PREPARATION au corps exact, identique à celui de passage-benevole hors instance ; rôle, sessions et Inscriptions inchangés ; après désinscription aux deux : 200")
    void ca6_refus_en_preparation_puis_desinscription() throws Exception {
        UUID coureur2 = coureur("Coureur2");
        Session session = api.ouvrir("Coureur2", MOT_DE_PASSE_COUREUR);
        UUID inscriptionP = inscrire(p, coureur2, 1, "EN_COURSE");
        UUID inscriptionP2 = inscrire(p2, coureur2, 1, "EN_COURSE");
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> refus = promouvoir(patron, coureur2);

        assertConflit(refus, coureur2, "COUREUR_INSCRIT_COURSE_EN_PREPARATION",
                "Ce coureur est inscrit aux courses « " + NOM_P2 + " », « " + NOM_P + " » qui n'ont pas démarré. "
                        + "Il doit d'abord se désinscrire.",
                List.of(p2, p), List.of(NOM_P2, NOM_P));
        HttpResponse<String> refusBenevole = promouvoirBenevole(patron, coureur2);
        assertThat(refusBenevole.statusCode()).isEqualTo(409);
        ObjectNode corpsAdmin = (ObjectNode) json.readTree(refus.body());
        ObjectNode corpsBenevole = (ObjectNode) json.readTree(refusBenevole.body());
        assertThat(corpsAdmin.get("instance").asString()).isEqualTo(chemin(coureur2));
        assertThat(corpsBenevole.get("instance").asString()).isEqualTo(cheminBenevole(coureur2));
        corpsAdmin.remove("instance");
        corpsBenevole.remove("instance");
        assertThat(corpsAdmin).isEqualTo(corpsBenevole);
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);

        assertThat(api.requete("DELETE", "/api/coureur/inscriptions/" + inscriptionP, session.entetes(), null, null)
                .statusCode()).isEqualTo(204);
        assertThat(api.requete("DELETE", "/api/coureur/inscriptions/" + inscriptionP2, session.entetes(), null, null)
                .statusCode()).isEqualTo(204);
        assertThat(promouvoir(patron, coureur2).statusCode()).isEqualTo(200);
        assertThat(roleDe(coureur2)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("CA6 : une seule Course EN_PREPARATION : detail au singulier, sans pseudo ni identifiant de Compte")
    void ca6_detail_au_singulier() throws Exception {
        UUID coureur = coureur("Coureur2s");
        inscrire(p, coureur, 1, "EN_COURSE");

        HttpResponse<String> refus = promouvoir(patron, coureur);

        assertConflit(refus, coureur, "COUREUR_INSCRIT_COURSE_EN_PREPARATION",
                "Ce coureur est inscrit à la course « " + NOM_P + " » qui n'a pas démarré. "
                        + "Il doit d'abord se désinscrire.", List.of(p), List.of(NOM_P));
        assertThat(json.readTree(refus.body()).get("detail").asString()).doesNotContain("Coureur2s")
                .doesNotContain(coureur.toString());
    }

    @Test
    @DisplayName("CA6 : courses du 409 triées par nom sans tenir compte de la casse, puis par identifiant à nom égal")
    void ca6_tri_insensible_a_la_casse_puis_identifiant() throws Exception {
        UUID coureur = coureur("Coureur2t");
        UUID zeta = insererCourse("zeta", "2026-11-02", "EN_PREPARATION", null);
        UUID alpha = insererCourse("Alpha", "2026-11-03", "EN_PREPARATION", null);
        UUID bravo = insererCourse("bravo", "2026-11-04", "EN_PREPARATION", null);
        UUID doublonBas = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID doublonHaut = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        insererCourse(doublonHaut, "Charlie", "2026-11-05", "EN_PREPARATION", null);
        insererCourse(doublonBas, "Charlie", "2026-11-06", "EN_PREPARATION", null);
        for (UUID course : List.of(zeta, alpha, bravo, doublonHaut, doublonBas)) {
            inscrire(course, coureur, 1, "EN_COURSE");
        }

        HttpResponse<String> refus = promouvoir(patron, coureur);

        assertThat(refus.statusCode()).isEqualTo(409);
        List<String> ids = new ArrayList<>();
        json.readTree(refus.body()).get("courses").forEach(course -> ids.add(course.get("id").asString()));
        assertThat(ids).containsExactly(alpha.toString(), bravo.toString(), doublonBas.toString(),
                doublonHaut.toString(), zeta.toString());
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : Coureur3 inscrit à C (EN_COURSE, puis ABANDON, puis VAINQUEUR) et à P : à chaque fois 409 COUREUR_INSCRIT_COURSE_EN_COURS, courses = [C] sans P, rien modifié ; C terminée : 409 EN_PREPARATION ; P désinscrite : 200")
    void ca7_refus_en_cours_prioritaire() throws Exception {
        UUID coureur3 = coureur("Coureur3");
        UUID inscriptionC = inscrire(c, coureur3, 1, "EN_COURSE");
        UUID inscriptionP = inscrire(p, coureur3, 1, "EN_COURSE");
        Session session = api.ouvrir("Coureur3", MOT_DE_PASSE_COUREUR);

        for (String statut : List.of("EN_COURSE", "ABANDON", "VAINQUEUR")) {
            jdbc.update("update inscription set statut = ? where id = ?", statut, inscriptionC);
            List<Map<String, Object>> avant = etatBase();

            HttpResponse<String> refus = promouvoir(patron, coureur3);

            assertConflit(refus, coureur3, "COUREUR_INSCRIT_COURSE_EN_COURS",
                    "Ce coureur participe à la course en cours « " + NOM_C + " ». Réessayez à la fin de la course.",
                    List.of(c), List.of(NOM_C));
            assertThat(refus.body()).doesNotContain(NOM_P);
            assertThat(etatBase()).as(statut).isEqualTo(avant);
            assertThat(lire(session, "/api/comptes/moi").statusCode()).as(statut).isEqualTo(200);
        }

        jdbc.update("update course set statut = 'TERMINEE' where id = ?", c);
        assertThat(promouvoir(patron, coureur3).statusCode()).isEqualTo(409);
        jdbc.update("delete from inscription where id = ?", inscriptionP);
        assertThat(promouvoir(patron, coureur3).statusCode()).isEqualTo(200);
        assertThat(roleDe(coureur3)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("CA7 : deux Courses EN_COURS : detail au pluriel avec les deux noms triés")
    void ca7_pluriel_en_cours() throws Exception {
        UUID coureur = coureur("Coureur3b");
        UUID c2 = insererCourse("Alpha en cours", "2026-10-09", "EN_COURS", Instant.parse("2026-10-09T13:00:00Z"));
        inscrire(c, coureur, 2, "ABANDON");
        inscrire(c2, coureur, 2, "EN_COURSE");

        HttpResponse<String> refus = promouvoir(patron, coureur);

        assertConflit(refus, coureur, "COUREUR_INSCRIT_COURSE_EN_COURS",
                "Ce coureur participe aux courses en cours « Alpha en cours », « " + NOM_C + " ». "
                        + "Réessayez à la fin des courses.", List.of(c2, c), List.of("Alpha en cours", NOM_C));
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : Coureur4 n'ayant couru que T (TERMINEE, dossard 5, Passages SCAN et CORRECTION) : 200 ; Inscription, Passages et Course inchangés, le suivi public affiche toujours son pseudo avec le dossard 5")
    void ca8_inscription_terminee_conservee() throws Exception {
        UUID coureur4 = coureur("Coureur4");
        UUID inscription = inscrire(t, coureur4, 5, "VAINQUEUR");
        poserPassages(inscription);
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> passagesAvant = jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");

        HttpResponse<String> reponse = promouvoir(patron, coureur4);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(jdbc.queryForList("select origine from passage order by numero_boucle", String.class))
                .containsExactly("SCAN", "CORRECTION");
        JsonNode suivi = json.readTree(api.requete("GET", "/api/courses/" + t + "/suivi", Map.of(), null, null).body());
        assertThat(suivi.get("vainqueurs")).hasSize(1);
        assertThat(suivi.get("vainqueurs").get(0).get("pseudo").asString()).isEqualTo("Coureur4");
        assertThat(suivi.get("vainqueurs").get(0).get("dossard").asInt()).isEqualTo(5);
        JsonNode inscrits = json.readTree(lire(patron, COURSES + "/" + t + "/inscriptions").body()).get("inscrits");
        assertThat(inscrits).hasSize(1);
        assertThat(inscrits.get(0).get("pseudo").asString()).isEqualTo("Coureur4");
    }

    @Test
    @DisplayName("CA8 : Coureur4 inscrit à T et à P : 409 COUREUR_INSCRIT_COURSE_EN_PREPARATION, courses = [P] sans T")
    void ca8_terminee_absente_du_refus() throws Exception {
        UUID coureur = coureur("Coureur4b");
        inscrire(t, coureur, 5, "VAINQUEUR");
        inscrire(p, coureur, 1, "EN_COURSE");

        HttpResponse<String> refus = promouvoir(patron, coureur);

        assertConflit(refus, coureur, "COUREUR_INSCRIT_COURSE_EN_PREPARATION",
                "Ce coureur est inscrit à la course « " + NOM_P + " » qui n'a pas démarré. "
                        + "Il doit d'abord se désinscrire.", List.of(p), List.of(NOM_P));
        assertThat(refus.body()).doesNotContain(NOM_T);
        assertThat(roleDe(coureur)).isEqualTo("COUREUR");
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : Coureur5 promu : présent une fois (actif) dans la liste des admins, absent de la recherche de coureurs, désactivable puis réactivable (204) ; reconnecté, il reçoit 403 sur création et suppression d'admin et sur passage-admin, mais peut passer un autre coureur en bénévole (200) ; son ancienne session : 401")
    void ca10_apparition_dans_les_listes_et_droits() throws Exception {
        UUID coureur5 = coureur("Coureur5");
        Session ancienne = api.ouvrir("Coureur5", MOT_DE_PASSE_COUREUR);
        assertThat(json.readTree(lire(patron, RECHERCHE + "?q=Coureur5").body()).get("coureurs")).hasSize(1);

        assertThat(promouvoir(patron, coureur5).statusCode()).isEqualTo(200);

        List<JsonNode> trouves = dansLaListe(patron, ADMINS, coureur5);
        assertThat(trouves).hasSize(1);
        assertThat(trouves.get(0).get("actif").asBoolean()).isTrue();
        assertThat(trouves.get(0).get("role").asString()).isEqualTo("ADMIN");
        assertThat(Instant.parse(trouves.get(0).get("creeLe").asString())).isEqualTo(
                jdbc.queryForObject("select cree_le from compte where id = ?", Timestamp.class, coureur5).toInstant());
        assertThat(json.readTree(lire(patron, RECHERCHE + "?q=Coureur5").body()).get("coureurs")).isEmpty();
        assertThat(lire(ancienne, "/api/comptes/moi").statusCode()).isEqualTo(401);

        assertThat(api.requete("POST", ADMINS + "/" + coureur5 + "/desactivation", patron.entetes(), null, null)
                .statusCode()).isEqualTo(204);
        assertThat(api.requete("POST", ADMINS + "/" + coureur5 + "/reactivation", patron.entetes(), null, null)
                .statusCode()).isEqualTo(204);
        assertThat(dansLaListe(patron, ADMINS, coureur5).get(0).get("actif").asBoolean()).isTrue();

        registre.vider();
        Session promu = api.ouvrir("Coureur5", MOT_DE_PASSE_COUREUR);
        assertThat(lire(promu, COURSES).statusCode()).isEqualTo(200);
        HttpResponse<String> creation = api.requete("POST", ADMINS, promu.entetes(), "application/json",
                "{\"pseudo\":\"NouvelAdmin\",\"motDePasse\":\"mot-de-passe-nouveau-1\"}");
        assertProbleme(creation, 403, "Accès refusé", "ACCES_REFUSE", "Vous n'avez pas les droits nécessaires.");
        assertProbleme(supprimerAdmin(promu, idNadia), 403, "Accès refusé", "ACCES_REFUSE",
                "Vous n'avez pas les droits nécessaires.");
        UUID autreCoureur = coureur("AutreCoureur");
        assertProbleme(promouvoir(promu, autreCoureur), 403, "Accès refusé", "ACCES_REFUSE",
                "Vous n'avez pas les droits nécessaires.");
        assertThat(roleDe(autreCoureur)).isEqualTo("COUREUR");
        assertThat(jdbc.queryForObject("select count(*) from compte where pseudo = 'NouvelAdmin'", Integer.class))
                .isZero();
        HttpResponse<String> benevole = promouvoirBenevole(promu, autreCoureur);
        assertThat(benevole.statusCode()).isEqualTo(200);
        assertThat(roleDe(autreCoureur)).isEqualTo("BENEVOLE");
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : Coureur6 (Inscription TERMINEE à T, dossard 7) promu puis supprimé par Patron : 204, Compte anonymisé (pseudo « Coureur anonyme », empreinte nulle, rôle COUREUR), Inscription et Passages conservés, résultats de T affichent « Coureur anonyme » dossard 7, absent des listes")
    void ca11_suppression_avec_inscription_terminee_anonymise() throws Exception {
        UUID coureur6 = coureur("Coureur6");
        UUID inscription = inscrire(t, coureur6, 7, "VAINQUEUR");
        poserPassages(inscription);
        List<Map<String, Object>> passagesAvant = jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        assertThat(promouvoir(patron, coureur6).statusCode()).isEqualTo(200);

        HttpResponse<String> suppression = supprimerAdmin(patron, coureur6);

        assertThat(suppression.statusCode()).isEqualTo(204);
        Map<String, Object> compte = jdbc.queryForMap("select * from compte where id = ?", coureur6);
        assertThat(compte.get("pseudo")).isEqualTo(ANONYME);
        assertThat(compte.get("empreinte_mot_de_passe")).isNull();
        assertThat(compte.get("role")).isEqualTo("COUREUR");
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        JsonNode suivi = json.readTree(api.requete("GET", "/api/courses/" + t + "/suivi", Map.of(), null, null).body());
        assertThat(suivi.get("vainqueurs")).hasSize(1);
        assertThat(suivi.get("vainqueurs").get(0).get("pseudo").asString()).isEqualTo(ANONYME);
        assertThat(suivi.get("vainqueurs").get(0).get("dossard").asInt()).isEqualTo(7);
        assertThat(dansLaListe(patron, ADMINS, coureur6)).isEmpty();
        assertThat(dansLaListe(nadia, BENEVOLES, coureur6)).isEmpty();
        assertThat(json.readTree(lire(patron, RECHERCHE + "?q=Coureur6").body()).get("coureurs")).isEmpty();
        assertThat(connexion("Coureur6", MOT_DE_PASSE_COUREUR).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("CA11 : Coureur7 promu sans Inscription puis supprimé par Patron : 204, ligne disparue de compte")
    void ca11_suppression_sans_inscription_est_physique() throws Exception {
        UUID coureur7 = coureur("Coureur7");
        assertThat(promouvoir(patron, coureur7).statusCode()).isEqualTo(200);

        assertThat(supprimerAdmin(patron, coureur7).statusCode()).isEqualTo(204);

        assertThat(jdbc.queryForObject("select count(*) from compte where id = ?", Integer.class, coureur7)).isZero();
        assertThat(dansLaListe(patron, ADMINS, coureur7)).isEmpty();
    }

    @Test
    @DisplayName("CA11 : Nadia désactivée et Coureur6 seul ADMIN actif : DELETE Coureur6 409 DERNIER_ADMIN_ACTIF, base inchangée")
    void ca11_dernier_admin_actif() throws Exception {
        UUID coureur6 = coureur("Coureur6d");
        assertThat(promouvoir(patron, coureur6).statusCode()).isEqualTo(200);
        jdbc.update("update compte set actif = false where id = ?", idNadia);
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> reponse = supprimerAdmin(patron, coureur6);

        assertProbleme(reponse, 409, "Conflit", "DERNIER_ADMIN_ACTIF",
                "Impossible de supprimer le dernier administrateur actif.");
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(roleDe(coureur6)).isEqualTo("ADMIN");
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : 404 COMPTE_INTROUVABLE de corps identique (à l'identifiant près) pour Patron : identifiant inconnu, pas-un-uuid, Léo, Nadia, Patron lui-même, un coureur anonymisé, un coureur inactif, un coureur déjà promu, un bénévole issu d'un coureur ; rien modifié, sessions intactes")
    void ca12_cibles_invalides_reponse_identique() throws Exception {
        UUID anonymise = coureur("Efface");
        inscrire(t, anonymise, 9, "VAINQUEUR");
        Session sessionAnonymise = api.ouvrir("Efface", MOT_DE_PASSE_COUREUR);
        assertThat(api.requete("POST", SUPPRESSION_COMPTE, sessionAnonymise.entetes(), "application/json",
                "{\"motDePasseActuel\":\"" + MOT_DE_PASSE_COUREUR + "\"}").statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select pseudo from compte where id = ?", String.class, anonymise))
                .isEqualTo(ANONYME);
        UUID inactif = coureur("Dormeur");
        jdbc.update("update compte set actif = false where id = ?", inactif);
        UUID promu = coureur("DejaPromu");
        assertThat(promouvoir(patron, promu).statusCode()).isEqualTo(200);
        UUID benevoleIssu = coureur("BenevoleIssu");
        assertThat(promouvoirBenevole(patron, benevoleIssu).statusCode()).isEqualTo(200);
        registre.vider();
        List<Map<String, Object>> avant = etatBase();

        Map<String, HttpResponse<String>> reponses = new java.util.LinkedHashMap<>();
        reponses.put("inconnu", promouvoir(patron, UUID.randomUUID().toString()));
        reponses.put("pas-un-uuid", promouvoir(patron, "pas-un-uuid"));
        reponses.put("bénévole", promouvoir(patron, idLeo));
        reponses.put("admin", promouvoir(patron, idNadia));
        reponses.put("appelant admin master", promouvoir(patron, idPatron));
        reponses.put("anonymisé", promouvoir(patron, anonymise));
        reponses.put("inactif", promouvoir(patron, inactif));
        reponses.put("déjà promu", promouvoir(patron, promu));
        reponses.put("bénévole issu d'un coureur", promouvoir(patron, benevoleIssu));

        String reference = normaliser(reponses.get("inconnu").body());
        reponses.forEach((cas, reponse) -> {
            assertProbleme(reponse, 404, "Introuvable", "COMPTE_INTROUVABLE", "Ce compte est introuvable.");
            assertThat(normaliser(reponse.body())).as(cas).isEqualTo(reference);
        });
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(leo, "/api/benevole/courses").statusCode()).isEqualTo(200);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(patron, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 (a) : deux passage-admin simultanés sur le même coureur : exactement un 200 et un 404, jamais de 500 (20 répétitions)")
    void ca13_deux_changements_simultanes() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = coureur("Course" + i);
            CountDownLatch depart = new CountDownLatch(1);
            Callable<HttpResponse<String>> action = () -> {
                depart.await();
                return promouvoir(patron, cible);
            };

            List<HttpResponse<String>> reponses = enParallele(depart, action, action);

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(200, 404);
            assertThat(roleDe(cible)).isEqualTo("ADMIN");
        }
    }

    @Test
    @DisplayName("CA13 (b) : passage-admin et passage-benevole simultanés sur le même coureur : un 200 et un 404, rôle final unique et cohérent avec le 200, jamais de 500 (20 répétitions)")
    void ca13_admin_et_benevole_simultanes() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = coureur("Double" + i);
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return promouvoir(patron, cible);
                    },
                    () -> {
                        depart.await();
                        return promouvoirBenevole(patron, cible);
                    });

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(200, 404);
            String attendu = reponses.get(0).statusCode() == 200 ? "ADMIN" : "BENEVOLE";
            assertThat(roleDe(cible)).as("répétition " + i).isEqualTo(attendu);
        }
    }

    @Test
    @DisplayName("CA13 (c) : passage-admin et suppression de son Compte par le coureur simultanés : jamais de 500, jamais un admin sans empreinte non anonymisé (20 répétitions)")
    void ca13_changement_et_suppression_du_compte() throws Exception {
        for (int i = 0; i < 20; i++) {
            String pseudo = "Efface" + i;
            UUID cible = coureur(pseudo);
            inscrire(t, cible, 100 + i, "VAINQUEUR");
            Session session = api.ouvrir(pseudo, MOT_DE_PASSE_COUREUR);
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return promouvoir(patron, cible);
                    },
                    () -> {
                        depart.await();
                        return api.requete("POST", SUPPRESSION_COMPTE, session.entetes(), "application/json",
                                "{\"motDePasseActuel\":\"" + MOT_DE_PASSE_COUREUR + "\"}");
                    });

            assertThat(reponses.get(0).statusCode()).as("changement " + i).isIn(200, 404);
            assertThat(reponses.get(1).statusCode()).as("suppression " + i).isLessThan(500);
            Map<String, Object> compte = jdbc.queryForMap("select * from compte where id = ?", cible);
            if (compte.get("empreinte_mot_de_passe") == null) {
                assertThat(compte.get("pseudo")).as("anonymisé " + i).isEqualTo(ANONYME);
                assertThat(compte.get("role")).as("anonymisé " + i).isEqualTo("COUREUR");
            } else {
                assertThat(compte.get("pseudo")).as("intact " + i).isEqualTo(pseudo);
                assertThat(compte.get("role")).as("intact " + i).isEqualTo("ADMIN");
            }
            assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                    cible)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CA13 (d) : passage-admin et fin de la Course du coureur simultanés : 200 ou 409, jamais de 500 (20 répétitions)")
    void ca13_changement_et_fin_de_course() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = coureur("Finisseur" + i);
            UUID course = insererCourse("Course fin " + i, "2026-10-09", "EN_COURS",
                    Instant.parse("2026-10-09T13:30:00Z"));
            inscrire(course, cible, 1, "VAINQUEUR");
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return promouvoir(patron, cible);
                    },
                    () -> {
                        depart.await();
                        horsTransaction().update("update course set statut = 'TERMINEE' where id = ?", course);
                        return null;
                    });

            assertThat(reponses.get(0).statusCode()).as("répétition " + i).isIn(200, 409);
            assertThat(roleDe(cible)).isEqualTo(reponses.get(0).statusCode() == 200 ? "ADMIN" : "COUREUR");
        }
    }

    @Test
    @DisplayName("CA13 (e) : connexion finalisée pendant le changement (après l'ouverture de sa session) : aucune session ne conserve l'ancien rôle de coureur")
    void ca13_connexion_pendant_le_changement() throws Exception {
        UUID cible = coureur("CibleConnexion");
        doAnswer(invocation -> {
            assertThat(promouvoir(patron, cible).statusCode()).isEqualTo(200);
            return invocation.callRealMethod();
        }).when(consulterCompteConnecte).estConnectable(cible);

        HttpResponse<String> reponse = connexion("CibleConnexion", MOT_DE_PASSE_COUREUR);

        assertThat(roleDe(cible)).isEqualTo("ADMIN");
        assertAucuneSessionCoureur(reponse);
    }

    @Test
    @DisplayName("CA13 (e) : connexion authentifiée avant le changement mais session ouverte après son commit : 401 générique, aucune session avec l'ancien rôle")
    void ca13_connexion_authentifiee_avant_le_changement() throws Exception {
        UUID cible = coureur("CibleFenetre");
        doAnswer(invocation -> {
            Object compte = invocation.callRealMethod();
            assertThat(promouvoir(patron, cible).statusCode()).isEqualTo(200);
            return compte;
        }).when(connecter).executer("CibleFenetre", MOT_DE_PASSE_COUREUR);

        HttpResponse<String> reponse = connexion("CibleFenetre", MOT_DE_PASSE_COUREUR);

        assertThat(roleDe(cible)).isEqualTo("ADMIN");
        assertThat(reponse.statusCode()).isEqualTo(401);
        assertAucuneSessionCoureur(reponse);
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : panne à la mise à jour : 500 ERREUR_INTERNE sans détail technique, rôle inchangé, sessions de la cible valides")
    void ca14_panne_pendant_le_changement() throws Exception {
        UUID cible = coureur("CiblePanne");
        Session session = api.ouvrir("CiblePanne", MOT_DE_PASSE_COUREUR);
        doThrow(new IllegalStateException("panne simulée secret-technique")).when(depotComptes).mettreAJour(any());

        HttpResponse<String> reponse = promouvoir(patron, cible);

        assertThat(reponse.statusCode()).isEqualTo(500);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("secret-technique").doesNotContain("IllegalState");
        assertThat(roleDe(cible)).isEqualTo("COUREUR");
        assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(session, "/api/coureur/inscriptions").statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : journal d'un succès, d'un refus 409 puis d'un 404 : « Coureur passé en admin (compte <id> par <id>) » une fois, « Changement de rôle refusé (compte <id>, motif …, 2 course(s)) » une fois, rien pour le 404 ; ni pseudo, ni nom de Course, ni mot de passe en INFO ou plus")
    void ca15_journal() throws Exception {
        UUID reussi = coureur("Zorglub");
        UUID refuse = coureur("Zorglub2");
        inscrire(p, refuse, 1, "EN_COURSE");
        inscrire(p2, refuse, 1, "EN_COURSE");

        HttpResponse<String> succes = promouvoir(patron, reussi);
        HttpResponse<String> conflit = promouvoir(patron, refuse);
        HttpResponse<String> introuvable = promouvoir(patron, UUID.randomUUID());
        int avant404 = journal.marque();
        HttpResponse<String> introuvable2 = promouvoir(patron, "pas-un-uuid");

        assertThat(succes.statusCode()).isEqualTo(200);
        assertThat(conflit.statusCode()).isEqualTo(409);
        assertThat(introuvable.statusCode()).isEqualTo(404);
        assertThat(lignes("Coureur passé en admin")).containsExactly("Coureur passé en admin (compte " + reussi
                + " par " + idPatron + ")");
        assertThat(lignes("Changement de rôle refusé")).containsExactly("Changement de rôle refusé (compte " + refuse
                + ", motif COUREUR_INSCRIT_COURSE_EN_PREPARATION, 2 course(s))");
        assertThat(journal.applicatifsDepuis(avant404).stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Coureur passé") || m.startsWith("Changement de rôle")).toList()).isEmpty();
        assertThat(introuvable2.statusCode()).isEqualTo(404);

        assertThat(introuvable.body()).doesNotContain("Zorglub").doesNotContain(NOM_P).doesNotContain(NOM_P2);
        assertThat(succes.body()).doesNotContain(NOM_P);
        assertThat(conflit.body()).contains(NOM_P).contains(NOM_P2).doesNotContain("Zorglub");
        for (ILoggingEvent evenement : journal.evenements()) {
            String texte = evenement.getFormattedMessage() + " " + evenement.getMDCPropertyMap()
                    + (evenement.getThrowableProxy() == null ? "" : evenement.getThrowableProxy().getMessage());
            assertThat(texte).as(evenement.getLevel() + " " + evenement.getLoggerName())
                    .doesNotContain(MOT_DE_PASSE_COUREUR).doesNotContain(MOT_DE_PASSE_ADMIN)
                    .doesNotContain(MOT_DE_PASSE_PATRON).doesNotContain(MOT_DE_PASSE_BENEVOLE);
            if (evenement.getLevel().isGreaterOrEqual(Level.INFO)
                    && evenement.getLoggerName().startsWith("fr.backyard.tracker")) {
                assertThat(texte).as("INFO+ " + evenement.getLoggerName()).doesNotContain("Zorglub")
                        .doesNotContain(NOM_P).doesNotContain(NOM_P2).doesNotContain(NOM_C).doesNotContain(NOM_T);
            }
        }
    }

    @Test
    @DisplayName("CA15 : le refus d'un ADMIN simple (403) n'écrit aucune ligne de ce cas d'usage dans le journal")
    void ca15_aucune_ligne_pour_un_403() throws Exception {
        UUID cible = coureur("CibleSilence");
        int marque = journal.marque();

        assertThat(promouvoir(nadia, cible).statusCode()).isEqualTo(403);

        assertThat(journal.applicatifsDepuis(marque).stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Coureur passé") || m.startsWith("Changement de rôle")).toList()).isEmpty();
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : aucun nouveau changeset (0002 à 0011-compte-actif) après un passage en admin ; passage-benevole inchangé pour Nadia et Patron (200) ; POST sur /api/administration/coureurs : 405 ou 404 sans effet (9.5 CA9)")
    void ca16_schema_inchange_et_non_regression() throws Exception {
        UUID cible = coureur("CibleSchema");
        assertThat(promouvoir(patron, cible).statusCode()).isEqualTo(200);
        UUID parNadia = coureur("ParNadia");
        UUID parPatron = coureur("ParPatron");
        assertThat(promouvoirBenevole(nadia, parNadia).statusCode()).isEqualTo(200);
        assertThat(promouvoirBenevole(patron, parPatron).statusCode()).isEqualTo(200);
        assertThat(roleDe(parNadia)).isEqualTo("BENEVOLE");
        assertThat(roleDe(parPatron)).isEqualTo("BENEVOLE");
        List<Map<String, Object>> avant = etatBase();

        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course",
                        "0006-affectation-benevole", "0007-inscription", "0008-demarrage-course", "0009-passage",
                        "0010-abandon-inscription", "0011-compte-actif");
        HttpResponse<String> post = api.requete("POST", RECHERCHE, nadia.entetes(), null, null);
        assertThat(post.statusCode()).isIn(404, 405);
        assertThat(api.requete("GET", RECHERCHE + "/" + UUID.randomUUID(), nadia.enteteLecture(), null, null)
                .statusCode()).isEqualTo(404);
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(json.readTree(lire(nadia, RECHERCHE + "?q=Alice").body()).get("coureurs")).hasSize(1);
    }

    @Test
    @DisplayName("CA16 : passage-benevole reste soumis aux mêmes 404 uniformes et 403 avant validation (9.6 CA20 / CA21) après l'ajout de la règle ADMIN_MASTER")
    void ca16_passage_benevole_toujours_ouvert_aux_admins() throws Exception {
        assertProbleme(promouvoirBenevole(nadia, UUID.randomUUID()), 404, "Introuvable", "COMPTE_INTROUVABLE",
                "Ce compte est introuvable.");
        assertProbleme(promouvoirBenevole(alice, "pas-un-uuid"), 403, "Accès refusé", "ACCES_REFUSE",
                "Vous n'avez pas les droits nécessaires.");
        assertProbleme(promouvoirBenevole(leo, UUID.randomUUID()), 403, "Accès refusé", "ACCES_REFUSE",
                "Vous n'avez pas les droits nécessaires.");
        assertThat(roleDe(idAlice)).isEqualTo("COUREUR");
    }

    private void poserPassages(UUID inscription) {
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) "
                + "values (?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscription,
                Timestamp.from(Instant.parse("2026-10-08T08:40:00Z")));
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) "
                + "values (?, ?, 2, null, 'CORRECTION')", UUID.randomUUID(), inscription);
    }

    // ---------------------------------------------------------------- utilitaires

    private String chemin(Object id) {
        return RECHERCHE + "/" + id + "/passage-admin";
    }

    private String cheminBenevole(Object id) {
        return RECHERCHE + "/" + id + "/passage-benevole";
    }

    private HttpResponse<String> promouvoir(Session session, Object id) throws Exception {
        return api.requete("POST", chemin(id), session.entetes(), null, null);
    }

    private HttpResponse<String> promouvoirBenevole(Session session, Object id) throws Exception {
        return api.requete("POST", cheminBenevole(id), session.entetes(), null, null);
    }

    private HttpResponse<String> supprimerAdmin(Session session, Object id) throws Exception {
        return api.requete("DELETE", ADMINS + "/" + id, session.entetes(), null, null);
    }

    private List<JsonNode> dansLaListe(Session session, String chemin, UUID id) throws Exception {
        List<JsonNode> trouves = new ArrayList<>();
        json.readTree(lire(session, chemin).body()).forEach(compte -> {
            if (compte.get("id").asString().equals(id.toString())) {
                trouves.add(compte);
            }
        });
        return trouves;
    }

    private void assertAucuneSessionCoureur(HttpResponse<String> reponse) throws Exception {
        List<String> sessions = reponse.headers().allValues("set-cookie").stream()
                .filter(cookie -> cookie.startsWith("JSESSIONID="))
                .map(cookie -> cookie.substring("JSESSIONID=".length(),
                        cookie.indexOf(';') < 0 ? cookie.length() : cookie.indexOf(';')))
                .filter(valeur -> !valeur.isEmpty()).toList();
        for (String identifiant : sessions) {
            HttpResponse<String> espaceCoureur = lire(new Session(identifiant, "x"), "/api/coureur/inscriptions");
            assertThat(espaceCoureur.statusCode()).as("espace coureur avec une session ouverte à l'ancien rôle")
                    .isIn(401, 403);
        }
    }

    private void assertConflit(HttpResponse<String> reponse, UUID cible, String code, String detail,
                               List<UUID> idsCourses, List<String> nomsCourses) {
        assertThat(reponse.statusCode()).isEqualTo(409);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("application/problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        Set<String> champs = new HashSet<>();
        corps.propertyNames().forEach(champs::add);
        assertThat(champs).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code",
                "courses");
        assertThat(corps.get("type").asString()).isEqualTo("about:blank");
        assertThat(corps.get("title").asString()).isEqualTo("Conflit");
        assertThat(corps.get("status").asInt()).isEqualTo(409);
        assertThat(corps.get("detail").asString()).isEqualTo(detail);
        assertThat(corps.get("code").asString()).isEqualTo(code);
        // Écart signalé : la spec donne "/api/administration/coureurs/<id>" ; le comportement réel est le chemin complet.
        assertThat(corps.get("instance").asString()).isEqualTo(chemin(cible));
        JsonNode courses = corps.get("courses");
        assertThat(courses).hasSize(idsCourses.size());
        for (int i = 0; i < idsCourses.size(); i++) {
            assertThat(courses.get(i).get("id").asString()).isEqualTo(idsCourses.get(i).toString());
            assertThat(courses.get(i).get("nom").asString()).isEqualTo(nomsCourses.get(i));
            Set<String> champsCourse = new HashSet<>();
            courses.get(i).propertyNames().forEach(champsCourse::add);
            assertThat(champsCourse).containsExactlyInAnyOrder("id", "nom");
        }
    }

    private void assertProbleme(HttpResponse<String> reponse, int statut, String titre, String code, String detail) {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("application/problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo(code);
        assertThat(corps.get("title").asString()).isEqualTo(titre);
        assertThat(corps.get("status").asInt()).isEqualTo(statut);
        assertThat(corps.get("detail").asString()).isEqualTo(detail);
    }

    private static String normaliser(String corps) {
        return corps.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "ID")
                .replace("pas-un-uuid", "ID");
    }

    private UUID coureur(String pseudo) {
        return insererCompte(pseudo, "COUREUR", empreinteCoureur);
    }

    private UUID insererCompte(String pseudo, String role, String empreinte) {
        compteur++;
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte, role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z").plusSeconds(60L * compteur)));
        return id;
    }

    private UUID insererCourse(String nom, String date, String statut, Instant demarreeLe) {
        return insererCourse(UUID.randomUUID(), nom, date, statut, demarreeLe);
    }

    private UUID insererCourse(UUID id, String nom, String date, String statut, Instant demarreeLe) {
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf(date), statut,
                demarreeLe == null ? null : Timestamp.from(demarreeLe));
        return id;
    }

    private UUID inscrire(UUID course, UUID compte, int dossard, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, ?, ?, ?)", id, course, compte, dossard, UUID.randomUUID().toString(), statut);
        return id;
    }

    private String roleDe(UUID compte) {
        return jdbc.queryForObject("select role from compte where id = ?", String.class, compte);
    }

    private List<Map<String, Object>> etatBase() {
        List<Map<String, Object>> etat = new ArrayList<>(jdbc.queryForList("select * from compte order by id"));
        etat.addAll(jdbc.queryForList("select * from affectation_benevole order by course_id, benevole_id"));
        etat.addAll(jdbc.queryForList("select * from inscription order by id"));
        return etat;
    }

    private HttpResponse<String> lire(Session session, String chemin) throws Exception {
        return api.requete("GET", chemin, session.enteteLecture(), null, null);
    }

    private HttpResponse<String> connexion(String pseudo, String motDePasse) throws Exception {
        String jeton = api.jetonValide();
        return api.requete("POST", "/api/connexion", Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton),
                "application/json", "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}");
    }

    private HttpResponse<String> affecter(Session session, UUID course, UUID... benevoles) throws Exception {
        ObjectNode corps = json.createObjectNode();
        var tableau = corps.putArray("benevoleIds");
        for (UUID benevole : benevoles) {
            tableau.add(benevole.toString());
        }
        return api.requete("PUT", COURSES + "/" + course + "/benevoles", session.entetes(), "application/json",
                corps.toString());
    }

    private JdbcTemplate horsTransaction() {
        return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private List<String> lignes(String prefixe) {
        return journal.evenementsApplicatifs().stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith(prefixe)).toList();
    }

    @SafeVarargs
    private final List<HttpResponse<String>> enParallele(CountDownLatch depart,
                                                         Callable<HttpResponse<String>>... actions) throws Exception {
        ExecutorService executeur = Executors.newFixedThreadPool(actions.length);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (Callable<HttpResponse<String>> action : actions) {
                futures.add(executeur.submit(action));
            }
            Thread.sleep(150);
            depart.countDown();
            List<HttpResponse<String>> reponses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                reponses.add(future.get());
            }
            return reponses;
        } finally {
            executeur.shutdownNow();
        }
    }
}
