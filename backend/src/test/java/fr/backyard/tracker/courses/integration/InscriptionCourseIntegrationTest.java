package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Contrat de l'incrément 3.1 : s'inscrire à une course (CA4 à CA10). Le jeton QR ne se lit qu'en base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class InscriptionCourseIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN_ADMIN = "/api/administration/courses";
    static final String CHEMIN = "/api/coureur/courses";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String INTROUVABLE_DETAIL = "La course est introuvable.";
    static final String DEJA_INSCRIT_DETAIL = "Vous êtes déjà inscrit à cette course.";
    static final String NOM_X = "Backyard des Crêtes";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final Pattern BASE64_URL_43 = Pattern.compile("[A-Za-z0-9_-]{43}");
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
    org.springframework.core.env.Environment environnement;

    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    ListAppender<ILoggingEvent> journal;
    String empreinte;
    Session patron;
    Session nadia;
    Session alice;
    Session bruno;
    Session leo;
    UUID idNadia;
    UUID idAlice;
    UUID idBruno;
    UUID idLeo;

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
        idNadia = insererCompte("Nadia", "ADMIN");
        idAlice = insererCompte("Alice", "COUREUR");
        idBruno = insererCompte("Bruno", "COUREUR");
        idLeo = insererCompte("Léo", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        bruno = api.ouvrir("Bruno", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
    }

    @AfterEach
    void nettoyer() {
        arreterJournal();
    }

    // ---------------------------------------------------------------- CA4

    @Test
    @DisplayName("CA4 : Alice puis Bruno s'inscrivent à X (dossards 1 et 2), Bruno à Y (dossard 1) : 201, JSON sans jetonQr, ligne en base avec jeton de 43 caractères distincts")
    void ca4_inscription_nominale_dossards_et_persistance() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        String y = creerCourse("Backyard express", "2026-12-14");

        HttpResponse<String> reponseAlice = sinscrire(alice, x);

        assertThat(reponseAlice.statusCode()).isEqualTo(201);
        assertThat(reponseAlice.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json"));
        JsonNode corps = json.readTree(reponseAlice.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("id", "courseId", "dossard", "statut");
        assertThat(corps.get("courseId").asString()).isEqualTo(x);
        assertThat(corps.get("dossard").asInt()).isEqualTo(1);
        assertThat(corps.get("statut").asString()).isEqualTo("EN_COURSE");
        assertThat(reponseAlice.body()).doesNotContain("jetonQr");

        assertThat(json.readTree(sinscrire(bruno, x).body()).get("dossard").asInt()).isEqualTo(2);
        assertThat(json.readTree(sinscrire(bruno, y).body()).get("dossard").asInt()).isEqualTo(1);

        Map<String, Object> ligneAlice = ligne(x, idAlice);
        Map<String, Object> ligneBruno = ligne(x, idBruno);
        assertThat(ligneAlice.get("id").toString()).isEqualTo(corps.get("id").asString());
        assertThat(ligneAlice.get("statut")).isEqualTo("EN_COURSE");
        assertThat(ligneAlice.get("dossard")).isEqualTo(1);
        assertThat((String) ligneAlice.get("jeton_qr")).matches(BASE64_URL_43);
        assertThat((String) ligneBruno.get("jeton_qr")).matches(BASE64_URL_43)
                .isNotEqualTo(ligneAlice.get("jeton_qr"));
        assertThat(jdbc.queryForObject("select count(*) from inscription", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("CA4 : un corps {dossard, compteId} est ignoré : l'inscription est celle d'Alice, dossard 1")
    void ca4_corps_ignore() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        ObjectNode corps = json.createObjectNode();
        corps.put("dossard", 99);
        corps.put("compteId", idBruno.toString());

        HttpResponse<String> reponse = api.requete("POST", CHEMIN + "/" + x + "/inscriptions", alice.entetes(),
                "application/json", corps.toString());

        assertThat(reponse.statusCode()).isEqualTo(201);
        assertThat(json.readTree(reponse.body()).get("dossard").asInt()).isEqualTo(1);
        assertThat(compter(x, idAlice)).isEqualTo(1);
        assertThat(compter(x, idBruno)).isZero();
    }

    @Test
    @DisplayName("CA4 : second POST d'Alice sur X : 409 INSCRIPTION_DEJA_EXISTANTE, une seule ligne, aucun dossard consommé")
    void ca4_doublon_refuse_sans_consommer_de_dossard() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);

        assertErreur(sinscrire(alice, x), 409, "INSCRIPTION_DEJA_EXISTANTE", "Conflit", DEJA_INSCRIT_DETAIL);

        assertThat(compter(x, idAlice)).isEqualTo(1);
        assertThat(json.readTree(sinscrire(bruno, x).body()).get("dossard").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA4 : POST sur un id inconnu ou non UUID : 404 COURSE_INTROUVABLE, jamais 400 ; aucune ligne créée")
    void ca4_course_inconnue_ou_non_uuid() throws Exception {
        creerCourse(NOM_X, "2026-11-14");
        for (String id : List.of(ID_INCONNU, "inexistant")) {
            assertErreur(sinscrire(alice, id), 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
        }
        assertThat(jdbc.queryForObject("select count(*) from inscription", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA4 : un POST après suppression de X par Patron donne 404 COURSE_INTROUVABLE")
    void ca4_course_supprimee() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        assertThat(api.requete("DELETE", CHEMIN_ADMIN + "/" + x, patron.entetes(), null, null).statusCode())
                .isEqualTo(204);

        assertErreur(sinscrire(alice, x), 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : GET liste exactement X et Y (ni EN_COURS ni TERMINEE), ordre date décroissante, monInscription renseignée ou null, logoUrl, aucun champ interdit")
    void ca5_liste_des_courses_ouvertes() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        String y = creerCourse("Backyard express", "2026-12-14");
        String z = creerCourse("Course démarrée", "2026-11-01");
        String w = creerCourse("Course terminée", "2026-10-20");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        changerStatut(z, "EN_COURS");
        changerStatut(w, "TERMINEE");
        String inscriptionAlice = json.readTree(sinscrire(alice, x).body()).get("id").asString();
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);

        HttpResponse<String> reponse = lister(alice);

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode liste = json.readTree(reponse.body());
        assertThat(liste).hasSize(2);
        assertThat(liste.get(0).get("id").asString()).isEqualTo(y);
        assertThat(liste.get(1).get("id").asString()).isEqualTo(x);
        JsonNode courseY = liste.get(0);
        JsonNode courseX = liste.get(1);
        assertThat(courseY.get("monInscription").isNull()).isTrue();
        assertThat(courseY.get("logoUrl").isNull()).isTrue();
        assertThat(courseX.get("logoUrl").isNull()).isFalse();
        JsonNode inscription = courseX.get("monInscription");
        assertThat(inscription.get("id").asString()).isEqualTo(inscriptionAlice);
        assertThat(inscription.get("courseId").asString()).isEqualTo(x);
        assertThat(inscription.get("dossard").asInt()).isEqualTo(1);
        assertThat(inscription.get("statut").asString()).isEqualTo("EN_COURSE");
        assertThat(courseX.get("nom").asString()).isEqualTo(NOM_X);
        assertThat(courseX.get("date").asString()).isEqualTo("2026-11-14");
        assertThat(courseX.get("distanceBoucleMetres").asInt()).isEqualTo(6706);
        assertThat(courseX.get("nombreMaxParticipants").asInt()).isEqualTo(50);
        assertThat(courseX.propertyNames()).doesNotContain("benevoleIds", "statut", "jetonQr");
        assertThat(reponse.body()).doesNotContain("jetonQr", "benevoleIds");

        JsonNode pourBruno = json.readTree(lister(bruno).body());
        assertThat(pourBruno.get(1).get("monInscription").get("dossard").asInt()).isEqualTo(2);
        assertThat(pourBruno.get(0).get("monInscription").isNull()).isTrue();
    }

    @Test
    @DisplayName("CA5 : même date, l'ordre est le nom ; sans aucune course ouverte : [] ; X passée EN_COURS en base disparaît au prochain appel sans reconnexion")
    void ca5_ordre_liste_vide_et_effet_immediat() throws Exception {
        assertThat(json.readTree(lister(alice).body())).isEmpty();
        String b = creerCourse("B course", "2026-11-14");
        String a = creerCourse("A course", "2026-11-14");

        assertThat(ids(lister(alice))).containsExactly(a, b);

        changerStatut(a, "EN_COURS");
        assertThat(ids(lister(alice))).containsExactly(b);
        changerStatut(b, "TERMINEE");
        assertThat(json.readTree(lister(alice).body())).isEmpty();
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : anonyme 401 NON_AUTHENTIFIE ; Nadia, Patron et Léo (affecté) 403 ACCES_REFUSE sur GET et POST ; aucune inscription créée")
    void ca6_roles_et_authentification() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        assertThat(api.requete("PUT", CHEMIN_ADMIN + "/" + x + "/benevoles", patron.entetes(), "application/json",
                "{\"benevoleIds\":[\"" + idLeo + "\"]}").statusCode()).isEqualTo(200);
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        assertErreur(api.requete("GET", CHEMIN, Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.requete("POST", CHEMIN + "/" + x + "/inscriptions", anonyme, null, null), 401,
                "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        for (Session session : List.of(nadia, patron, leo)) {
            assertErreur(lister(session), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(sinscrire(session, x), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        }
        assertErreur(sinscrire(nadia, ID_INCONNU), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(sinscrire(nadia, "inexistant"), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);
        assertErreur(sinscrire(nadia, x), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertThat(jdbc.queryForObject("select count(*) from inscription", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA6 : Alice sans X-XSRF-TOKEN, avec un jeton différent ou sans cookie : 403 CSRF_INVALIDE avant le 404 et le 409 ; aucune inscription")
    void ca6_csrf() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        List<Map<String, String>> variantes = List.of(alice.enteteLecture(),
                Map.of("Cookie", "JSESSIONID=" + alice.id() + "; XSRF-TOKEN=" + alice.xsrf(), "X-XSRF-TOKEN",
                        "autre-valeur"),
                Map.of("X-XSRF-TOKEN", alice.xsrf()));

        for (Map<String, String> entetes : variantes) {
            for (String cible : List.of(x, ID_INCONNU)) {
                assertErreur(api.requete("POST", CHEMIN + "/" + cible + "/inscriptions", entetes, null, null), 403,
                        "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from inscription", Integer.class)).isZero();
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);
        assertErreur(api.requete("POST", CHEMIN + "/" + x + "/inscriptions", alice.enteteLecture(), null, null), 403,
                "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
    }

    @Test
    @DisplayName("CA6 : DELETE, PUT et PATCH sur /api/coureur/courses/X/inscriptions par Alice : 404 ou 405 ; logo, /api/sante et /api/csrf restent publics")
    void ca6_autres_methodes_et_chemins_publics() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        assertThat(envoyerLogo(patron, x).statusCode()).isEqualTo(200);
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);

        for (String methode : List.of("DELETE", "PUT", "PATCH")) {
            assertThat(api.requete(methode, CHEMIN + "/" + x + "/inscriptions", alice.entetes(), "application/json",
                    "PUT".equals(methode) || "PATCH".equals(methode) ? "{}" : null).statusCode())
                    .as(methode).isIn(404, 405);
        }
        assertThat(api.requete("GET", CHEMIN + "/" + x + "/inscriptions", alice.enteteLecture(), null, null)
                .statusCode()).isIn(404, 405);
        assertThat(compter(x, idAlice)).isEqualTo(1);

        HttpRequest logo = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/courses/" + x
                + "/logo")).GET().build();
        assertThat(client.send(logo, HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(200);
        assertThat(api.requete("GET", "/api/sante", Map.of(), null, null).statusCode()).isNotIn(401, 403);
        assertThat(api.requete("GET", "/api/csrf", Map.of(), null, null).statusCode()).isIn(200, 204);
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : 20 coureurs s'inscrivent simultanément à X : 20 réponses 201, dossards exactement {1..20}, 20 lignes en base")
    void ca7_vingt_inscriptions_simultanees() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        List<Session> coureurs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String pseudo = "Coureur" + i;
            insererCompte(pseudo, "COUREUR");
            coureurs.add(api.ouvrir(pseudo, MOT_DE_PASSE));
        }

        List<Callable<HttpResponse<String>>> taches = new ArrayList<>();
        coureurs.forEach(session -> taches.add(() -> sinscrire(session, x)));
        List<HttpResponse<String>> reponses = lancerEnParallele(taches);

        assertThat(reponses).extracting(HttpResponse::statusCode).containsOnly(201);
        TreeSet<Integer> dossards = new TreeSet<>();
        for (HttpResponse<String> reponse : reponses) {
            dossards.add(json.readTree(reponse.body()).get("dossard").asInt());
        }
        assertThat(dossards).hasSize(20).first().isEqualTo(1);
        assertThat(dossards).last().isEqualTo(20);
        assertThat(jdbc.queryForList("select dossard from inscription where course_id = ? order by dossard",
                Integer.class, UUID.fromString(x))).containsExactlyElementsOf(dossards);
    }

    @Test
    @DisplayName("CA7 : deux POST simultanés d'Alice sur Y : un 201 et un 409, une seule ligne (plusieurs tours)")
    void ca7_meme_coureur_deux_fois_en_parallele() throws Exception {
        for (int tour = 0; tour < 5; tour++) {
            String y = creerCourse("Backyard express " + tour, "2026-12-14");

            List<HttpResponse<String>> reponses = lancerEnParallele(
                    List.of(() -> sinscrire(alice, y), () -> sinscrire(alice, y)));

            assertThat(reponses).as("tour " + tour).extracting(HttpResponse::statusCode)
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(compter(y, idAlice)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("CA7 : POST de Bruno et DELETE de X par Patron simultanés : jamais de 500, POST 201 ou 404, à la fin aucune ligne course ni inscription pour X (plusieurs tours)")
    void ca7_inscription_et_suppression_concurrentes() throws Exception {
        for (int tour = 0; tour < 8; tour++) {
            String x = creerCourse("Concurrence " + tour, "2026-11-14");

            List<HttpResponse<String>> reponses = lancerEnParallele(List.of(
                    () -> sinscrire(bruno, x),
                    () -> api.requete("DELETE", CHEMIN_ADMIN + "/" + x, patron.entetes(), null, null)));

            assertThat(reponses.get(0).statusCode()).as("POST, tour " + tour).isIn(201, 404);
            assertThat(reponses.get(1).statusCode()).as("DELETE, tour " + tour).isEqualTo(204);
            assertThat(jdbc.queryForObject("select count(*) from course where id = ?", Integer.class,
                    UUID.fromString(x))).isZero();
            assertThat(jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                    UUID.fromString(x))).isZero();
        }
    }

    @Test
    @DisplayName("CA7 : l'insertion SQL directe d'un doublon (X, dossard) ou (X, Alice) est refusée par la base ; (Y, dossard 1) reste permis")
    void ca7_contraintes_uniques_en_base() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        String y = creerCourse("Backyard express", "2026-12-14");
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);

        assertThatThrownBy(() -> insererInscription(x, idBruno, 1, jetonUnique(), "EN_COURSE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_inscription_course_dossard");
        assertThatThrownBy(() -> insererInscription(x, idAlice, 7, jetonUnique(), "EN_COURSE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_inscription_course_compte");
        assertThat(jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                UUID.fromString(x))).isEqualTo(1);
        insererInscription(y, idBruno, 1, jetonUnique(), "EN_COURSE");
        assertThat(compter(y, idBruno)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : databasechangelog contient exactement 0002 à 0007 ; la table inscription a colonnes, contraintes et index de RG11 ; aucune clé étrangère vers compte ; ddl-auto=validate")
    void ca8_schema_de_la_table_inscription() {
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactlyElementsOf(CHANGESETS);
        assertThat(environnement.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");

        Map<String, String> colonnes = new java.util.HashMap<>();
        jdbc.query("select column_name, data_type || '/' || coalesce(character_maximum_length::text, '') || '/' "
                        + "|| is_nullable from information_schema.columns where table_name = 'inscription'",
                rs -> {
                    colonnes.put(rs.getString(1), rs.getString(2));
                });
        assertThat(colonnes).containsOnly(
                Map.entry("id", "uuid//NO"), Map.entry("course_id", "uuid//NO"), Map.entry("compte_id", "uuid//NO"),
                Map.entry("dossard", "integer//NO"), Map.entry("jeton_qr", "character varying/64/NO"),
                Map.entry("statut", "character varying/20/NO"));

        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid = 'inscription'::regclass",
                String.class)).containsExactlyInAnyOrder("pk_inscription", "fk_inscription_course",
                "uq_inscription_course_dossard", "uq_inscription_course_compte", "uq_inscription_jeton_qr",
                "ck_inscription_dossard", "ck_inscription_statut");
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conrelid = 'inscription'::regclass "
                + "and contype = 'f' and confrelid = 'compte'::regclass", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select confdeltype::text from pg_constraint "
                + "where conname = 'fk_inscription_course'", String.class)).isEqualTo("c");
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where tablename = 'inscription' "
                + "and indexname = 'ix_inscription_compte' and indexdef like '%(compte_id)%'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("CA8 : delete from course en SQL supprime les 2 inscriptions de X et laisse celle de Y")
    void ca8_cascade_en_sql_direct() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        String y = creerCourse("Backyard express", "2026-12-14");
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(alice, y).statusCode()).isEqualTo(201);

        jdbc.update("delete from course where id = ?", UUID.fromString(x));

        assertThat(jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                UUID.fromString(x))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                UUID.fromString(y))).isEqualTo(1);
    }

    @Test
    @DisplayName("CA8 : DELETE /api/administration/courses/X par Patron : 204, 0 inscription pour X, celle de Y subsiste, Alice et Bruno existent encore")
    void ca8_cascade_par_l_api() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        String y = creerCourse("Backyard express", "2026-12-14");
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(bruno, x).statusCode()).isEqualTo(201);
        assertThat(sinscrire(alice, y).statusCode()).isEqualTo(201);

        assertThat(api.requete("DELETE", CHEMIN_ADMIN + "/" + x, patron.entetes(), null, null).statusCode())
                .isEqualTo(204);

        assertThat(jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                UUID.fromString(x))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from inscription where course_id = ?", Integer.class,
                UUID.fromString(y))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from compte where id in (?, ?)", Integer.class, idAlice,
                idBruno)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA8 : insertion avec dossard 0, statut inconnu, course_id inexistant ou jeton déjà utilisé : refusée par la base")
    void ca8_contraintes_de_validite() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        assertThat(sinscrire(alice, x).statusCode()).isEqualTo(201);
        String jetonAlice = jdbc.queryForObject("select jeton_qr from inscription", String.class);

        assertThatThrownBy(() -> insererInscription(x, idBruno, 0, jetonUnique(), "EN_COURSE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_inscription_dossard");
        assertThatThrownBy(() -> insererInscription(x, idBruno, 5, jetonUnique(), "INCONNU"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_inscription_statut");
        assertThatThrownBy(() -> insererInscription(ID_INCONNU, idBruno, 5, jetonUnique(), "EN_COURSE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_inscription_course");
        assertThatThrownBy(() -> insererInscription(x, idBruno, 5, jetonAlice, "EN_COURSE"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_inscription_jeton_qr");
        assertThat(jdbc.queryForObject("select count(*) from inscription", Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : une ligne INFO « Inscription enregistrée » (course, dossard) ; rien en INFO ou plus pour 409, 404 et 403 ; ni pseudo, ni UUID, ni jeton, ni nom de course dans le journal ni dans les réponses")
    void ca9_journal() throws Exception {
        String x = creerCourse(NOM_X, "2026-11-14");
        demarrerJournal();

        HttpResponse<String> succes = sinscrire(alice, x);
        assertThat(succes.statusCode()).isEqualTo(201);
        int apresSucces = journal.list.size();
        HttpResponse<String> conflit = sinscrire(alice, x);
        HttpResponse<String> introuvable = sinscrire(alice, ID_INCONNU);
        HttpResponse<String> refus = sinscrire(nadia, x);
        assertThat(conflit.statusCode()).isEqualTo(409);
        assertThat(introuvable.statusCode()).isEqualTo(404);
        assertThat(refus.statusCode()).isEqualTo(403);

        List<ILoggingEvent> inscriptions = journal.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Inscription enregistrée")).toList();
        assertThat(inscriptions).hasSize(1);
        assertThat(inscriptions.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(inscriptions.get(0).getFormattedMessage()).contains(x).contains("1");
        assertThat(journal.list.subList(apresSucces, journal.list.size()))
                .noneSatisfy(e -> assertThat(e.getLevel().isGreaterOrEqual(Level.INFO)).isTrue());
        String jeton = jdbc.queryForObject("select jeton_qr from inscription", String.class);
        List<String> interdits = List.of("Alice", idAlice.toString(), jeton, NOM_X);
        // Tous niveaux pour le code de l'application ; INFO et plus pour les bibliothèques (le DEBUG de Spring
        // Security affiche le principal de la session, hors du code de l'application).
        assertThat(journal.list).filteredOn(e -> e.getLoggerName().startsWith("fr.backyard")
                        || e.getLevel().isGreaterOrEqual(Level.INFO))
                .noneSatisfy(e -> assertThat(e.getFormattedMessage()).containsAnyOf(interdits.toArray(new String[0])));
        for (HttpResponse<String> reponse : List.of(succes, conflit, introuvable, refus)) {
            assertThat(reponse.body()).doesNotContain(interdits.toArray(new String[0]));
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : /api/administration/** et /api/benevole/** gardent leurs rôles malgré la règle /api/coureur/** ; sept changesets au total")
    void ca10_non_regression_des_roles() throws Exception {
        assertThat(api.requete("GET", CHEMIN_ADMIN, nadia.enteteLecture(), null, null).statusCode()).isEqualTo(200);
        assertErreur(api.requete("GET", CHEMIN_ADMIN, alice.enteteLecture(), null, null), 403, "ACCES_REFUSE",
                "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(api.requete("GET", CHEMIN_ADMIN, leo.enteteLecture(), null, null), 403, "ACCES_REFUSE",
                "Accès refusé", ACCES_REFUSE_DETAIL);
        assertThat(api.requete("GET", "/api/benevole/courses", leo.enteteLecture(), null, null).statusCode())
                .isEqualTo(200);
        assertErreur(api.requete("GET", "/api/benevole/courses", alice.enteteLecture(), null, null), 403,
                "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(api.requete("GET", "/api/benevole/courses", Map.of(), null, null), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
        assertThat(lister(alice).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .hasSize(6).containsExactlyElementsOf(CHANGESETS);
    }

    // ---------------------------------------------------------------- utilitaires

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

    private List<String> ids(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(200);
        List<String> resultat = new ArrayList<>();
        json.readTree(reponse.body()).forEach(n -> resultat.add(n.get("id").asString()));
        return resultat;
    }

    private int compter(String idCourse, UUID idCompte) {
        return jdbc.queryForObject("select count(*) from inscription where course_id = ? and compte_id = ?",
                Integer.class, UUID.fromString(idCourse), idCompte);
    }

    private Map<String, Object> ligne(String idCourse, UUID idCompte) {
        return jdbc.queryForMap("select * from inscription where course_id = ? and compte_id = ?",
                UUID.fromString(idCourse), idCompte);
    }

    private void insererInscription(String idCourse, UUID idCompte, int dossard, String jeton, String statut) {
        jdbc.update(INSERTION_INSCRIPTION, UUID.randomUUID(), UUID.fromString(idCourse), idCompte, dossard, jeton,
                statut);
    }

    private String jetonUnique() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void changerStatut(String idCourse, String statut) {
        jdbc.update("update course set statut = ? where id = ?", statut, UUID.fromString(idCourse));
    }

    private UUID insererCompte(String pseudo, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinteOuCalculee(), role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    private String empreinteOuCalculee() {
        return empreinte != null ? empreinte : encodeur.encode(MOT_DE_PASSE);
    }

    private ObjectNode corpsCourse(String nom, String date) {
        ObjectNode corps = json.createObjectNode();
        corps.put("nom", nom);
        corps.put("date", date);
        corps.put("distanceBoucleMetres", 6706);
        corps.put("dureeBoucleMinutes", 60);
        corps.put("denivelePositifBoucleMetres", 120);
        corps.put("nombreMaxParticipants", 50);
        corps.put("nombreMaxBoucles", 24);
        return corps;
    }

    private String creerCourse(String nom, String date) throws Exception {
        HttpResponse<String> reponse = api.requete("POST", CHEMIN_ADMIN, patron.entetes(), "application/json",
                corpsCourse(nom, date).toString());
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
