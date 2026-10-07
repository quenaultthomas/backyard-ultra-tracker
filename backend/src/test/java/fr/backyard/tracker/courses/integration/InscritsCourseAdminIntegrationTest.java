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
 * Contrat de l'incrément 3.5 : GET /api/administration/courses/{id}/inscriptions (CA5 à CA10). Les références
 * (identifiants d'inscription, jetons QR) sont lues en base, indépendamment de l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class InscritsCourseAdminIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN_ADMIN = "/api/administration/courses";
    static final String CHEMIN_COURSES = "/api/coureur/courses";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_INSCRIPTION = "insert into inscription (id, course_id, compte_id, dossard, "
            + "jeton_qr, statut) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHANGESETS = List.of("0002-compte", "0003-admin-master-unique", "0004-course",
            "0005-logo-course", "0006-affectation-benevole", "0007-inscription");

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
    @DisplayName("CA5 : Patron puis Nadia lisent X : 200, nombres, inscrits triés (1 Alice, 2 Bruno, 3 Chloé), ids égaux à la base, sans jeton ni compteId, course inchangée")
    void ca5_liste_nominale_pour_les_deux_roles_admin() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");

        for (Session admin : List.of(patron, nadia)) {
            HttpResponse<String> reponse = lister(admin, x);

            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                    c -> assertThat(c).contains("application/json"));
            JsonNode corps = json.readTree(reponse.body());
            assertThat(corps.propertyNames()).containsExactlyInAnyOrder("courseId", "nombreMaxParticipants",
                    "nombreInscrits", "placesRestantes", "complete", "inscrits");
            assertThat(corps.get("courseId").asString()).isEqualTo(x);
            assertThat(corps.get("nombreMaxParticipants").asInt()).isEqualTo(3);
            assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(3);
            assertThat(corps.get("placesRestantes").asInt()).isZero();
            assertThat(corps.get("complete").asBoolean()).isTrue();
            JsonNode inscrits = corps.get("inscrits");
            assertThat(inscrits).hasSize(3);
            List<String> pseudos = List.of("Alice", "Bruno", "Chloé");
            List<UUID> comptes = List.of(idAlice, idBruno, idChloe);
            for (int i = 0; i < 3; i++) {
                JsonNode ligne = inscrits.get(i);
                assertThat(ligne.propertyNames()).containsExactlyInAnyOrder("inscriptionId", "dossard", "pseudo",
                        "statut");
                assertThat(ligne.get("dossard").asInt()).isEqualTo(i + 1);
                assertThat(ligne.get("pseudo").asString()).isEqualTo(pseudos.get(i));
                assertThat(ligne.get("statut").asString()).isEqualTo("EN_COURSE");
                assertThat(ligne.get("inscriptionId").asString()).isEqualTo(jdbc.queryForObject(
                        "select id from inscription where course_id = ? and compte_id = ?", UUID.class,
                        UUID.fromString(x), comptes.get(i)).toString());
            }
            List<String> interdits = new ArrayList<>(jdbc.queryForList("select jeton_qr from inscription",
                    String.class));
            interdits.addAll(jdbc.queryForList("select empreinte_mot_de_passe from compte", String.class));
            comptes.forEach(c -> interdits.add(c.toString()));
            assertThat(reponse.body()).doesNotContain(interdits).doesNotContain("jetonQr", "jeton_qr", "compteId");
        }

        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
    }

    @Test
    @DisplayName("CA5 : l'ordre suit le dossard et non l'ordre d'insertion ni le pseudo")
    void ca5_tri_par_dossard() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 5);
        insererInscription(x, idChloe, 1);
        insererInscription(x, idAlice, 7);
        insererInscription(x, idBruno, 3);

        JsonNode inscrits = json.readTree(lister(nadia, x).body()).get("inscrits");

        assertThat(inscrits).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 3, 7);
        assertThat(inscrits).extracting(n -> n.get("pseudo").asString()).containsExactly("Chloé", "Bruno", "Alice");
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : Y avec la seule Alice, V sans inscription, X EN_COURS puis TERMINEE, maximum ramené à 2 avec 3 inscrits")
    void ca6_isolation_courses_statuts_et_bornes() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        String y = creerCourse("Backyard express", 10);
        String v = creerCourse("Backyard vide", 7);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        inscrireDansLOrdre(y, alice);

        JsonNode corpsY = json.readTree(lister(nadia, y).body());
        assertThat(corpsY.get("nombreInscrits").asInt()).isEqualTo(1);
        assertThat(corpsY.get("placesRestantes").asInt()).isEqualTo(9);
        assertThat(corpsY.get("complete").asBoolean()).isFalse();
        assertThat(corpsY.get("inscrits")).hasSize(1);
        assertThat(corpsY.get("inscrits").get(0).get("pseudo").asString()).isEqualTo("Alice");
        assertThat(corpsY.get("inscrits").get(0).get("dossard").asInt()).isEqualTo(1);

        HttpResponse<String> reponseV = lister(nadia, v);
        assertThat(reponseV.statusCode()).isEqualTo(200);
        JsonNode corpsV = json.readTree(reponseV.body());
        assertThat(corpsV.get("inscrits")).isEmpty();
        assertThat(corpsV.get("nombreInscrits").asInt()).isZero();
        assertThat(corpsV.get("placesRestantes").asInt()).isEqualTo(7);
        assertThat(corpsV.get("nombreMaxParticipants").asInt()).isEqualTo(7);
        assertThat(corpsV.get("complete").asBoolean()).isFalse();

        String referenceX = lister(nadia, x).body();
        for (String statut : List.of("EN_COURS", "TERMINEE")) {
            jdbc.update("update course set statut = ? where id = ?", statut, UUID.fromString(x));
            HttpResponse<String> reponse = lister(nadia, x);
            assertThat(reponse.statusCode()).as(statut).isEqualTo(200);
            assertThat(reponse.body()).as(statut).isEqualTo(referenceX);
        }

        jdbc.update("update course set nombre_max_participants = 2 where id = ?", UUID.fromString(x));
        JsonNode reduit = json.readTree(lister(nadia, x).body());
        assertThat(reduit.get("nombreMaxParticipants").asInt()).isEqualTo(2);
        assertThat(reduit.get("nombreInscrits").asInt()).isEqualTo(3);
        assertThat(reduit.get("placesRestantes").asInt()).isZero();
        assertThat(reduit.get("complete").asBoolean()).isTrue();
        assertThat(reduit.get("inscrits")).hasSize(3);
    }

    @Test
    @DisplayName("CA6 : les statuts ABANDON et VAINQUEUR posés par SQL sont renvoyés tels quels")
    void ca6_statuts_reels_des_inscriptions() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        jdbc.update("update inscription set statut = 'ABANDON' where compte_id = ?", idBruno);
        jdbc.update("update inscription set statut = 'VAINQUEUR' where compte_id = ?", idChloe);

        JsonNode inscrits = json.readTree(lister(nadia, x).body()).get("inscrits");

        assertThat(inscrits).extracting(n -> n.get("statut").asString())
                .containsExactly("EN_COURSE", "ABANDON", "VAINQUEUR");
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : UUID inconnu, identifiant non UUID et course supprimée : trois 404 COURSE_INTROUVABLE identiques")
    void ca7_course_introuvable() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno);
        assertThat(api.requete("DELETE", CHEMIN_ADMIN + "/" + x, patron.entetes(), null, null).statusCode())
                .isEqualTo(204);

        HttpResponse<String> inconnue = lister(nadia, UUID.randomUUID().toString());
        HttpResponse<String> nonUuid = lister(nadia, "pas-un-uuid");
        HttpResponse<String> supprimee = lister(nadia, x);

        for (HttpResponse<String> reponse : List.of(inconnue, nonUuid, supprimee)) {
            assertThat(reponse.statusCode()).isEqualTo(404);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                    c -> assertThat(c).contains("application/problem+json"));
            JsonNode corps = json.readTree(reponse.body());
            assertThat(corps.get("title").asString()).isEqualTo("Introuvable");
            assertThat(corps.get("status").asInt()).isEqualTo(404);
            assertThat(corps.get("detail").asString()).isEqualTo("La course est introuvable.");
            assertThat(corps.get("code").asString()).isEqualTo("COURSE_INTROUVABLE");
            assertThat(reponse.body()).doesNotContain("Alice", "Bruno");
        }
        assertThat(jdbc.queryForObject("select count(*) from inscription", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : anonyme 401 NON_AUTHENTIFIE ; Léo (affecté) et Alice 403 ACCES_REFUSE sans pseudo ; Patron et Nadia 200 identique")
    void ca8_authentification_et_roles() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        assertThat(api.requete("PUT", CHEMIN_ADMIN + "/" + x + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + idLeo + "\"]}").statusCode()).isEqualTo(200);

        HttpResponse<String> anonyme = api.requete("GET", chemin(x), Map.of(), null, null);
        assertErreur(anonyme, 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertThat(anonyme.body()).doesNotContain("Alice", "Bruno", "Chloé");
        for (Session session : List.of(leo, alice)) {
            HttpResponse<String> refus = lister(session, x);
            assertErreur(refus, 403, "ACCES_REFUSE", "Accès refusé", "Vous n'avez pas les droits nécessaires.");
            assertThat(refus.body()).doesNotContain("Alice", "Bruno", "Chloé");
        }

        HttpResponse<String> parPatron = lister(patron, x);
        HttpResponse<String> parNadia = lister(nadia, x);
        assertThat(parPatron.statusCode()).isEqualTo(200);
        assertThat(parNadia.statusCode()).isEqualTo(200);
        assertThat(parNadia.body()).isEqualTo(parPatron.body());
    }

    @Test
    @DisplayName("CA8 : POST, PUT, PATCH et DELETE sur .../inscriptions par Nadia (CSRF valide) : 404 ou 405, base inchangée ; GET sans CSRF reste 200")
    void ca8_methodes_non_prevues_et_lecture_sans_csrf() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno);
        List<Map<String, Object>> inscriptionsAvant = jdbc.queryForList("select * from inscription order by id");
        List<Map<String, Object>> coursesAvant = jdbc.queryForList("select * from course order by id");

        for (String methode : List.of("POST", "PUT", "PATCH", "DELETE")) {
            boolean corps = !"DELETE".equals(methode);
            assertThat(api.requete(methode, chemin(x), nadia.entetes(), corps ? "application/json" : null,
                    corps ? "{}" : null).statusCode()).as(methode).isIn(404, 405);
        }

        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(inscriptionsAvant);
        assertThat(jdbc.queryForList("select * from course order by id")).isEqualTo(coursesAvant);
        assertThat(api.requete("GET", chemin(x), nadia.enteteLecture(), null, null).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA8 : les pseudos des autres coureurs n'apparaissent ni dans /api/coureur/inscriptions d'Alice ni dans /api/benevole/courses de Léo")
    void ca8_pseudos_non_exposes_aux_autres_roles() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        assertThat(api.requete("PUT", CHEMIN_ADMIN + "/" + x + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + idLeo + "\"]}").statusCode()).isEqualTo(200);

        HttpResponse<String> mesInscriptions = api.requete("GET", "/api/coureur/inscriptions", alice.enteteLecture(),
                null, null);
        HttpResponse<String> coursesBenevole = api.requete("GET", "/api/benevole/courses", leo.enteteLecture(),
                null, null);
        HttpResponse<String> coursesCoureur = api.requete("GET", CHEMIN_COURSES, alice.enteteLecture(), null, null);

        assertThat(mesInscriptions.statusCode()).isEqualTo(200);
        assertThat(coursesBenevole.statusCode()).isEqualTo(200);
        assertThat(coursesCoureur.statusCode()).isEqualTo(200);
        assertThat(mesInscriptions.body()).doesNotContain("Bruno", "Chloé");
        assertThat(coursesBenevole.body()).doesNotContain("Alice", "Bruno", "Chloé");
        assertThat(coursesCoureur.body()).doesNotContain("Alice", "Bruno", "Chloé");
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : lecture 200, 404, 401, 403 : aucune ligne INFO ou supérieure, aucun pseudo, UUID de Compte ni jeton dans le journal")
    void ca9_journal_sans_donnee_sensible() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        demarrerJournal();
        int avant = journal.list.size();

        assertThat(lister(nadia, x).statusCode()).isEqualTo(200);
        assertThat(lister(nadia, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
        assertThat(api.requete("GET", chemin(x), Map.of(), null, null).statusCode()).isEqualTo(401);
        assertThat(lister(alice, x).statusCode()).isEqualTo(403);

        assertThat(journal.list.subList(avant, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<String> interdits = new ArrayList<>(List.of("Alice", "Bruno", "Chloé", idAlice.toString(),
                idBruno.toString(), idChloe.toString()));
        interdits.addAll(jdbc.queryForList("select jeton_qr from inscription", String.class));
        assertThat(journal.list).filteredOn(e -> e.getLoggerName().startsWith("fr.backyard")
                        || e.getLevel().isGreaterOrEqual(Level.INFO))
                .noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(interdits.toArray(new String[0])));
    }

    @Test
    @DisplayName("CA9 : un compte supprimé par SQL donne 200 avec « Compte inconnu » et un unique WARN sans pseudo ni identifiant de Compte")
    void ca9_compte_inconnu_conserve_la_ligne() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno, chloe);
        jdbc.update("delete from compte where id = ?", idBruno);
        demarrerJournal();

        HttpResponse<String> reponse = lister(nadia, x);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode inscrits = json.readTree(reponse.body()).get("inscrits");
        assertThat(inscrits).extracting(n -> n.get("pseudo").asString())
                .containsExactly("Alice", "Compte inconnu", "Chloé");
        assertThat(inscrits).extracting(n -> n.get("dossard").asInt()).containsExactly(1, 2, 3);
        List<ILoggingEvent> avertissements = journal.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
        assertThat(avertissements).hasSize(1);
        assertThat(avertissements.get(0).getFormattedMessage())
                .doesNotContain("Bruno", idBruno.toString(), idAlice.toString(), idChloe.toString());
        assertThat(journal.list).noneSatisfy(
                e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO) && !e.getLevel().equals(Level.WARN)
                        && e.getLoggerName().startsWith("fr.backyard")).isTrue());
    }

    @Test
    @DisplayName("CA9 : aucun nouveau changeset (0002 à 0007 exactement), ddl-auto=validate, fiche inchangée")
    void ca9_aucune_migration_et_fiche_inchangee() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno);

        HttpResponse<String> fiche = api.requete("GET", CHEMIN_ADMIN + "/" + x, nadia.enteteLecture(), null, null);

        assertThat(lister(nadia, x).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(fiche.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(fiche.body());
        assertThat(corps.get("id").asString()).isEqualTo(x);
        assertThat(corps.propertyNames()).doesNotContain("inscrits", "nombreInscrits", "placesRestantes");
        assertThat(fiche.body()).doesNotContain("Alice", "Bruno", "jetonQr");
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : le nombre d'instructions SQL de la lecture est identique avec 3 et avec 30 inscrits (pseudos en une requête)")
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
            assertThat(lister(nadia, petite).statusCode()).isEqualTo(200);

            long avantPetite = instructions(statistiques);
            JsonNode corpsPetite = json.readTree(lister(nadia, petite).body());
            long pourTrois = instructions(statistiques) - avantPetite;
            long avantGrande = instructions(statistiques);
            JsonNode corpsGrande = json.readTree(lister(nadia, grande).body());
            long pourTrente = instructions(statistiques) - avantGrande;

            assertThat(corpsPetite.get("inscrits")).hasSize(3);
            assertThat(corpsGrande.get("inscrits")).hasSize(30);
            assertThat(corpsGrande.get("inscrits").get(29).get("pseudo").asString()).isEqualTo("Coureur30");
            assertThat(pourTrente).isEqualTo(pourTrois);
        } finally {
            statistiques.setStatisticsEnabled(false);
        }
    }

    @Test
    @DisplayName("CA10 : désinscription d'Alice puis inscription de Chloé : nombreInscrits, placesRestantes et inscrits restent cohérents")
    void ca10_coherence_apres_desinscription_et_inscription() throws Exception {
        String x = creerCourse("Backyard des Crêtes", 3);
        inscrireDansLOrdre(x, alice, bruno);
        JsonNode avant = json.readTree(lister(nadia, x).body());
        assertThat(avant.get("nombreInscrits").asInt()).isEqualTo(2);
        assertThat(avant.get("placesRestantes").asInt()).isEqualTo(1);
        assertThat(avant.get("complete").asBoolean()).isFalse();

        assertThat(api.requete("DELETE", "/api/coureur/inscriptions/" + jdbc.queryForObject(
                "select id from inscription where compte_id = ?", UUID.class, idAlice), alice.entetes(), null, null)
                .statusCode()).isEqualTo(204);
        JsonNode apresDesinscription = json.readTree(lister(nadia, x).body());
        assertCoherent(apresDesinscription);
        assertThat(apresDesinscription.get("nombreInscrits").asInt()).isEqualTo(1);
        assertThat(apresDesinscription.get("placesRestantes").asInt()).isEqualTo(2);

        assertThat(api.requete("POST", CHEMIN_COURSES + "/" + x + "/inscriptions", chloe.entetes(), null, null)
                .statusCode()).isEqualTo(201);
        JsonNode apres = json.readTree(lister(nadia, x).body());
        assertCoherent(apres);
        assertThat(apres.get("nombreInscrits").asInt()).isEqualTo(2);
        assertThat(apres.get("placesRestantes").asInt()).isEqualTo(1);
        assertThat(apres.get("inscrits")).extracting(n -> n.get("pseudo").asString())
                .containsExactlyInAnyOrder("Bruno", "Chloé");
        assertThat(apres.get("inscrits")).extracting(n -> n.get("pseudo").asString()).doesNotContain("Alice");
    }

    // ---------------------------------------------------------------- utilitaires

    private void assertCoherent(JsonNode corps) {
        int n = corps.get("inscrits").size();
        int m = corps.get("nombreMaxParticipants").asInt();
        assertThat(corps.get("nombreInscrits").asInt()).isEqualTo(n);
        assertThat(corps.get("placesRestantes").asInt()).isEqualTo(Math.max(0, m - n));
        assertThat(corps.get("complete").asBoolean()).isEqualTo(n >= m);
    }

    private long instructions(Statistics statistiques) {
        return statistiques.getPrepareStatementCount();
    }

    private String chemin(String idCourse) {
        return CHEMIN_ADMIN + "/" + idCourse + "/inscriptions";
    }

    private HttpResponse<String> lister(Session session, String idCourse) throws Exception {
        return api.requete("GET", chemin(idCourse), session.enteteLecture(), null, null);
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
