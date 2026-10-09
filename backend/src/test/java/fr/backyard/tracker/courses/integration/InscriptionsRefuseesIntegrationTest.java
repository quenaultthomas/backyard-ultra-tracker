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
import java.util.TreeSet;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 3.2 : inscriptions refusées (CA5 à CA9). Le statut EN_COURS / TERMINEE se place par SQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class InscriptionsRefuseesIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN_ADMIN = "/api/administration/courses";
    static final String CHEMIN = "/api/coureur/courses";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String INTROUVABLE_DETAIL = "La course est introuvable.";
    static final String DEJA_INSCRIT_DETAIL = "Vous êtes déjà inscrit à cette course.";
    static final String COMPLETE_DETAIL = "La course est complète.";
    static final String NON_OUVERTE_DETAIL = "La course n'est plus ouverte aux inscriptions.";
    static final String NOM_D = "Backyard duo";
    static final String NOM_X = "Backyard des Crêtes";
    static final String NOM_E = "Course démarrée";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_INSCRIPTION = "insert into inscription (id, course_id, compte_id, dossard, "
            + "jeton_qr, statut) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHANGESETS = List.of("0002-compte", "0003-admin-master-unique", "0004-course",
            "0005-logo-course", "0006-affectation-benevole", "0007-inscription",
                "0008-demarrage-course");

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
    String empreinte;
    Session patron;
    Session nadia;
    Session alice;
    Session bruno;
    Session chloe;
    Session leo;
    UUID idAlice;
    UUID idBruno;
    UUID idChloe;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(MIDI_PARIS);
        api = new ClientHttp(port);
        empreinte = encodeur.encode(MOT_DE_PASSE);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        insererCompte("Nadia", "ADMIN");
        idAlice = insererCompte("Alice", "COUREUR");
        idBruno = insererCompte("Bruno", "COUREUR");
        idChloe = insererCompte("Chloé", "COUREUR");
        insererCompte("Léo", "BENEVOLE");
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

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : D à 2 places : Alice et Bruno 201 (dossards 1 et 2), Chloé 409 COURSE_COMPLETE sans ligne créée ; Alice rejoue : INSCRIPTION_DEJA_EXISTANTE ; Chloé s'inscrit à X : dossard 1")
    void ca5_course_complete_refusee_et_ordre_doublon() throws Exception {
        String d = creerCourse(NOM_D, "2026-11-14", 2);
        String x = creerCourse(NOM_X, "2026-12-14", 50);

        assertThat(json.readTree(sinscrire(alice, d).body()).get("dossard").asInt()).isEqualTo(1);
        HttpResponse<String> deuxieme = sinscrire(bruno, d);
        assertThat(deuxieme.statusCode()).isEqualTo(201);
        assertThat(json.readTree(deuxieme.body()).get("dossard").asInt()).isEqualTo(2);

        assertErreur(sinscrire(chloe, d), 409, "COURSE_COMPLETE", "Conflit", COMPLETE_DETAIL);
        assertThat(compterCourse(d)).isEqualTo(2);
        assertThat(compter(d, idChloe)).isZero();

        assertErreur(sinscrire(alice, d), 409, "INSCRIPTION_DEJA_EXISTANTE", "Conflit", DEJA_INSCRIT_DETAIL);
        assertThat(compterCourse(d)).isEqualTo(2);

        HttpResponse<String> surX = sinscrire(chloe, x);
        assertThat(surX.statusCode()).isEqualTo(201);
        assertThat(json.readTree(surX.body()).get("dossard").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 : une course à 1 participant max est remplie par la première inscription, la deuxième est 409 COURSE_COMPLETE")
    void ca5_course_a_un_participant() throws Exception {
        String unique = creerCourse("Backyard solo", "2026-11-14", 1);

        assertThat(sinscrire(alice, unique).statusCode()).isEqualTo(201);
        assertErreur(sinscrire(bruno, unique), 409, "COURSE_COMPLETE", "Conflit", COMPLETE_DETAIL);

        assertThat(compterCourse(unique)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 : PUT de Patron ramenant le maximum de D de 2 à 1 : 200, les 2 inscriptions subsistent, Chloé reçoit 409 COURSE_COMPLETE")
    void ca5_baisse_du_maximum_sous_le_nombre_d_inscrits() throws Exception {
        String d = creerCourse(NOM_D, "2026-11-14", 2);
        assertThat(sinscrire(alice, d).statusCode()).isEqualTo(201);
        assertThat(sinscrire(bruno, d).statusCode()).isEqualTo(201);

        HttpResponse<String> modification = api.requete("PUT", CHEMIN_ADMIN + "/" + d, patron.entetes(),
                "application/json", corpsCourse(NOM_D, "2026-11-14", 1).toString());

        assertThat(modification.statusCode()).isEqualTo(200);
        assertThat(compterCourse(d)).isEqualTo(2);
        assertErreur(sinscrire(chloe, d), 409, "COURSE_COMPLETE", "Conflit", COMPLETE_DETAIL);
        assertThat(compterCourse(d)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA5 : course complète, rôles et CSRF : Nadia, Patron et Léo 403 ACCES_REFUSE, anonyme 401, CSRF absent 403 CSRF_INVALIDE, avant COURSE_COMPLETE")
    void ca5_securite_prioritaire_sur_course_complete() throws Exception {
        String d = creerCourse(NOM_D, "2026-11-14", 1);
        assertThat(sinscrire(alice, d).statusCode()).isEqualTo(201);
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (Session session : List.of(nadia, patron, leo)) {
            assertErreur(sinscrire(session, d), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        }
        assertErreur(api.requete("POST", CHEMIN + "/" + d + "/inscriptions", anonyme, null, null), 401,
                "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.requete("POST", CHEMIN + "/" + d + "/inscriptions", chloe.enteteLecture(), null, null), 403,
                "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
        assertThat(compterCourse(d)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : E EN_COURS puis TERMINEE avec Alice inscrite : Bruno 409 COURSE_NON_OUVERTE identique, aucune ligne créée ; Alice (déjà inscrite) aussi")
    void ca6_course_non_ouverte_refusee() throws Exception {
        String e = creerCourse(NOM_E, "2026-11-14", 50);
        insererInscription(e, idAlice, 1);
        for (String statut : List.of("EN_COURS", "TERMINEE")) {
            changerStatut(e, statut);

            assertErreur(sinscrire(bruno, e), 409, "COURSE_NON_OUVERTE", "Conflit", NON_OUVERTE_DETAIL);
            assertErreur(sinscrire(alice, e), 409, "COURSE_NON_OUVERTE", "Conflit", NON_OUVERTE_DETAIL);

            assertThat(compter(e, idBruno)).as(statut).isZero();
            assertThat(compterCourse(e)).as(statut).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CA6 : E EN_COURS dont le nombre d'inscriptions atteint le maximum : 409 COURSE_NON_OUVERTE (pas COURSE_COMPLETE)")
    void ca6_non_ouverte_prioritaire_sur_complete() throws Exception {
        String e = creerCourse(NOM_E, "2026-11-14", 1);
        insererInscription(e, idAlice, 1);
        changerStatut(e, "EN_COURS");

        assertErreur(sinscrire(bruno, e), 409, "COURSE_NON_OUVERTE", "Conflit", NON_OUVERTE_DETAIL);

        assertThat(compterCourse(e)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA6 : X passée EN_COURS par SQL après une inscription : 409 COURSE_NON_OUVERTE ; remise à EN_PREPARATION : 201")
    void ca6_cycle_de_statut() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14", 50);
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);
        changerStatut(x, "EN_COURS");

        assertErreur(sinscrire(bruno, x), 409, "COURSE_NON_OUVERTE", "Conflit", NON_OUVERTE_DETAIL);
        assertThat(compter(x, idBruno)).isZero();

        changerStatut(x, "EN_PREPARATION");
        HttpResponse<String> reponse = sinscrire(bruno, x);
        assertThat(reponse.statusCode()).isEqualTo(201);
        assertThat(json.readTree(reponse.body()).get("dossard").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA6 : course introuvable (id inconnu, non UUID) : 404 COURSE_INTROUVABLE avant tout autre contrôle")
    void ca6_introuvable_prioritaire() throws Exception {
        for (String id : List.of(ID_INCONNU, "inexistant")) {
            assertErreur(sinscrire(alice, id), 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
        }
    }

    @Test
    @DisplayName("CA6 : sur E non ouverte, Nadia, Patron et Léo 403 ACCES_REFUSE, anonyme 401, Alice sans X-XSRF-TOKEN 403 CSRF_INVALIDE")
    void ca6_securite_prioritaire_sur_non_ouverte() throws Exception {
        String e = creerCourse(NOM_E, "2026-11-14", 50);
        changerStatut(e, "EN_COURS");
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (Session session : List.of(nadia, patron, leo)) {
            assertErreur(sinscrire(session, e), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        }
        assertErreur(api.requete("POST", CHEMIN + "/" + e + "/inscriptions", anonyme, null, null), 401,
                "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.requete("POST", CHEMIN + "/" + e + "/inscriptions", alice.enteteLecture(), null, null), 403,
                "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
        assertThat(compterCourse(e)).isZero();
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : liste de Chloé avec D (1 inscrite sur 2) : D et X complete=false ; après Bruno : D complete=true, monInscription null ; la course pleine reste listée")
    void ca7_indicateur_complete_dans_la_liste() throws Exception {
        String d = creerCourse(NOM_D, "2026-12-14", 2);
        String x = creerCourse(NOM_X, "2026-11-14", 50);
        assertThat(sinscrire(alice, d).statusCode()).isEqualTo(201);

        JsonNode avant = json.readTree(lister(chloe).body());
        assertThat(ligneListe(avant, d).get("complete").asBoolean()).isFalse();
        assertThat(ligneListe(avant, x).get("complete").asBoolean()).isFalse();

        assertThat(sinscrire(bruno, d).statusCode()).isEqualTo(201);

        HttpResponse<String> reponse = lister(chloe);
        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode apres = json.readTree(reponse.body());
        assertThat(apres).hasSize(2);
        JsonNode ligneD = ligneListe(apres, d);
        assertThat(ligneD.get("complete").isBoolean()).isTrue();
        assertThat(ligneD.get("complete").asBoolean()).isTrue();
        assertThat(ligneD.get("monInscription").isNull()).isTrue();
        assertThat(ligneListe(apres, x).get("complete").asBoolean()).isFalse();
        assertThat(ligneD.propertyNames()).doesNotContain("nombreInscrits", "placesRestantes", "jetonQr", "statut");
        assertThat(reponse.body()).doesNotContain("nombreInscrits", "placesRestantes", "jetonQr");
    }

    @Test
    @DisplayName("CA7 : liste d'Alice : D pleine avec complete=true et monInscription renseignée (dossard 1)")
    void ca7_coureur_inscrit_a_une_course_pleine() throws Exception {
        String d = creerCourse(NOM_D, "2026-12-14", 2);
        assertThat(sinscrire(alice, d).statusCode()).isEqualTo(201);
        assertThat(sinscrire(bruno, d).statusCode()).isEqualTo(201);

        JsonNode ligneD = ligneListe(json.readTree(lister(alice).body()), d);

        assertThat(ligneD.get("complete").asBoolean()).isTrue();
        assertThat(ligneD.get("monInscription").get("dossard").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA7 : D passée EN_COURS en base disparaît de la liste ; Nadia 403, anonyme 401")
    void ca7_course_demarree_et_securite() throws Exception {
        String d = creerCourse(NOM_D, "2026-12-14", 2);
        String x = creerCourse(NOM_X, "2026-11-14", 50);
        assertThat(sinscrire(alice, d).statusCode()).isEqualTo(201);
        assertThat(sinscrire(bruno, d).statusCode()).isEqualTo(201);
        changerStatut(d, "EN_COURS");

        HttpResponse<String> reponse = lister(chloe);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode liste = json.readTree(reponse.body());
        assertThat(liste).hasSize(1);
        assertThat(liste.get(0).get("id").asString()).isEqualTo(x);
        assertErreur(lister(nadia), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(api.requete("GET", CHEMIN, Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : 10 coureurs simultanés sur 3 places : 3 réponses 201 et 7 réponses 409 COURSE_COMPLETE, aucune 500, dossards {1, 2, 3}, 3 lignes")
    void ca8_dix_coureurs_sur_trois_places() throws Exception {
        String c = creerCourse("Trois places", "2026-11-14", 3);
        List<Session> coureurs = creerCoureurs(10);

        List<Callable<HttpResponse<String>>> taches = new ArrayList<>();
        coureurs.forEach(session -> taches.add(() -> sinscrire(session, c)));
        List<HttpResponse<String>> reponses = lancerEnParallele(taches);

        assertThat(reponses).filteredOn(r -> r.statusCode() == 201).hasSize(3);
        List<HttpResponse<String>> refus = reponses.stream().filter(r -> r.statusCode() == 409).toList();
        assertThat(refus).hasSize(7);
        for (HttpResponse<String> reponse : refus) {
            assertErreur(reponse, 409, "COURSE_COMPLETE", "Conflit", COMPLETE_DETAIL);
        }
        TreeSet<Integer> dossards = new TreeSet<>();
        for (HttpResponse<String> reponse : reponses) {
            if (reponse.statusCode() == 201) {
                dossards.add(json.readTree(reponse.body()).get("dossard").asInt());
            }
        }
        assertThat(dossards).containsExactly(1, 2, 3);
        assertThat(compterCourse(c)).isEqualTo(3);
    }

    @Test
    @DisplayName("CA8 : une seule place libre, Alice, Bruno et Chloé simultanés (plusieurs tours) : un 201, deux 409 COURSE_COMPLETE, 2 lignes")
    void ca8_derniere_place_disputee() throws Exception {
        for (int tour = 0; tour < 5; tour++) {
            String c = creerCourse("Dernière place " + tour, "2026-11-14", 2);
            insererInscription(c, insererCompte("Premier" + tour, "COUREUR"), 1);

            List<HttpResponse<String>> reponses = lancerEnParallele(List.of(
                    () -> sinscrire(alice, c), () -> sinscrire(bruno, c), () -> sinscrire(chloe, c)));

            assertThat(reponses).as("tour " + tour).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(201, 409, 409);
            for (HttpResponse<String> reponse : reponses) {
                if (reponse.statusCode() == 409) {
                    assertErreur(reponse, 409, "COURSE_COMPLETE", "Conflit", COMPLETE_DETAIL);
                }
            }
            assertThat(compterCourse(c)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("CA8 : Alice envoie deux POST simultanés sur la dernière place : un 201 et un 409 INSCRIPTION_DEJA_EXISTANTE, une seule ligne")
    void ca8_meme_coureur_deux_fois_sur_la_derniere_place() throws Exception {
        for (int tour = 0; tour < 5; tour++) {
            String c = creerCourse("Doublon " + tour, "2026-11-14", 1);

            List<HttpResponse<String>> reponses = lancerEnParallele(
                    List.of(() -> sinscrire(alice, c), () -> sinscrire(alice, c)));

            assertThat(reponses).as("tour " + tour).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(201, 409);
            for (HttpResponse<String> reponse : reponses) {
                if (reponse.statusCode() == 409) {
                    assertErreur(reponse, 409, "INSCRIPTION_DEJA_EXISTANTE", "Conflit", DEJA_INSCRIT_DETAIL);
                }
            }
            assertThat(compterCourse(c)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CA8 : POST de Bruno et DELETE de la course par Patron simultanés : aucune 500, POST 201 ou 404, aucune ligne restante")
    void ca8_inscription_et_suppression_concurrentes() throws Exception {
        for (int tour = 0; tour < 8; tour++) {
            String c = creerCourse("Concurrence " + tour, "2026-11-14", 1);

            List<HttpResponse<String>> reponses = lancerEnParallele(List.of(
                    () -> sinscrire(bruno, c),
                    () -> api.requete("DELETE", CHEMIN_ADMIN + "/" + c, patron.entetes(), null, null)));

            assertThat(reponses.get(0).statusCode()).as("POST, tour " + tour).isIn(201, 404);
            assertThat(reponses.get(1).statusCode()).as("DELETE, tour " + tour).isEqualTo(204);
            assertThat(compterCourse(c)).isZero();
        }
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : aucune ligne INFO ou plus pour les 409 COURSE_COMPLETE, COURSE_NON_OUVERTE et INSCRIPTION_DEJA_EXISTANTE ; ni pseudo, UUID, nom de course ni jeton dans le journal et les réponses ; le succès journalise « Inscription enregistrée »")
    void ca9_journal_des_refus() throws Exception {
        String d = creerCourse(NOM_D, "2026-11-14", 2);
        String e = creerCourse(NOM_E, "2026-11-15", 50);
        insererInscription(e, idAlice, 1);
        changerStatut(e, "EN_COURS");
        demarrerJournal();

        HttpResponse<String> succes1 = sinscrire(alice, d);
        HttpResponse<String> succes2 = sinscrire(bruno, d);
        assertThat(succes1.statusCode()).isEqualTo(201);
        assertThat(succes2.statusCode()).isEqualTo(201);
        int apresSucces = journal.list.size();
        HttpResponse<String> complete = sinscrire(chloe, d);
        HttpResponse<String> nonOuverte = sinscrire(bruno, e);
        HttpResponse<String> dejaInscrit = sinscrire(alice, d);

        assertErreur(complete, 409, "COURSE_COMPLETE", "Conflit", COMPLETE_DETAIL);
        assertErreur(nonOuverte, 409, "COURSE_NON_OUVERTE", "Conflit", NON_OUVERTE_DETAIL);
        assertErreur(dejaInscrit, 409, "INSCRIPTION_DEJA_EXISTANTE", "Conflit", DEJA_INSCRIT_DETAIL);

        assertThat(journal.list.subList(apresSucces, journal.list.size()))
                .noneSatisfy(ev -> assertThat(ev.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<ILoggingEvent> inscriptions = journal.list.stream()
                .filter(ev -> ev.getFormattedMessage().contains("Inscription enregistrée")).toList();
        assertThat(inscriptions).hasSize(2);
        assertThat(inscriptions).allSatisfy(ev -> assertThat(ev.getLevel()).isEqualTo(Level.INFO));

        List<String> interdits = new ArrayList<>(List.of("Alice", "Bruno", "Chloé", idAlice.toString(),
                idBruno.toString(), idChloe.toString(), NOM_D));
        interdits.addAll(jdbc.queryForList("select jeton_qr from inscription", String.class));
        // Tous niveaux pour le code de l'application ; INFO et plus pour les bibliothèques (le DEBUG de Spring
        // Security affiche le principal de la session, hors du code de l'application).
        assertThat(journal.list).filteredOn(ev -> ev.getLoggerName().startsWith("fr.backyard")
                        || ev.getLevel().isGreaterOrEqual(Level.INFO))
                .noneSatisfy(ev -> assertThat(ev.getFormattedMessage()).containsAnyOf(interdits.toArray(new String[0])));
        for (HttpResponse<String> reponse : List.of(complete, nonOuverte, dejaInscrit)) {
            assertThat(reponse.body()).doesNotContain(interdits.toArray(new String[0]));
        }
    }

    @Test
    @DisplayName("CA9 : databasechangelog contient exactement 0002 à 0008 (aucun nouveau changeset) et ddl-auto=validate")
    void ca9_aucune_migration() {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    // ---------------------------------------------------------------- utilitaires

    private List<Session> creerCoureurs(int nombre) throws Exception {
        List<Session> sessions = new ArrayList<>();
        for (int i = 0; i < nombre; i++) {
            String pseudo = "Coureur" + i;
            insererCompte(pseudo, "COUREUR");
            sessions.add(api.ouvrir(pseudo, MOT_DE_PASSE));
        }
        return sessions;
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

    private HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", CHEMIN + "/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    private HttpResponse<String> lister(Session session) throws Exception {
        return api.requete("GET", CHEMIN, session.enteteLecture(), null, null);
    }

    private JsonNode ligneListe(JsonNode liste, String idCourse) {
        for (JsonNode course : liste) {
            if (idCourse.equals(course.get("id").asString())) {
                return course;
            }
        }
        throw new AssertionError("Course absente de la liste : " + idCourse);
    }

    private int compter(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select count(*) from inscription where course_id = ? and compte_id = ?",
                Integer.class, UUID.fromString(idCourse), idCompte);
    }

    private int compterCourse(String idCourse) {
        return jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                UUID.fromString(idCourse));
    }

    private void insererInscription(String idCourse, UUID idCompte, int dossard) {
        jdbc.update(INSERTION_INSCRIPTION, UUID.randomUUID(), UUID.fromString(idCourse), idCompte, dossard,
                UUID.randomUUID().toString().replace("-", ""), "EN_COURSE");
    }

    private void changerStatut(String idCourse, String statut) {
        jdbc.update("update course set statut = ? where id = ?", statut, UUID.fromString(idCourse));
    }

    private UUID insererCompte(String pseudo, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT),
                empreinte != null ? empreinte : encodeur.encode(MOT_DE_PASSE), role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private ObjectNode corpsCourse(String nom, String date, int nombreMaxParticipants) {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", date);
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", nombreMaxParticipants);
        corps.put("nombreMaxBoucles", 24);
        return corps;
    }

    private String creerCourse(String nom, String date, int nombreMaxParticipants) throws Exception {
        HttpResponse<String> reponse = api.requete("POST", CHEMIN_ADMIN, patron.entetes(), "application/json",
                corpsCourse(nom, date, nombreMaxParticipants).toString());
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
