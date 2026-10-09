package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.OctetsDeLogo;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
 * Contrat de l'incrément 3.3 : GET /api/coureur/inscriptions, « Mes inscriptions » (CA3 à CA7). Le jeton QR de
 * référence est lu en base, indépendamment de l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class MesInscriptionsIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN_ADMIN = "/api/administration/courses";
    static final String CHEMIN_COURSES = "/api/coureur/courses";
    static final String CHEMIN = "/api/coureur/inscriptions";
    static final String NOM_X = "Backyard des Crêtes";
    static final String NOM_Y = "Backyard express";
    static final String NOM_Z = "Backyard hiver";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final Pattern BASE64_URL_43 = Pattern.compile("[A-Za-z0-9_-]{43}");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
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
    org.springframework.core.env.Environment environnement;

    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
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
    UUID idNadia;
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
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    // ---------------------------------------------------------------- CA3

    @Test
    @DisplayName("CA3 : Alice (X, Y, Z) lit 3 inscriptions dans l'ordre Y, X, Z avec tous les champs, jetons égaux à la base, stables, Cache-Control no-store, sans champ du compte")
    void ca3_liste_nominale_ordre_jetons_et_en_tetes() throws Exception {
        Scenario s = scenarioAliceXYZ();

        HttpResponse<String> reponse = lister(alice);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json"));
        assertThat(reponse.headers().allValues("cache-control")).anySatisfy(
                c -> assertThat(c).contains("no-store"));
        JsonNode liste = json.readTree(reponse.body());
        assertThat(liste).hasSize(3);
        assertThat(liste.get(0).get("courseId").asString()).isEqualTo(s.y);
        assertThat(liste.get(1).get("courseId").asString()).isEqualTo(s.x);
        assertThat(liste.get(2).get("courseId").asString()).isEqualTo(s.z);

        JsonNode elementX = liste.get(1);
        assertThat(elementX.propertyNames()).containsExactlyInAnyOrder("id", "courseId", "courseNom", "courseDate",
                "courseStatut", "logoUrl", "dossard", "statut", "jetonQr");
        assertThat(elementX.get("id").asString()).isEqualTo(ligne(s.x, idAlice).get("id").toString());
        assertThat(elementX.get("courseNom").asString()).isEqualTo(NOM_X);
        assertThat(elementX.get("courseDate").asString()).isEqualTo("2026-10-10");
        assertThat(elementX.get("courseStatut").asString()).isEqualTo("EN_PREPARATION");
        assertThat(elementX.get("logoUrl").isNull()).isFalse();
        assertThat(elementX.get("dossard").asInt()).isEqualTo(1);
        assertThat(elementX.get("statut").asString()).isEqualTo("EN_COURSE");
        assertThat(liste.get(0).get("logoUrl").isNull()).isTrue();
        assertThat(liste.get(0).get("courseNom").asString()).isEqualTo(NOM_Y);
        assertThat(liste.get(2).get("courseStatut").asString()).isEqualTo("EN_COURS");
        assertThat(liste.get(2).get("courseDate").asString()).isEqualTo("2025-12-01");
        assertThat(liste.get(2).get("dossard").asInt()).isEqualTo(3);

        List<String> jetons = new ArrayList<>();
        for (JsonNode element : liste) {
            String jeton = element.get("jetonQr").asString();
            assertThat(jeton).matches(BASE64_URL_43);
            assertThat(jeton).isNotEqualTo(String.valueOf(element.get("dossard").asInt()));
            assertThat(jeton).isEqualTo(jdbc.queryForObject("select jeton_qr from inscription where id = ?",
                    String.class, UUID.fromString(element.get("id").asString())));
            jetons.add(jeton);
        }
        assertThat(new HashSet<>(jetons)).hasSize(3);
        assertThat(reponse.body()).doesNotContain("compteId", "pseudo", "benevoleIds", "Alice", idAlice.toString());

        JsonNode secondAppel = json.readTree(lister(alice).body());
        List<String> jetonsRelus = new ArrayList<>();
        secondAppel.forEach(e -> jetonsRelus.add(e.get("jetonQr").asString()));
        assertThat(jetonsRelus).containsExactlyElementsOf(jetons);
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Bruno ne voit que X (dossard 2, son jeton) ; ?compteId d'Alice est ignoré ; pas de lecture par identifiant")
    void ca4_isolation_entre_coureurs() throws Exception {
        Scenario s = scenarioAliceXYZ();
        assertThat(sinscrire(bruno, s.x).statusCode()).isEqualTo(201);
        String jetonAlice = jdbc.queryForObject("select jeton_qr from inscription where course_id = ? and compte_id = ?",
                String.class, UUID.fromString(s.x), idAlice);
        String idInscriptionAlice = ligne(s.x, idAlice).get("id").toString();

        HttpResponse<String> reponse = lister(bruno);
        HttpResponse<String> avecParametre = api.requete("GET", CHEMIN + "?compteId=" + idAlice,
                bruno.enteteLecture(), null, null);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode liste = json.readTree(reponse.body());
        assertThat(liste).hasSize(1);
        assertThat(liste.get(0).get("courseId").asString()).isEqualTo(s.x);
        assertThat(liste.get(0).get("dossard").asInt()).isEqualTo(2);
        String jetonBruno = jdbc.queryForObject("select jeton_qr from inscription where course_id = ? and compte_id = ?",
                String.class, UUID.fromString(s.x), idBruno);
        assertThat(liste.get(0).get("jetonQr").asString()).isEqualTo(jetonBruno).isNotEqualTo(jetonAlice);
        assertThat(reponse.body()).doesNotContain(jetonAlice, idInscriptionAlice, s.y, s.z, NOM_Y, NOM_Z);
        assertThat(avecParametre.statusCode()).isEqualTo(200);
        assertThat(avecParametre.body()).isEqualTo(reponse.body());

        HttpResponse<String> parId = api.requete("GET", CHEMIN + "/" + idInscriptionAlice, bruno.enteteLecture(),
                null, null);
        assertThat(parId.statusCode()).isIn(404, 405);
        assertThat(parId.body()).doesNotContain(jetonAlice);
    }

    @Test
    @DisplayName("CA4 : un coureur sans inscription reçoit 200 []")
    void ca4_coureur_sans_inscription() throws Exception {
        scenarioAliceXYZ();

        HttpResponse<String> reponse = lister(chloe);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body())).isEmpty();
    }

    @Test
    @DisplayName("CA4 : les statuts ABANDON et VAINQUEUR posés par SQL sont renvoyés tels quels, quelle que soit la course")
    void ca4_statuts_abandon_et_vainqueur() throws Exception {
        Scenario s = scenarioAliceXYZ();
        jdbc.update("update inscription set statut = 'ABANDON' where course_id = ? and compte_id = ?",
                UUID.fromString(s.y), idAlice);
        jdbc.update("update inscription set statut = 'VAINQUEUR' where course_id = ? and compte_id = ?",
                UUID.fromString(s.z), idAlice);

        JsonNode liste = json.readTree(lister(alice).body());

        assertThat(liste).hasSize(3);
        assertThat(liste.get(0).get("statut").asString()).isEqualTo("ABANDON");
        assertThat(liste.get(1).get("statut").asString()).isEqualTo("EN_COURSE");
        assertThat(liste.get(2).get("statut").asString()).isEqualTo("VAINQUEUR");
        for (JsonNode element : liste) {
            assertThat(element.get("jetonQr").asString()).matches(BASE64_URL_43);
        }
    }

    @Test
    @DisplayName("CA4 : une course supprimée par Patron (204) disparaît de la liste d'Alice, qui répond toujours 200")
    void ca4_course_supprimee_disparait() throws Exception {
        Scenario s = scenarioAliceXYZ();

        assertThat(api.requete("DELETE", CHEMIN_ADMIN + "/" + s.y, patron.entetes(), null, null).statusCode())
                .isEqualTo(204);

        HttpResponse<String> reponse = lister(alice);
        assertThat(reponse.statusCode()).isEqualTo(200);
        List<String> ids = new ArrayList<>();
        json.readTree(reponse.body()).forEach(e -> ids.add(e.get("courseId").asString()));
        assertThat(ids).containsExactly(s.x, s.z);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : anonyme 401 NON_AUTHENTIFIE ; Nadia, Patron et Léo (affecté) 403 ACCES_REFUSE, sans jeton dans la réponse")
    void ca5_authentification_et_roles() throws Exception {
        Scenario s = scenarioAliceXYZ();
        assertThat(api.requete("PUT", CHEMIN_ADMIN + "/" + s.x + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + idLeo + "\"]}").statusCode()).isEqualTo(200);
        List<String> jetons = jdbc.queryForList("select jeton_qr from inscription", String.class);
        assertThat(jetons).hasSize(5);

        HttpResponse<String> anonyme = api.requete("GET", CHEMIN, Map.of(), null, null);
        assertErreur(anonyme, 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertThat(anonyme.body()).doesNotContain(jetons.toArray(new String[0]));
        for (Session session : List.of(nadia, patron, leo)) {
            HttpResponse<String> refus = lister(session);
            assertErreur(refus, 403, "ACCES_REFUSE", "Accès refusé", "Vous n'avez pas les droits nécessaires.");
            assertThat(refus.body()).doesNotContain(jetons.toArray(new String[0])).doesNotContain("jetonQr");
        }
    }

    @Test
    @DisplayName("CA5 : POST, PUT, PATCH et DELETE sur /api/coureur/inscriptions par Alice (CSRF valide) : 404 ou 405, aucune inscription modifiée")
    void ca5_methodes_non_prevues() throws Exception {
        scenarioAliceXYZ();
        List<Map<String, Object>> avant = jdbc.queryForList("select * from inscription order by id");

        for (String methode : List.of("POST", "PUT", "PATCH", "DELETE")) {
            boolean corps = !"DELETE".equals(methode);
            assertThat(api.requete(methode, CHEMIN, alice.entetes(), corps ? "application/json" : null,
                    corps ? "{}" : null).statusCode()).as(methode).isIn(404, 405);
        }

        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA5 : Alice sans X-XSRF-TOKEN : le GET reste en 200 (pas de CSRF sur une lecture)")
    void ca5_lecture_sans_csrf() throws Exception {
        scenarioAliceXYZ();

        HttpResponse<String> reponse = api.requete("GET", CHEMIN, alice.enteteLecture(), null, null);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(json.readTree(reponse.body())).hasSize(3);
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : le jeton de W n'apparaît que dans GET /api/coureur/inscriptions : ni dans le 201, ni dans la liste des courses, ni dans les ProblemDetail, ni dans le journal ; aucune ligne INFO pour les lectures et refus")
    void ca6_jeton_expose_une_seule_fois_et_journal() throws Exception {
        scenarioAliceXYZ();
        String w = creerCourse("Backyard W", "2026-12-20");
        demarrerJournal();

        HttpResponse<String> creation = sinscrire(alice, w);
        assertThat(creation.statusCode()).isEqualTo(201);
        int apresCreation = journal.list.size();
        HttpResponse<String> mesInscriptions = lister(alice);
        HttpResponse<String> courses = api.requete("GET", CHEMIN_COURSES, alice.enteteLecture(), null, null);
        HttpResponse<String> anonyme = api.requete("GET", CHEMIN, Map.of(), null, null);
        HttpResponse<String> refus = lister(nadia);

        assertThat(mesInscriptions.statusCode()).isEqualTo(200);
        assertThat(courses.statusCode()).isEqualTo(200);
        assertThat(anonyme.statusCode()).isEqualTo(401);
        assertThat(refus.statusCode()).isEqualTo(403);
        String jetonW = jdbc.queryForObject("select jeton_qr from inscription where course_id = ? and compte_id = ?",
                String.class, UUID.fromString(w), idAlice);
        assertThat(mesInscriptions.body()).contains(jetonW);
        for (HttpResponse<String> reponse : List.of(creation, courses, anonyme, refus)) {
            assertThat(reponse.body()).doesNotContain(jetonW).doesNotContain("jetonQr");
        }
        assertThat(journal.list.subList(apresCreation, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        List<String> interdits = new ArrayList<>(List.of("Alice", idAlice.toString(), NOM_X));
        interdits.addAll(jdbc.queryForList("select jeton_qr from inscription", String.class));
        assertThat(journal.list).filteredOn(e -> e.getLoggerName().startsWith("fr.backyard")
                        || e.getLevel().isGreaterOrEqual(Level.INFO))
                .noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(interdits.toArray(new String[0])));
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : aucun nouveau changeset (0002 à 0008 exactement), ddl-auto=validate, aucune écriture par la lecture")
    void ca7_aucune_migration_et_lecture_seule() throws Exception {
        scenarioAliceXYZ();
        List<Map<String, Object>> avant = jdbc.queryForList("select * from inscription order by id");

        assertThat(lister(alice).statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForList("select * from inscription order by id")).isEqualTo(avant);
    }

    @Test
    @DisplayName("CA7 : logo, /api/sante et /api/csrf restent publics ; /api/administration/** et /api/benevole/** gardent leurs rôles ; GET /api/coureur/courses sans jeton ; GET courses/{id}/inscriptions 404 ou 405")
    void ca7_non_regression_acces_et_roles() throws Exception {
        Scenario s = scenarioAliceXYZ();
        HttpRequest logo = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/courses/" + s.x
                + "/logo")).GET().build();
        assertThat(client.send(logo, HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
        assertThat(api.requete("GET", "/api/sante", Map.of(), null, null).statusCode()).isNotIn(401, 403);
        assertThat(api.requete("GET", "/api/csrf", Map.of(), null, null).statusCode()).isIn(200, 204);

        assertThat(api.requete("GET", CHEMIN_ADMIN, nadia.enteteLecture(), null, null).statusCode()).isEqualTo(200);
        assertThat(api.requete("GET", CHEMIN_ADMIN, alice.enteteLecture(), null, null).statusCode()).isEqualTo(403);
        assertThat(api.requete("GET", CHEMIN_ADMIN, Map.of(), null, null).statusCode()).isEqualTo(401);
        assertThat(api.requete("GET", "/api/benevole/courses", leo.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
        assertThat(api.requete("GET", "/api/benevole/courses", alice.enteteLecture(), null, null).statusCode())
                .isEqualTo(403);
        assertThat(api.requete("GET", "/api/benevole/courses", Map.of(), null, null).statusCode()).isEqualTo(401);

        HttpResponse<String> courses = api.requete("GET", CHEMIN_COURSES, alice.enteteLecture(), null, null);
        assertThat(courses.statusCode()).isEqualTo(200);
        assertThat(courses.body()).doesNotContain("jetonQr");
        assertThat(api.requete("GET", CHEMIN_COURSES + "/" + s.x + "/inscriptions", alice.enteteLecture(), null, null)
                .statusCode()).isIn(404, 405);
    }

    // ---------------------------------------------------------------- utilitaires

    private record Scenario(String x, String y, String z) {
    }

    /** Alice inscrite à X (avec logo), Y et Z (EN_COURS, 2025-12-01, dossard 3) ; Z reçoit deux autres coureurs avant. */
    private Scenario scenarioAliceXYZ() throws Exception {
        String x = creerCourse(NOM_X, "2026-10-10");
        String y = creerCourse(NOM_Y, "2026-11-15");
        String z = creerCourse(NOM_Z, "2026-12-01");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(alice, y).statusCode()).isEqualTo(201);
        assertThat(sinscrire(creerCoureur("Zoe"), z).statusCode()).isEqualTo(201);
        assertThat(sinscrire(creerCoureur("Yan"), z).statusCode()).isEqualTo(201);
        assertThat(sinscrire(alice, z).statusCode()).isEqualTo(201);
        jdbc.update("update course set statut = 'EN_COURS', date_course = date '2025-12-01' where id = ?",
                UUID.fromString(z));
        return new Scenario(x, y, z);
    }

    private Session creerCoureur(String pseudo) throws Exception {
        insererCompte(pseudo, "COUREUR", encodeur.encode(MOT_DE_PASSE));
        return api.ouvrir(pseudo, MOT_DE_PASSE);
    }

    private HttpResponse<String> sinscrire(Session session, String idCourse) throws Exception {
        return api.requete("POST", CHEMIN_COURSES + "/" + idCourse + "/inscriptions", session.entetes(), null, null);
    }

    private HttpResponse<String> lister(Session session) throws Exception {
        return api.requete("GET", CHEMIN, session.enteteLecture(), null, null);
    }

    private Map<String, Object> ligne(String idCourse, UUID idCompte) {
        return jdbc.queryForMap("select * from inscription where course_id = ? and compte_id = ?",
                UUID.fromString(idCourse), idCompte);
    }

    private UUID insererCompte(String pseudo, String role, String empreinte) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte, role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private String creerCourse(String nom, String date) throws Exception {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", date);
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", 50);
        corps.put("nombreMaxBoucles", 24);
        HttpResponse<String> reponse = api.requete("POST", CHEMIN_ADMIN, patron.entetes(), "application/json",
                corps.toString());
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(reponse.body()).get("id").asString();
    }

    private HttpResponse<String> envoyerLogo(Session session, String idCourse) throws Exception {
        String frontiere = "----frontiere" + UUID.randomUUID();
        byte[] corps = OctetsDeLogo.concatener(
                ("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"fichier\"; filename=\"l.png\"\r\n"
                        + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8), OctetsDeLogo.png(),
                ("\r\n--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder requete = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + CHEMIN_ADMIN + "/" + idCourse + "/logo"))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + frontiere)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(corps));
        session.entetes().forEach(requete::header);
        return client.send(requete.build(), HttpResponse.BodyHandlers.ofString());
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
