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
 * Contrat de l'incrément 9.6b : POST /api/administration/coureurs/{id}/passage-benevole (CA15 à CA25). Les états sont
 * lus en base ; les Inscriptions sont posées par SQL, sauf là où l'API est elle-même l'objet du scénario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class PassageEnBenevoleIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE_COUREUR = "un-mot-de-passe-12";
    static final String BENEVOLES = "/api/administration/benevoles";
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

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : Nadia passe Coureur1 (sans Inscription, deux sessions) en bénévole : 200 CompteReponse BENEVOLE actif, date d'origine, sans empreinte ; seul le rôle change en base ; sessions de Coureur1 fermées (401), celles de Léo et d'un autre appareil de Nadia valides ; reconnexion avec le même mot de passe en BENEVOLE, /api/benevole/courses vide")
    void ca15_passage_nominal_et_sessions() throws Exception {
        UUID coureur1 = coureur("Coureur1");
        Session s1 = api.ouvrir("Coureur1", MOT_DE_PASSE_COUREUR);
        Session s2 = api.ouvrir("Coureur1", MOT_DE_PASSE_COUREUR);
        Session s4 = api.ouvrir("Nadia", MOT_DE_PASSE_ADMIN);
        assertThat(lire(s1, "/api/coureur/inscriptions").statusCode()).isEqualTo(200);
        Map<String, Object> avant = jdbc.queryForMap("select * from compte where id = ?", coureur1);
        Instant creeLe = ((Timestamp) avant.get("cree_le")).toInstant();

        HttpResponse<String> reponse = promouvoir(nadia, coureur1);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                valeur -> assertThat(valeur).contains("application/json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("id").asString()).isEqualTo(coureur1.toString());
        assertThat(corps.get("pseudo").asString()).isEqualTo("Coureur1");
        assertThat(corps.get("role").asString()).isEqualTo("BENEVOLE");
        assertThat(corps.get("actif").asBoolean()).isTrue();
        assertThat(Instant.parse(corps.get("creeLe").asString())).isEqualTo(creeLe);
        assertThat(reponse.body()).doesNotContain("empreinte").doesNotContain("motDePasse");
        Map<String, Object> apres = jdbc.queryForMap("select * from compte where id = ?", coureur1);
        Map<String, Object> attendu = new java.util.HashMap<>(avant);
        attendu.put("role", "BENEVOLE");
        assertThat(apres).isEqualTo(attendu);

        for (Session fermee : List.of(s1, s2)) {
            assertProbleme(lire(fermee, "/api/comptes/moi"), 401, "Authentification requise", "NON_AUTHENTIFIE",
                    "Vous devez être connecté.");
        }
        assertThat(lire(leo, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(s4, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(nadia, "/api/comptes/moi").statusCode()).isEqualTo(200);

        registre.vider();
        HttpResponse<String> connexion = connexion("Coureur1", MOT_DE_PASSE_COUREUR);
        assertThat(connexion.statusCode()).isEqualTo(200);
        assertThat(json.readTree(connexion.body()).get("role").asString()).isEqualTo("BENEVOLE");
        Session nouvelle = new Session(ClientHttp.valeurCookie(connexion, "JSESSIONID"), api.jetonValide());
        HttpResponse<String> courses = lire(nouvelle, "/api/benevole/courses");
        assertThat(courses.statusCode()).isEqualTo(200);
        assertThat(json.readTree(courses.body())).isEmpty();
    }

    @Test
    @DisplayName("CA15 : Patron (admin master) obtient le même 200 sur un autre coureur ; un BENEVOLE créé n'est ni ADMIN ni ADMIN_MASTER")
    void ca15_admin_master_obtient_le_meme_resultat() throws Exception {
        UUID coureur = coureur("Coureur1b");

        HttpResponse<String> reponse = promouvoir(patron, coureur);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body()).get("role").asString()).isEqualTo("BENEVOLE");
        assertThat(roleDe(coureur)).isEqualTo("BENEVOLE");
        assertThat(lire(patron, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA15 : le compteur d'échecs de connexion n'est pas remis à zéro par le changement : 3 échecs avant, 2 après, puis 429 même avec le bon mot de passe")
    void ca15_compteur_d_echecs_conserve() throws Exception {
        UUID coureur = coureur("Coureur1c");
        registre.vider();
        for (int i = 0; i < 3; i++) {
            assertThat(connexion("Coureur1c", "mauvais-mot-de-passe-1").statusCode()).as("avant " + i).isEqualTo(401);
        }

        assertThat(promouvoir(nadia, coureur).statusCode()).isEqualTo(200);

        for (int i = 0; i < 2; i++) {
            assertThat(connexion("Coureur1c", "mauvais-mot-de-passe-1").statusCode()).as("après " + i).isEqualTo(401);
        }
        assertThat(connexion("Coureur1c", MOT_DE_PASSE_COUREUR).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : Coureur2 inscrit à P et P2 (EN_PREPARATION) : 409 COUREUR_INSCRIT_COURSE_EN_PREPARATION au corps exact (detail au pluriel, courses triées par nom), rôle, sessions et Inscriptions inchangés ; après désinscription aux deux : 200")
    void ca16_refus_en_preparation_puis_desinscription() throws Exception {
        UUID coureur2 = coureur("Coureur2");
        Session session = api.ouvrir("Coureur2", MOT_DE_PASSE_COUREUR);
        UUID inscriptionP = inscrire(p, coureur2, 1, "EN_COURSE");
        UUID inscriptionP2 = inscrire(p2, coureur2, 1, "EN_COURSE");
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> refus = promouvoir(nadia, coureur2);

        assertConflit(refus, coureur2, "COUREUR_INSCRIT_COURSE_EN_PREPARATION",
                "Ce coureur est inscrit aux courses « " + NOM_P2 + " », « " + NOM_P + " » qui n'ont pas démarré. "
                        + "Il doit d'abord se désinscrire.",
                List.of(p2, p), List.of(NOM_P2, NOM_P));
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);

        assertThat(api.requete("DELETE", "/api/coureur/inscriptions/" + inscriptionP, session.entetes(), null, null)
                .statusCode()).isEqualTo(204);
        assertThat(api.requete("DELETE", "/api/coureur/inscriptions/" + inscriptionP2, session.entetes(), null, null)
                .statusCode()).isEqualTo(204);
        assertThat(promouvoir(nadia, coureur2).statusCode()).isEqualTo(200);
        assertThat(roleDe(coureur2)).isEqualTo("BENEVOLE");
    }

    @Test
    @DisplayName("CA16 : une seule Course EN_PREPARATION : detail au singulier ; 409 sans pseudo ni identifiant de Compte dans le detail")
    void ca16_detail_au_singulier() throws Exception {
        UUID coureur = coureur("Coureur2s");
        inscrire(p, coureur, 1, "EN_COURSE");

        HttpResponse<String> refus = promouvoir(nadia, coureur);

        assertConflit(refus, coureur, "COUREUR_INSCRIT_COURSE_EN_PREPARATION",
                "Ce coureur est inscrit à la course « " + NOM_P + " » qui n'a pas démarré. "
                        + "Il doit d'abord se désinscrire.", List.of(p), List.of(NOM_P));
        assertThat(json.readTree(refus.body()).get("detail").asString()).doesNotContain("Coureur2s")
                .doesNotContain(coureur.toString());
    }

    @Test
    @DisplayName("CA16 : courses du 409 triées par nom sans tenir compte de la casse, puis par identifiant à nom égal")
    void ca16_tri_insensible_a_la_casse_puis_identifiant() throws Exception {
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

        HttpResponse<String> refus = promouvoir(nadia, coureur);

        assertThat(refus.statusCode()).isEqualTo(409);
        JsonNode courses = json.readTree(refus.body()).get("courses");
        List<String> ids = new ArrayList<>();
        courses.forEach(course -> ids.add(course.get("id").asString()));
        assertThat(ids).containsExactly(alpha.toString(), bravo.toString(), doublonBas.toString(),
                doublonHaut.toString(), zeta.toString());
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : Coureur3 inscrit à C (EN_COURSE, puis ABANDON, puis VAINQUEUR) et à P : à chaque fois 409 COUREUR_INSCRIT_COURSE_EN_COURS, courses = [C] sans P, detail au singulier, rien modifié ; C terminée et P désinscrite : 200")
    void ca17_refus_en_cours_prioritaire() throws Exception {
        UUID coureur3 = coureur("Coureur3");
        UUID inscriptionC = inscrire(c, coureur3, 1, "EN_COURSE");
        UUID inscriptionP = inscrire(p, coureur3, 1, "EN_COURSE");
        Session session = api.ouvrir("Coureur3", MOT_DE_PASSE_COUREUR);

        for (String statut : List.of("EN_COURSE", "ABANDON", "VAINQUEUR")) {
            jdbc.update("update inscription set statut = ? where id = ?", statut, inscriptionC);
            List<Map<String, Object>> avant = etatBase();

            HttpResponse<String> refus = promouvoir(nadia, coureur3);

            assertConflit(refus, coureur3, "COUREUR_INSCRIT_COURSE_EN_COURS",
                    "Ce coureur participe à la course en cours « " + NOM_C + " ». Réessayez à la fin de la course.",
                    List.of(c), List.of(NOM_C));
            assertThat(refus.body()).doesNotContain(NOM_P);
            assertThat(etatBase()).as(statut).isEqualTo(avant);
            assertThat(lire(session, "/api/comptes/moi").statusCode()).as(statut).isEqualTo(200);
        }

        jdbc.update("update course set statut = 'TERMINEE' where id = ?", c);
        assertThat(promouvoir(nadia, coureur3).statusCode()).isEqualTo(409);
        jdbc.update("delete from inscription where id = ?", inscriptionP);
        assertThat(promouvoir(nadia, coureur3).statusCode()).isEqualTo(200);
        assertThat(roleDe(coureur3)).isEqualTo("BENEVOLE");
    }

    @Test
    @DisplayName("CA17 : deux Courses EN_COURS : detail au pluriel avec les deux noms triés")
    void ca17_pluriel_en_cours() throws Exception {
        UUID coureur = coureur("Coureur3b");
        UUID c2 = insererCourse("Alpha en cours", "2026-10-09", "EN_COURS", Instant.parse("2026-10-09T13:00:00Z"));
        inscrire(c, coureur, 2, "ABANDON");
        inscrire(c2, coureur, 2, "EN_COURSE");

        HttpResponse<String> refus = promouvoir(nadia, coureur);

        assertConflit(refus, coureur, "COUREUR_INSCRIT_COURSE_EN_COURS",
                "Ce coureur participe aux courses en cours « Alpha en cours », « " + NOM_C + " ». "
                        + "Réessayez à la fin des courses.", List.of(c2, c), List.of("Alpha en cours", NOM_C));
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : Coureur4 n'ayant couru que T (TERMINEE, dossard 5, Passages SCAN) : 200 ; Inscription, Passages et résultats de T inchangés, le suivi public affiche toujours son pseudo avec le dossard 5")
    void ca18_inscription_terminee_conservee() throws Exception {
        UUID coureur4 = coureur("Coureur4");
        UUID inscription = inscrire(t, coureur4, 5, "VAINQUEUR");
        jdbc.update("insert into passage (id, inscription_id, numero_boucle, scanne_le, origine) "
                + "values (?, ?, 1, ?, 'SCAN')", UUID.randomUUID(), inscription,
                Timestamp.from(Instant.parse("2026-10-08T08:40:00Z")));
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> passagesAvant = jdbc.queryForList("select * from passage order by id");
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");

        HttpResponse<String> reponse = promouvoir(nadia, coureur4);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select * from passage order by id")).isEqualTo(passagesAvant);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(jdbc.queryForList("select origine from passage", String.class)).containsOnly("SCAN");
        JsonNode suivi = json.readTree(api.requete("GET", "/api/courses/" + t + "/suivi", Map.of(), null, null).body());
        assertThat(suivi.get("vainqueurs")).hasSize(1);
        assertThat(suivi.get("vainqueurs").get(0).get("pseudo").asString()).isEqualTo("Coureur4");
        assertThat(suivi.get("vainqueurs").get(0).get("dossard").asInt()).isEqualTo(5);
        JsonNode inscrits = json.readTree(lire(nadia, COURSES + "/" + t + "/inscriptions").body()).get("inscrits");
        assertThat(inscrits).hasSize(1);
        assertThat(inscrits.get(0).get("pseudo").asString()).isEqualTo("Coureur4");
    }

    @Test
    @DisplayName("CA18 : Coureur4 inscrit à T et à P : 409 COUREUR_INSCRIT_COURSE_EN_PREPARATION, courses = [P] sans T")
    void ca18_terminee_absente_du_refus() throws Exception {
        UUID coureur = coureur("Coureur4b");
        inscrire(t, coureur, 5, "VAINQUEUR");
        inscrire(p, coureur, 1, "EN_COURSE");

        HttpResponse<String> refus = promouvoir(nadia, coureur);

        assertConflit(refus, coureur, "COUREUR_INSCRIT_COURSE_EN_PREPARATION",
                "Ce coureur est inscrit à la course « " + NOM_P + " » qui n'a pas démarré. "
                        + "Il doit d'abord se désinscrire.", List.of(p), List.of(NOM_P));
        assertThat(refus.body()).doesNotContain(NOM_T);
        assertThat(roleDe(coureur)).isEqualTo("COUREUR");
    }

    // ---------------------------------------------------------------- CA19

    @Test
    @DisplayName("CA19 : Coureur5 promu : présent une fois (actif) dans la liste des bénévoles, absent de la recherche de coureurs, affectable à P (fiche de P), désactivable puis réactivable (204), et son ancienne session coureur ne peut plus s'inscrire (401)")
    void ca19_apparition_dans_les_listes() throws Exception {
        UUID coureur5 = coureur("Coureur5");
        Session ancienne = api.ouvrir("Coureur5", MOT_DE_PASSE_COUREUR);
        assertThat(json.readTree(lire(nadia, RECHERCHE + "?q=Coureur5").body()).get("coureurs")).hasSize(1);

        assertThat(promouvoir(nadia, coureur5).statusCode()).isEqualTo(200);

        JsonNode liste = json.readTree(lire(nadia, BENEVOLES).body());
        List<JsonNode> trouves = new ArrayList<>();
        liste.forEach(compte -> {
            if (compte.get("id").asString().equals(coureur5.toString())) {
                trouves.add(compte);
            }
        });
        assertThat(trouves).hasSize(1);
        assertThat(trouves.get(0).get("actif").asBoolean()).isTrue();
        assertThat(trouves.get(0).get("role").asString()).isEqualTo("BENEVOLE");
        assertThat(Instant.parse(trouves.get(0).get("creeLe").asString())).isEqualTo(
                jdbc.queryForObject("select cree_le from compte where id = ?", Timestamp.class, coureur5).toInstant());
        JsonNode recherche = json.readTree(lire(nadia, RECHERCHE + "?q=Coureur5").body());
        assertThat(recherche.get("coureurs")).isEmpty();

        assertThat(affecter(nadia, p, coureur5).statusCode()).isEqualTo(200);
        JsonNode fiche = json.readTree(lire(nadia, COURSES + "/" + p).body());
        List<String> affectes = new ArrayList<>();
        fiche.get("benevoleIds").forEach(id -> affectes.add(id.asString()));
        assertThat(affectes).containsExactly(coureur5.toString());

        assertThat(api.requete("POST", BENEVOLES + "/" + coureur5 + "/desactivation", nadia.entetes(), null, null)
                .statusCode()).isEqualTo(204);
        assertThat(api.requete("POST", BENEVOLES + "/" + coureur5 + "/reactivation", nadia.entetes(), null, null)
                .statusCode()).isEqualTo(204);

        HttpResponse<String> inscription = api.requete("POST", "/api/coureur/courses/" + p2 + "/inscriptions",
                ancienne.entetes(), null, null);
        assertThat(inscription.statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                coureur5)).isZero();
    }

    // ---------------------------------------------------------------- CA20

    @Test
    @DisplayName("CA20 : 404 COMPTE_INTROUVABLE de corps identique (à l'identifiant près) pour : identifiant inconnu, pas-un-uuid, Léo, Nadia, Patron, l'appelant, un coureur anonymisé, un coureur inactif, un coureur déjà promu ; rien modifié, sessions intactes")
    void ca20_cibles_invalides_reponse_identique() throws Exception {
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
        assertThat(promouvoir(nadia, promu).statusCode()).isEqualTo(200);
        registre.vider();
        List<Map<String, Object>> avant = etatBase();

        Map<String, HttpResponse<String>> reponses = new java.util.LinkedHashMap<>();
        reponses.put("inconnu", promouvoir(nadia, UUID.randomUUID().toString()));
        reponses.put("pas-un-uuid", promouvoir(nadia, "pas-un-uuid"));
        reponses.put("bénévole", promouvoir(nadia, idLeo));
        reponses.put("admin", promouvoir(patron, idNadia));
        reponses.put("admin master", promouvoir(nadia, idPatron));
        reponses.put("appelant admin", promouvoir(nadia, idNadia));
        reponses.put("appelant admin master", promouvoir(patron, idPatron));
        reponses.put("anonymisé", promouvoir(nadia, anonymise));
        reponses.put("inactif", promouvoir(nadia, inactif));
        reponses.put("déjà promu", promouvoir(nadia, promu));

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

    // ---------------------------------------------------------------- CA21

    @Test
    @DisplayName("CA21 : anonyme : 401 NON_AUTHENTIFIE ; Léo (bénévole) et Alice (coureuse) : 403 ACCES_REFUSE identique pour une cible existante, un identifiant inconnu et un non-UUID (avant résolution) ; cible inchangée")
    void ca21_roles_non_autorises() throws Exception {
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
    @DisplayName("CA21 : jeton CSRF absent ou invalide : 403 CSRF_INVALIDE pour Nadia, base inchangée, sessions du coureur valides")
    void ca21_csrf_absent_ou_invalide() throws Exception {
        UUID cible = coureur("CibleCsrf");
        Session session = api.ouvrir("CibleCsrf", MOT_DE_PASSE_COUREUR);
        List<Map<String, Object>> avant = etatBase();

        HttpResponse<String> sansJeton = api.requete("POST", chemin(cible),
                Map.of("Cookie", "JSESSIONID=" + nadia.id()), null, null);
        HttpResponse<String> invalide = api.requete("POST", chemin(cible), Map.of("Cookie",
                "JSESSIONID=" + nadia.id() + "; XSRF-TOKEN=" + nadia.xsrf(), "X-XSRF-TOKEN", "invalide"), null, null);

        for (HttpResponse<String> reponse : List.of(sansJeton, invalide)) {
            assertProbleme(reponse, 403, "Accès refusé", "CSRF_INVALIDE", "Jeton CSRF absent ou invalide.");
        }
        assertThat(etatBase()).isEqualTo(avant);
        assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA21 : Nadia et Patron obtiennent 200 ; GET, PUT, PATCH, DELETE sur le chemin : 404 ou 405, jamais 2xx, rien modifié ; un corps de requête est ignoré")
    void ca21_methodes_et_corps() throws Exception {
        UUID cible = coureur("CibleMethodes");
        List<Map<String, Object>> avant = etatBase();

        for (String methode : List.of("GET", "PUT", "PATCH", "DELETE")) {
            HttpResponse<String> reponse = api.requete(methode, chemin(cible), nadia.entetes(), null, null);
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        assertThat(etatBase()).isEqualTo(avant);

        HttpResponse<String> avecCorps = api.requete("POST", chemin(cible), nadia.entetes(), "application/json",
                "{\"role\":\"ADMIN\",\"n'importe\":\"quoi\"}");
        assertThat(avecCorps.statusCode()).isEqualTo(200);
        assertThat(json.readTree(avecCorps.body()).get("role").asString()).isEqualTo("BENEVOLE");
        UUID autre = coureur("CibleMethodes2");
        assertThat(promouvoir(patron, autre).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA22

    @Test
    @DisplayName("CA22 : panne à la mise à jour : 500 ERREUR_INTERNE sans détail technique, rôle inchangé, sessions de la cible valides")
    void ca22_panne_pendant_le_changement() throws Exception {
        UUID cible = coureur("CiblePanne");
        Session session = api.ouvrir("CiblePanne", MOT_DE_PASSE_COUREUR);
        doThrow(new IllegalStateException("panne simulée secret-technique")).when(depotComptes).mettreAJour(any());

        HttpResponse<String> reponse = promouvoir(nadia, cible);

        assertThat(reponse.statusCode()).isEqualTo(500);
        assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("ERREUR_INTERNE");
        assertThat(reponse.body()).doesNotContain("secret-technique").doesNotContain("IllegalState");
        assertThat(roleDe(cible)).isEqualTo("COUREUR");
        assertThat(lire(session, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(lire(session, "/api/coureur/inscriptions").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA22 : connexion finalisée pendant le changement (après l'ouverture de sa session) : aucune session ne conserve l'ancien rôle de coureur")
    void ca22_connexion_pendant_le_changement() throws Exception {
        UUID cible = coureur("CibleConnexion");
        doAnswer(invocation -> {
            assertThat(promouvoir(nadia, cible).statusCode()).isEqualTo(200);
            return invocation.callRealMethod();
        }).when(consulterCompteConnecte).estConnectable(cible);

        HttpResponse<String> reponse = connexion("CibleConnexion", MOT_DE_PASSE_COUREUR);

        assertThat(roleDe(cible)).isEqualTo("BENEVOLE");
        assertAucuneSessionCoureur(reponse);
    }

    @Test
    @DisplayName("CA22 : connexion authentifiée avant le changement mais session ouverte après son commit : aucune session ne conserve l'ancien rôle de coureur")
    void ca22_connexion_authentifiee_avant_le_changement() throws Exception {
        UUID cible = coureur("CibleFenetre");
        doAnswer(invocation -> {
            Object compte = invocation.callRealMethod();
            assertThat(promouvoir(nadia, cible).statusCode()).isEqualTo(200);
            return compte;
        }).when(connecter).executer("CibleFenetre", MOT_DE_PASSE_COUREUR);

        HttpResponse<String> reponse = connexion("CibleFenetre", MOT_DE_PASSE_COUREUR);

        assertThat(roleDe(cible)).isEqualTo("BENEVOLE");
        assertAucuneSessionCoureur(reponse);
    }

    // ---------------------------------------------------------------- CA23

    @Test
    @DisplayName("CA23 : journal d'un succès, d'un refus 409 puis d'un 404 : « Coureur passé en bénévole (compte <id> par <id>) » une fois, « Changement de rôle refusé (compte <id>, motif …, 2 course(s)) » une fois, rien pour le 404 ; ni pseudo, ni nom de Course, ni mot de passe en INFO ou plus ; seul le 409 contient des noms de Courses")
    void ca23_journal() throws Exception {
        UUID reussi = coureur("Zorglub");
        UUID refuse = coureur("Zorglub2");
        inscrire(p, refuse, 1, "EN_COURSE");
        inscrire(p2, refuse, 1, "EN_COURSE");

        HttpResponse<String> succes = promouvoir(nadia, reussi);
        HttpResponse<String> conflit = promouvoir(nadia, refuse);
        HttpResponse<String> introuvable = promouvoir(nadia, UUID.randomUUID());
        int avant404 = journal.marque();
        HttpResponse<String> introuvable2 = promouvoir(nadia, "pas-un-uuid");

        assertThat(succes.statusCode()).isEqualTo(200);
        assertThat(conflit.statusCode()).isEqualTo(409);
        assertThat(introuvable.statusCode()).isEqualTo(404);
        assertThat(lignes("Coureur passé en bénévole")).containsExactly("Coureur passé en bénévole (compte " + reussi
                + " par " + idNadia + ")");
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

    // ---------------------------------------------------------------- CA24

    @Test
    @DisplayName("CA24 (a) : deux changements simultanés du même coureur : exactement un 200 et un 404, jamais de 500 (20 répétitions)")
    void ca24_deux_changements_simultanes() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = coureur("Course" + i);
            CountDownLatch depart = new CountDownLatch(1);
            Callable<HttpResponse<String>> action = () -> {
                depart.await();
                return promouvoir(nadia, cible);
            };

            List<HttpResponse<String>> reponses = enParallele(depart, action, action);

            assertThat(reponses).as("répétition " + i).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(200, 404);
            assertThat(roleDe(cible)).isEqualTo("BENEVOLE");
        }
    }

    @Test
    @DisplayName("CA24 (b) : changement et suppression de son Compte par le coureur simultanés : jamais de 500, jamais un bénévole sans empreinte, jamais un Compte anonymisé resté bénévole (20 répétitions)")
    void ca24_changement_et_suppression_du_compte() throws Exception {
        for (int i = 0; i < 20; i++) {
            String pseudo = "Efface" + i;
            UUID cible = coureur(pseudo);
            inscrire(t, cible, 100 + i, "VAINQUEUR");
            Session session = api.ouvrir(pseudo, MOT_DE_PASSE_COUREUR);
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return promouvoir(nadia, cible);
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
                assertThat(compte.get("role")).as("intact " + i).isEqualTo("BENEVOLE");
            }
            assertThat(jdbc.queryForObject("select count(*) from inscription where compte_id = ?", Integer.class,
                    cible)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CA24 (c) : changement et fin de la Course du coureur simultanés : 200 ou 409, jamais de 500 (20 répétitions)")
    void ca24_changement_et_fin_de_course() throws Exception {
        for (int i = 0; i < 20; i++) {
            UUID cible = coureur("Finisseur" + i);
            UUID course = insererCourse("Course fin " + i, "2026-10-09", "EN_COURS",
                    Instant.parse("2026-10-09T13:30:00Z"));
            inscrire(course, cible, 1, "VAINQUEUR");
            CountDownLatch depart = new CountDownLatch(1);

            List<HttpResponse<String>> reponses = enParallele(depart,
                    () -> {
                        depart.await();
                        return promouvoir(nadia, cible);
                    },
                    () -> {
                        depart.await();
                        horsTransaction().update("update course set statut = 'TERMINEE' where id = ?", course);
                        return null;
                    });

            assertThat(reponses.get(0).statusCode()).as("répétition " + i).isIn(200, 409);
            assertThat(roleDe(cible)).isEqualTo(reponses.get(0).statusCode() == 200 ? "BENEVOLE" : "COUREUR");
        }
    }

    // ---------------------------------------------------------------- CA25

    @Test
    @DisplayName("CA25 : aucun nouveau changeset (0002 à 0011-compte-actif) après un passage en bénévole ; POST sur /api/administration/coureurs : 405 ou 404 sans effet (9.5 CA9)")
    void ca25_schema_inchange_et_non_regression() throws Exception {
        UUID cible = coureur("CibleSchema");
        assertThat(promouvoir(nadia, cible).statusCode()).isEqualTo(200);
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

    // ---------------------------------------------------------------- utilitaires

    private String chemin(Object id) {
        return RECHERCHE + "/" + id + "/passage-benevole";
    }

    private HttpResponse<String> promouvoir(Session session, Object id) throws Exception {
        return api.requete("POST", chemin(id), session.entetes(), null, null);
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
