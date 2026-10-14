package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * Contrat de l'incrément 4.5b : GET /api/administration/courses/{id}/inscriptions/{inscriptionId}/dossard
 * (CA4 à CA10). Les références (identifiants d'inscription, jetons QR) sont lues en base, indépendamment de l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class DossardAdminIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN_ADMIN = "/api/administration/courses";
    static final String CHEMIN_COURSES = "/api/coureur/courses";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String NOM_X = "Backyard des Crêtes";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_INSCRIPTION = "insert into inscription (id, course_id, compte_id, dossard, "
            + "jeton_qr, statut) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHANGESETS = List.of("0002-compte", "0003-admin-master-unique", "0004-course",
            "0005-logo-course", "0006-affectation-benevole", "0007-inscription",
            "0008-demarrage-course", "0009-passage", "0010-abandon-inscription");

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
    EntityManagerFactory entityManagerFactory;

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
    Session marc;
    Session chloe;
    UUID idAlice;
    UUID idBruno;
    UUID idLeo;
    UUID idChloe;

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
        insererCompte("Marc", "BENEVOLE", empreinte);
        idChloe = insererCompte("Chloé", "COUREUR", empreinte);
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        marc = api.ouvrir("Marc", MOT_DE_PASSE);
        chloe = api.ouvrir("Chloé", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Patron puis Nadia lisent le dossard d'Alice : 200 JSON, 8 champs égaux à la base, jeton égal à celui d'Alice, no-store, ligne inchangée")
    void ca4_dossard_nominal_pour_les_deux_roles_admin() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        UUID inscriptionAlice = inscriptionDe(x, idAlice);
        String jetonEnBase = jetonDe(inscriptionAlice);
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");

        for (Session admin : List.of(patron, nadia)) {
            HttpResponse<String> reponse = lire(admin, x, inscriptionAlice.toString());

            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                    c -> assertThat(c).contains("application/json"));
            assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                    c -> assertThat(c).contains("no-store"));
            JsonNode corps = json.readTree(reponse.body());
            assertThat(corps.propertyNames()).containsExactlyInAnyOrder("inscriptionId", "courseId", "courseNom",
                    "courseStatut", "dossard", "pseudo", "statut", "jetonQr");
            assertThat(corps.get("inscriptionId").asString()).isEqualTo(inscriptionAlice.toString());
            assertThat(corps.get("courseId").asString()).isEqualTo(x);
            assertThat(corps.get("courseNom").asString()).isEqualTo(NOM_X);
            assertThat(corps.get("courseStatut").asString()).isEqualTo("EN_PREPARATION");
            assertThat(corps.get("dossard").asInt()).isEqualTo(1);
            assertThat(corps.get("pseudo").asString()).isEqualTo("Alice");
            assertThat(corps.get("statut").asString()).isEqualTo("EN_COURSE");
            assertThat(corps.get("jetonQr").asString()).hasSize(43).matches("[A-Za-z0-9_-]{43}")
                    .isEqualTo(jetonEnBase);
            List<String> interdits = new ArrayList<>(List.of(idAlice.toString(), idBruno.toString(),
                    idChloe.toString()));
            interdits.addAll(jdbc.queryForList("select empreinte_mot_de_passe from compte", String.class));
            assertThat(reponse.body()).doesNotContain(interdits).doesNotContain("compteId");
        }

        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
    }

    @Test
    @DisplayName("CA4 : le jeton du dossard est égal à celui renvoyé à Alice par GET /api/coureur/inscriptions ; un second appel renvoie la même valeur")
    void ca4_jeton_identique_a_celui_du_coureur_et_stable() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno);
        UUID inscriptionAlice = inscriptionDe(x, idAlice);

        String premier = jetonDuDossard(nadia, x, inscriptionAlice);
        String second = jetonDuDossard(nadia, x, inscriptionAlice);

        HttpResponse<String> mesInscriptions = api.requete("GET", "/api/coureur/inscriptions", alice.enteteLecture(),
                null, null);
        assertThat(mesInscriptions.statusCode()).isEqualTo(200);
        JsonNode liste = json.readTree(mesInscriptions.body());
        String jetonCoureur = null;
        for (JsonNode ligne : liste) {
            if (x.equals(ligne.get("courseId").asString())) {
                jetonCoureur = ligne.get("jetonQr").asString();
            }
        }
        assertThat(jetonCoureur).isNotNull().isEqualTo(premier);
        assertThat(second).isEqualTo(premier);
        assertThat(jetonDe(inscriptionAlice)).isEqualTo(premier);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : X EN_COURS puis TERMINEE, inscription ABANDON puis VAINQUEUR : 200 à chaque fois, statuts reflétés, même jeton")
    void ca5_statuts_de_course_et_d_inscription() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno);
        UUID inscriptionAlice = inscriptionDe(x, idAlice);
        String jeton = jetonDe(inscriptionAlice);

        for (String statutCourse : List.of("EN_COURS", "TERMINEE")) {
            jdbc.update("update course set statut = ? where id = ?", statutCourse, UUID.fromString(x));
            for (String statutInscription : List.of("EN_COURSE", "ABANDON", "VAINQUEUR")) {
                jdbc.update("update inscription set statut = ? where id = ?", statutInscription, inscriptionAlice);

                HttpResponse<String> reponse = lire(nadia, x, inscriptionAlice.toString());

                assertThat(reponse.statusCode()).as(statutCourse + "/" + statutInscription).isEqualTo(200);
                JsonNode corps = json.readTree(reponse.body());
                assertThat(corps.get("courseStatut").asString()).isEqualTo(statutCourse);
                assertThat(corps.get("statut").asString()).isEqualTo(statutInscription);
                assertThat(corps.get("jetonQr").asString()).isEqualTo(jeton);
                assertThat(corps.get("pseudo").asString()).isEqualTo("Alice");
            }
        }
    }

    @Test
    @DisplayName("CA5 : Alice supprime son compte (X EN_COURS) : le dossard conservé s'ouvre avec le pseudo « Coureur anonyme »")
    void ca5_compte_anonymise() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno);
        UUID inscriptionAlice = inscriptionDe(x, idAlice);
        String jeton = jetonDe(inscriptionAlice);
        jdbc.update("update course set statut = 'EN_COURS' where id = ?", UUID.fromString(x));
        ObjectNode corpsSuppression = json.createObjectNode();
        corpsSuppression.put("motDePasseActuel", MOT_DE_PASSE);
        assertThat(api.requete("POST", "/api/comptes/moi/suppression", alice.entetes(), "application/json",
                corpsSuppression.toString()).statusCode()).isEqualTo(204);

        HttpResponse<String> reponse = lire(nadia, x, inscriptionAlice.toString());

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("pseudo").asString()).isEqualTo("Coureur anonyme");
        assertThat(corps.get("jetonQr").asString()).isEqualTo(jeton);
        assertThat(corps.get("dossard").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 : un compte supprimé par SQL donne 200 avec « Compte inconnu » et un unique WARN sans pseudo ni identifiant de Compte")
    void ca5_compte_inconnu() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        UUID inscriptionBruno = inscriptionDe(x, idBruno);
        jdbc.update("delete from compte where id = ?", idBruno);
        demarrerJournal();

        HttpResponse<String> reponse = lire(nadia, x, inscriptionBruno.toString());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body()).get("pseudo").asString()).isEqualTo("Compte inconnu");
        List<ILoggingEvent> avertissements = journal.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        assertThat(avertissements).hasSize(1);
        assertThat(avertissements.get(0).getFormattedMessage())
                .doesNotContain("Bruno", idBruno.toString(), idAlice.toString(), idChloe.toString());
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : Course aléatoire, non UUID, supprimée : 404 COURSE_INTROUVABLE ; Inscription aléatoire, non UUID, d'une autre Course : 404 INSCRIPTION_INTROUVABLE")
    void ca6_introuvables() throws Exception {
        String x = creerCourse(NOM_X, 3);
        String y = creerCourse("Backyard express", 10);
        String supprimee = creerCourse("Backyard supprimée", 3);
        inscrireDansLOrdre(x, alice, bruno);
        inscrireDansLOrdre(y, alice);
        inscrireDansLOrdre(supprimee, alice);
        UUID inscriptionAliceX = inscriptionDe(x, idAlice);
        UUID inscriptionAliceY = inscriptionDe(y, idAlice);
        UUID inscriptionSupprimee = inscriptionDe(supprimee, idAlice);
        assertThat(api.requete("DELETE", CHEMIN_ADMIN + "/" + supprimee, patron.entetes(), null, null).statusCode())
                .isEqualTo(204);

        List<HttpResponse<String>> parCourse = List.of(
                lire(nadia, UUID.randomUUID().toString(), inscriptionAliceX.toString()),
                lire(nadia, "pas-un-uuid", inscriptionAliceX.toString()),
                lire(nadia, supprimee, inscriptionSupprimee.toString()));
        List<HttpResponse<String>> parInscription = List.of(
                lire(nadia, x, UUID.randomUUID().toString()),
                lire(nadia, x, "pas-un-uuid"),
                lire(nadia, x, inscriptionAliceY.toString()));

        for (HttpResponse<String> reponse : parCourse) {
            assertIntrouvable(reponse, "COURSE_INTROUVABLE", "La course est introuvable.");
        }
        for (HttpResponse<String> reponse : parInscription) {
            assertIntrouvable(reponse, "INSCRIPTION_INTROUVABLE", "L'inscription est introuvable.");
        }
    }

    @Test
    @DisplayName("CA6 : RG8, la Course est contrôlée avant l'Inscription : Course inconnue avec Inscription non UUID ou inconnue donne COURSE_INTROUVABLE")
    void ca6_ordre_course_puis_inscription() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice);

        assertIntrouvable(lire(nadia, UUID.randomUUID().toString(), "pas-un-uuid"), "COURSE_INTROUVABLE",
                "La course est introuvable.");
        assertIntrouvable(lire(nadia, "pas-un-uuid", "pas-un-uuid"), "COURSE_INTROUVABLE",
                "La course est introuvable.");
        assertIntrouvable(lire(nadia, UUID.randomUUID().toString(), UUID.randomUUID().toString()),
                "COURSE_INTROUVABLE", "La course est introuvable.");
        assertIntrouvable(lire(nadia, x, new UUID(0L, 0L).toString()), "INSCRIPTION_INTROUVABLE",
                "L'inscription est introuvable.");
    }

    @Test
    @DisplayName("CA6 : isolation entre Courses, deux « Dossard 1 » indépendants avec leur propre jeton")
    void ca6_dossards_independants_par_course() throws Exception {
        String x = creerCourse(NOM_X, 3);
        String y = creerCourse("Backyard express", 10);
        inscrireDansLOrdre(x, alice);
        inscrireDansLOrdre(y, alice);
        UUID inscriptionX = inscriptionDe(x, idAlice);
        UUID inscriptionY = inscriptionDe(y, idAlice);

        JsonNode corpsX = json.readTree(lire(nadia, x, inscriptionX.toString()).body());
        JsonNode corpsY = json.readTree(lire(nadia, y, inscriptionY.toString()).body());

        assertThat(corpsX.get("dossard").asInt()).isEqualTo(1);
        assertThat(corpsY.get("dossard").asInt()).isEqualTo(1);
        assertThat(corpsX.get("courseNom").asString()).isEqualTo(NOM_X);
        assertThat(corpsY.get("courseNom").asString()).isEqualTo("Backyard express");
        assertThat(corpsX.get("jetonQr").asString()).isNotEqualTo(corpsY.get("jetonQr").asString());
    }

    @Test
    @DisplayName("CA6 : après désinscription de Bruno, le dossard de son Inscription donne 404 INSCRIPTION_INTROUVABLE")
    void ca6_desinscription_rend_le_dossard_introuvable() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno);
        UUID inscriptionBruno = inscriptionDe(x, idBruno);
        assertThat(lire(nadia, x, inscriptionBruno.toString()).statusCode()).isEqualTo(200);

        assertThat(api.requete("DELETE", "/api/coureur/inscriptions/" + inscriptionBruno, bruno.entetes(), null,
                null).statusCode()).isEqualTo(204);

        assertIntrouvable(lire(nadia, x, inscriptionBruno.toString()), "INSCRIPTION_INTROUVABLE",
                "L'inscription est introuvable.");
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : anonyme 401 NON_AUTHENTIFIE ; Léo (affecté), Marc, Alice, Bruno 403 ACCES_REFUSE sans jeton ni pseudo ; Patron et Nadia 200 identiques")
    void ca7_authentification_et_roles() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        assertThat(api.requete("PUT", CHEMIN_ADMIN + "/" + x + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + idLeo + "\"]}").statusCode()).isEqualTo(200);
        UUID inscriptionAlice = inscriptionDe(x, idAlice);
        String jeton = jetonDe(inscriptionAlice);

        HttpResponse<String> anonyme = api.requete("GET", chemin(x, inscriptionAlice.toString()), Map.of(), null, null);
        assertErreur(anonyme, 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertThat(anonyme.body()).doesNotContain(jeton, "Alice");
        for (Session session : List.of(leo, marc, alice, bruno)) {
            HttpResponse<String> refus = lire(session, x, inscriptionAlice.toString());
            assertErreur(refus, 403, "ACCES_REFUSE", "Accès refusé", "Vous n'avez pas les droits nécessaires.");
            assertThat(refus.body()).doesNotContain(jeton, "Alice", "Bruno");
        }

        HttpResponse<String> parPatron = lire(patron, x, inscriptionAlice.toString());
        HttpResponse<String> parNadia = lire(nadia, x, inscriptionAlice.toString());
        assertThat(parPatron.statusCode()).isEqualTo(200);
        assertThat(parNadia.statusCode()).isEqualTo(200);
        assertThat(parNadia.body()).isEqualTo(parPatron.body());
    }

    @Test
    @DisplayName("CA7 : la sécurité passe avant la recherche : 403 pour Léo, Alice et 401 pour un anonyme même avec une Course ou une Inscription inexistante")
    void ca7_securite_avant_recherche() throws Exception {
        String x = creerCourse(NOM_X, 3);
        String inconnue = UUID.randomUUID().toString();

        for (Session session : List.of(leo, alice)) {
            assertErreur(lire(session, inconnue, inconnue), 403, "ACCES_REFUSE", "Accès refusé",
                    "Vous n'avez pas les droits nécessaires.");
            assertErreur(lire(session, x, inconnue), 403, "ACCES_REFUSE", "Accès refusé",
                    "Vous n'avez pas les droits nécessaires.");
            assertErreur(lire(session, "pas-un-uuid", "pas-un-uuid"), 403, "ACCES_REFUSE", "Accès refusé",
                    "Vous n'avez pas les droits nécessaires.");
        }
        assertErreur(api.requete("GET", chemin(inconnue, inconnue), Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
    }

    @Test
    @DisplayName("CA7 : GET sans X-XSRF-TOKEN : 200 ; POST, PUT, PATCH et DELETE par Nadia (CSRF valide) sur .../dossard : 404 ou 405, base inchangée")
    void ca7_lecture_sans_csrf_et_methodes_non_prevues() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno);
        UUID inscriptionAlice = inscriptionDe(x, idAlice);
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");
        String chemin = chemin(x, inscriptionAlice.toString());

        assertThat(api.requete("GET", chemin, nadia.enteteLecture(), null, null).statusCode()).isEqualTo(200);
        for (String methode : List.of("POST", "PUT", "PATCH", "DELETE")) {
            boolean corps = !"DELETE".equals(methode);
            HttpResponse<String> reponse = api.requete(methode, chemin, nadia.entetes(),
                    corps ? "application/json" : null, corps ? "{}" : null);
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
            assertThat(reponse.body()).as(methode).doesNotContain(jetonDe(inscriptionAlice));
        }

        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(lire(nadia, x, inscriptionAlice.toString()).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : aucune autre réponse (fiche, inscrits, pilotage, liste admin, courses coureur, inscription 201, bénévole, santé, logo) ne contient un jeton_qr de la base")
    void ca8_jeton_absent_des_autres_reponses() throws Exception {
        String x = creerCourse(NOM_X, 3);
        String z = creerCourse("Backyard mini", 5);
        inscrireDansLOrdre(x, bruno, chloe);
        assertThat(api.requete("PUT", CHEMIN_ADMIN + "/" + x + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + idLeo + "\"]}").statusCode()).isEqualTo(200);
        jdbc.update("update course set statut = 'EN_COURS', demarree_le = ? where id = ?",
                Timestamp.from(MIDI_PARIS), UUID.fromString(x));
        HttpResponse<String> inscriptionAlice = api.requete("POST", CHEMIN_COURSES + "/" + z + "/inscriptions",
                alice.entetes(), null, null);
        assertThat(inscriptionAlice.statusCode()).isEqualTo(201);

        List<HttpResponse<String>> reponses = List.of(
                inscriptionAlice,
                api.requete("GET", CHEMIN_ADMIN + "/" + x, nadia.enteteLecture(), null, null),
                api.requete("GET", CHEMIN_ADMIN + "/" + x + "/inscriptions", nadia.enteteLecture(), null, null),
                api.requete("GET", CHEMIN_ADMIN + "/" + x + "/pilotage", nadia.enteteLecture(), null, null),
                api.requete("GET", CHEMIN_ADMIN, nadia.enteteLecture(), null, null),
                api.requete("GET", CHEMIN_COURSES, alice.enteteLecture(), null, null),
                api.requete("GET", "/api/benevole/courses", leo.enteteLecture(), null, null),
                api.requete("GET", "/api/sante", Map.of(), null, null),
                api.requete("GET", "/api/courses/" + x + "/logo", Map.of(), null, null));
        List<String> jetons = jdbc.queryForList("select jeton_qr from inscription", String.class);

        assertThat(jetons).hasSize(3);
        for (HttpResponse<String> reponse : reponses) {
            assertThat(reponse.body()).doesNotContain(jetons).doesNotContain("jetonQr", "jeton_qr");
            assertThat(reponse.headers().map().toString()).doesNotContain(jetons.toArray(new String[0]));
        }
        assertThat(reponses.subList(1, 7)).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
    }

    @Test
    @DisplayName("CA8 : seuls GET /api/coureur/inscriptions (propriétaire) et le dossard (admin) contiennent le jeton")
    void ca8_jeton_present_uniquement_aux_deux_endroits_prevus() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno);
        UUID inscriptionAlice = inscriptionDe(x, idAlice);
        String jeton = jetonDe(inscriptionAlice);

        assertThat(api.requete("GET", "/api/coureur/inscriptions", alice.enteteLecture(), null, null).body())
                .contains(jeton);
        assertThat(lire(nadia, x, inscriptionAlice.toString()).body()).contains(jeton);
        assertThat(api.requete("GET", "/api/coureur/inscriptions", bruno.enteteLecture(), null, null).body())
                .doesNotContain(jeton);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : lecture 200 puis 404, 401, 403 : une seule ligne INFO « Dossard consulté (course <id>, dossard 1) », aucun jeton, pseudo, UUID de Compte ni nom de Course dans le journal")
    void ca9_journal_sans_donnee_sensible() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        UUID inscriptionAlice = inscriptionDe(x, idAlice);
        demarrerJournal();
        int avant = journal.list.size();

        HttpResponse<String> lecture = lire(nadia, x, inscriptionAlice.toString());
        int apresLecture = journal.list.size();
        HttpResponse<String> introuvableCourse = lire(nadia, UUID.randomUUID().toString(),
                inscriptionAlice.toString());
        HttpResponse<String> introuvableInscription = lire(nadia, x, UUID.randomUUID().toString());
        HttpResponse<String> anonyme = api.requete("GET", chemin(x, inscriptionAlice.toString()), Map.of(), null,
                null);
        HttpResponse<String> refuse = lire(alice, x, inscriptionAlice.toString());

        assertThat(lecture.statusCode()).isEqualTo(200);
        assertThat(introuvableCourse.statusCode()).isEqualTo(404);
        assertThat(introuvableInscription.statusCode()).isEqualTo(404);
        assertThat(anonyme.statusCode()).isEqualTo(401);
        assertThat(refuse.statusCode()).isEqualTo(403);
        List<ILoggingEvent> lectureInfo = journal.list.subList(avant, apresLecture).stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).toList();
        assertThat(lectureInfo).hasSize(1);
        assertThat(lectureInfo.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(lectureInfo.get(0).getFormattedMessage())
                .isEqualTo("Dossard consulté (course " + x + ", dossard 1)");
        assertThat(journal.list.subList(apresLecture, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());

        List<String> interdits = new ArrayList<>(List.of("Alice", "Bruno", "Chloé", NOM_X, idAlice.toString(),
                idBruno.toString(), idChloe.toString()));
        interdits.addAll(jdbc.queryForList("select jeton_qr from inscription", String.class));
        assertThat(journal.list).filteredOn(e -> e.getLoggerName().startsWith("fr.backyard")
                        || e.getLevel().isGreaterOrEqual(Level.INFO))
                .noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(interdits.toArray(new String[0])));
        assertThat(journal.list).filteredOn(e -> e.getThrowableProxy() != null)
                .noneSatisfy(e -> assertThat(e.getThrowableProxy().getMessage())
                        .containsAnyOf(interdits.toArray(new String[0])));
        String jeton = jetonDe(inscriptionAlice);
        for (HttpResponse<String> erreur : List.of(introuvableCourse, introuvableInscription, anonyme, refuse)) {
            assertThat(erreur.body()).doesNotContain(jeton, "Alice");
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : aucun nouveau changeset (0002 à 0009 exactement), ddl-auto=validate")
    void ca10_aucune_migration() throws Exception {
        String x = creerCourse(NOM_X, 3);
        inscrireDansLOrdre(x, alice);

        assertThat(lire(nadia, x, inscriptionDe(x, idAlice).toString()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    @DisplayName("CA10 : le nombre d'instructions SQL du dossard est identique avec 3 et avec 30 inscrits dans la Course")
    void ca10_nombre_de_requetes_constant() throws Exception {
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistiques.setStatisticsEnabled(true);
        try {
            String petite = creerCourse("Backyard petite", 50);
            String grande = creerCourse("Backyard grande", 50);
            inscrireDansLOrdre(petite, alice, bruno, chloe);
            String empreinte = encodeur.encode(MOT_DE_PASSE);
            for (int i = 1; i <= 30; i++) {
                insererInscription(grande, insererCompte("Coureur" + i, "COUREUR", empreinte), i);
            }
            UUID dansPetite = inscriptionDe(petite, idAlice);
            UUID dansGrande = jdbc.queryForObject("select id from inscription where course_id = ? and dossard = 30",
                    UUID.class, UUID.fromString(grande));
            assertThat(lire(nadia, petite, dansPetite.toString()).statusCode()).isEqualTo(200);

            long avantPetite = statistiques.getPrepareStatementCount();
            HttpResponse<String> reponsePetite = lire(nadia, petite, dansPetite.toString());
            long pourTrois = statistiques.getPrepareStatementCount() - avantPetite;
            long avantGrande = statistiques.getPrepareStatementCount();
            HttpResponse<String> reponseGrande = lire(nadia, grande, dansGrande.toString());
            long pourTrente = statistiques.getPrepareStatementCount() - avantGrande;

            assertThat(reponsePetite.statusCode()).isEqualTo(200);
            assertThat(reponseGrande.statusCode()).isEqualTo(200);
            assertThat(json.readTree(reponseGrande.body()).get("pseudo").asString()).isEqualTo("Coureur30");
            assertThat(pourTrente).isEqualTo(pourTrois);
        } finally {
            statistiques.setStatisticsEnabled(false);
        }
    }

    // ---------------------------------------------------------------- utilitaires

    private String chemin(String idCourse, String idInscription) {
        return CHEMIN_ADMIN + "/" + idCourse + "/inscriptions/" + idInscription + "/dossard";
    }

    private HttpResponse<String> lire(Session session, String idCourse, String idInscription) throws Exception {
        return api.requete("GET", chemin(idCourse, idInscription), session.enteteLecture(), null, null);
    }

    private String jetonDuDossard(Session session, String idCourse, UUID idInscription) throws Exception {
        HttpResponse<String> reponse = lire(session, idCourse, idInscription.toString());
        assertThat(reponse.statusCode()).isEqualTo(200);
        return json.readTree(reponse.body()).get("jetonQr").asString();
    }

    private UUID inscriptionDe(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                UUID.fromString(idCourse), idCompte);
    }

    private String jetonDe(UUID idInscription) {
        return jdbc.queryForObject("select jeton_qr from inscription where id = ?", String.class, idInscription);
    }

    private void inscrireDansLOrdre(String idCourse, Session... coureurs) throws Exception {
        for (Session coureur : coureurs) {
            assertThat(api.requete("POST", CHEMIN_COURSES + "/" + idCourse + "/inscriptions", coureur.entetes(),
                    null, null).statusCode()).isEqualTo(201);
        }
    }

    private void insererInscription(String idCourse, UUID idCompte, int dossard) {
        jdbc.update(INSERTION_INSCRIPTION, UUID.randomUUID(), UUID.fromString(idCourse), idCompte, dossard,
                UUID.randomUUID().toString().replace("-", ""), "EN_COURSE");
    }

    private UUID insererCompte(String pseudo, String role, String empreinte) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte, role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
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

    private void assertIntrouvable(HttpResponse<String> reponse, String code, String detail) throws Exception {
        assertErreur(reponse, 404, code, "Introuvable", detail);
        List<String> interdits = new ArrayList<>(jdbc.queryForList("select jeton_qr from inscription", String.class));
        interdits.addAll(List.of("Alice", "Bruno", "Chloé"));
        assertThat(reponse.body()).doesNotContain(interdits);
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
