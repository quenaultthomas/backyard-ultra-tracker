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
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.LogoInvalideException;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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

/**
 * Contrat de l'incrément 2.3 (logo de course) : CA9 à CA17 et CA19. Serveur réel (RANDOM_PORT) : les limites
 * multipart du conteneur sont actives, ce que MockMvc n'applique pas. Le contrat est celui de la spec, pas celui du
 * code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class LogoCourseIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/courses";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";
    static final String INTROUVABLE_DETAIL = "La course est introuvable.";
    static final String NON_MODIFIABLE_DETAIL =
            "La course n'est plus en préparation : elle ne peut plus être modifiée.";
    static final String FORMAT_DETAIL = "Le logo doit être une image PNG, JPEG ou WebP.";
    static final String TROP_VOLUMINEUX_DETAIL = "Le logo ne doit pas dépasser 2 Mo.";
    static final String ID_INCONNU = "00000000-0000-0000-0000-000000000000";
    static final Instant MIDI_PARIS = Instant.parse("2026-10-03T10:00:00Z");
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final List<String> CHAMPS_REPONSE = List.of("id", "nom", "date", "statut", "distanceBoucleMetres",
            "dureeBoucleMinutes", "denivelePositifBoucleMetres", "nombreMaxParticipants", "nombreMaxBoucles",
            "logoUrl");

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

    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ClientHttp api;
    ListAppender<ILoggingEvent> journal;
    Session patron;
    Session nadia;
    Session alice;
    Session leo;

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
        insererCompte("Nadia", "ADMIN");
        insererCompte("Alice", "COUREUR");
        insererCompte("Léo", "BENEVOLE");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        alice = api.ouvrir("Alice", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        journal = new ListAppender<>();
        journal.list = new java.util.concurrent.CopyOnWriteArrayList<>(); // liste sûre face aux threads qui journalisent
        journal.start();
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.addAppender(journal);
        racine.setLevel(Level.DEBUG);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(Level.INFO);
    }

    @AfterEach
    void nettoyer() {
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.detachAppender(journal);
        racine.setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("org.hibernate")).setLevel(null);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : Patron et Nadia envoient PNG puis JPEG puis WebP (200, dix champs, logoUrl avec v), une seule ligne logo_course cohérente, la liste renvoie le même logoUrl")
    void ca9_envoi_et_remplacement_par_les_admins() throws Exception {
        for (Session session : List.of(patron, nadia)) {
            jdbc.update("delete from course");
            String x = creerCourse(patron, "Backyard des Crêtes");
            String y = creerCourse(patron, "Piste plate");
            assertThat(json.readTree(lister(patron).body()).get(0).get("logoUrl").isNull()).isTrue();

            byte[] dernier = null;
            for (byte[] fichier : List.of(OctetsDeLogo.png(), OctetsDeLogo.jpeg(), OctetsDeLogo.webp())) {
                HttpResponse<byte[]> reponse = envoyerLogo(session, x, fichier, "logo.bin", "application/octet-stream");

                assertThat(reponse.statusCode()).isEqualTo(200);
                assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                        c -> assertThat(c).contains("application/json"));
                JsonNode course = json.readTree(texte(reponse));
                assertThat(course.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_REPONSE);
                assertThat(course.get("id").asString()).isEqualTo(x);
                assertThat(course.get("logoUrl").asString())
                        .isEqualTo("/api/courses/" + x + "/logo?v=" + OctetsDeLogo.sha256(fichier).substring(0, 12));
                dernier = fichier;
            }

            String urlAttendue = "/api/courses/" + x + "/logo?v=" + OctetsDeLogo.sha256(dernier).substring(0, 12);
            assertThat(urlAttendue).matches("/api/courses/[0-9a-f-]{36}/logo\\?v=[0-9a-f]{12}");
            Map<String, JsonNode> parId = listeParId(lister(session));
            assertThat(parId.get(x).get("logoUrl").asString()).isEqualTo(urlAttendue);
            assertThat(parId.get(y).get("logoUrl").isNull()).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from logo_course", Integer.class)).isEqualTo(1);
            Map<String, Object> ligne = jdbc.queryForMap("select type_mime, taille_octets, octet_length(contenu) "
                    + "as longueur, empreinte, contenu from logo_course where course_id = ?", UUID.fromString(x));
            assertThat(ligne.get("type_mime")).isEqualTo("image/webp");
            assertThat(ligne.get("taille_octets")).isEqualTo(dernier.length);
            assertThat(ligne.get("longueur")).isEqualTo(dernier.length);
            assertThat(ligne.get("empreinte")).isEqualTo(OctetsDeLogo.sha256(dernier));
            assertThat((byte[]) ligne.get("contenu")).isEqualTo(dernier);
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : lecture publique sans cookie (200, octets identiques, en-têtes, ETag, aucun Set-Cookie), 304 sur If-None-Match, v ignoré, remplacement par un JPEG")
    void ca10_lecture_publique() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        byte[] png = OctetsDeLogo.png();
        assertThat(envoyerLogo(patron, x, png, "logo.png", "image/jpeg").statusCode()).isEqualTo(200);
        String etag = "\"" + OctetsDeLogo.sha256(png) + "\"";

        HttpResponse<byte[]> reponse = lireLogoPublic(x, Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body()).isEqualTo(png);
        assertThat(reponse.headers().firstValue("content-type")).hasValue("image/png");
        assertThat(reponse.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(reponse.headers().firstValue("content-disposition")).hasValue("inline");
        assertThat(reponse.headers().firstValue("cache-control")).hasValue("public, no-cache");
        assertThat(reponse.headers().firstValue("content-length")).hasValue(String.valueOf(png.length));
        assertThat(reponse.headers().firstValue("etag")).hasValue(etag);
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();

        HttpResponse<byte[]> conditionnelle = lireLogoPublic(x, Map.of("If-None-Match", etag));
        assertThat(conditionnelle.statusCode()).isEqualTo(304);
        assertThat(conditionnelle.body()).isEmpty();
        assertThat(conditionnelle.headers().firstValue("etag")).hasValue(etag);
        assertThat(conditionnelle.headers().firstValue("cache-control")).hasValue("public, no-cache");

        HttpResponse<byte[]> avecVersion = api2("GET", "/api/courses/" + x + "/logo?v=autre", Map.of(), null, null);
        assertThat(avecVersion.statusCode()).isEqualTo(200);
        assertThat(avecVersion.body()).isEqualTo(png);

        byte[] jpeg = OctetsDeLogo.jpeg();
        assertThat(envoyerLogo(patron, x, jpeg, "logo.png", "image/png").statusCode()).isEqualTo(200);
        HttpResponse<byte[]> apres = lireLogoPublic(x, Map.of("If-None-Match", etag));
        assertThat(apres.statusCode()).isEqualTo(200);
        assertThat(apres.body()).isEqualTo(jpeg);
        assertThat(apres.headers().firstValue("content-type")).hasValue("image/jpeg");
        assertThat(apres.headers().firstValue("etag")).hasValue("\"" + OctetsDeLogo.sha256(jpeg) + "\"");
        assertThat(apres.headers().firstValue("etag").orElseThrow()).isNotEqualTo(etag);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : formats refusés par signature (texte nommé .png, SVG, GIF, PDF, PNG tronqué) en 415 LOGO_FORMAT_INVALIDE sans écho, ancien logo conservé")
    void ca11_formats_refuses() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        String vide = creerCourse(patron, "Sans logo");
        byte[] ancien = OctetsDeLogo.jpeg();
        assertThat(envoyerLogo(patron, x, ancien, "a.jpg", "image/jpeg").statusCode()).isEqualTo(200);
        String etag = lireLogoPublic(x, Map.of()).headers().firstValue("etag").orElseThrow();

        Map<String, byte[]> refuses = new HashMap<>();
        refuses.put("texte", OctetsDeLogo.ascii("ceci n'est pas une image"));
        refuses.put("svg", OctetsDeLogo.ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
        refuses.put("xml", OctetsDeLogo.ascii("<?xml version=\"1.0\"?><svg/>"));
        refuses.put("gif", OctetsDeLogo.ascii("GIF89a\u0001\u0000\u0001\u0000"));
        refuses.put("pdf", OctetsDeLogo.ascii("%PDF-1.7 contenu"));
        refuses.put("png tronque", java.util.Arrays.copyOf(OctetsDeLogo.png(), 7));
        for (Map.Entry<String, byte[]> refus : refuses.entrySet()) {
            for (String cible : List.of(x, vide)) {
                HttpResponse<byte[]> reponse = envoyerLogo(patron, cible, refus.getValue(), "logo-secret.png",
                        "image/x-declare-secret");
                assertErreur(reponse, 415, "LOGO_FORMAT_INVALIDE", "Format non supporté", FORMAT_DETAIL);
                assertThat(texte(reponse)).doesNotContain("logo-secret").doesNotContain("x-declare-secret");
            }
            assertThat(lireLogoPublic(x, Map.of()).headers().firstValue("etag")).as(refus.getKey()).hasValue(etag);
        }
        assertThat(jdbc.queryForObject("select count(*) from logo_course", Integer.class)).isEqualTo(1);
        assertThat(lireLogoPublic(vide, Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("CA11 : le contenu prime sur le nom et le type déclaré (vrai PNG nommé .txt accepté, JPEG déclaré PNG servi en JPEG, PNG à contenu aléatoire accepté)")
    void ca11_le_contenu_prime_sur_le_nom_et_le_type_declare() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        byte[] png = OctetsDeLogo.png();
        assertThat(envoyerLogo(patron, x, png, "logo.txt", "text/plain").statusCode()).isEqualTo(200);
        assertThat(lireLogoPublic(x, Map.of()).headers().firstValue("content-type")).hasValue("image/png");

        assertThat(envoyerLogo(patron, x, OctetsDeLogo.jpeg(), "logo.png", "image/png").statusCode()).isEqualTo(200);
        assertThat(lireLogoPublic(x, Map.of()).headers().firstValue("content-type")).hasValue("image/jpeg");

        byte[] corrompu = OctetsDeLogo.png(OctetsDeLogo.motif(300, 9));
        assertThat(envoyerLogo(patron, x, corrompu, "logo.png", "image/png").statusCode()).isEqualTo(200);
        assertThat(lireLogoPublic(x, Map.of()).body()).isEqualTo(corrompu);
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : serveur réel, 2 097 152 octets acceptés, 2 097 153 refusés en 413 LOGO_TROP_VOLUMINEUX (ancien logo conservé), fichier vide ou partie absente 400 LOGO_REQUIS, JSON 415, multipart sans boundary 400 CORPS_ILLISIBLE")
    void ca12_limites_de_taille_et_formes_de_corps() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        byte[] maximal = OctetsDeLogo.pngDeTaille(OctetsDeLogo.TAILLE_MAXIMALE);
        assertThat(envoyerLogo(patron, x, maximal, "logo.png", "image/png").statusCode()).isEqualTo(200);
        String etag = lireLogoPublic(x, Map.of()).headers().firstValue("etag").orElseThrow();
        assertThat(etag).isEqualTo("\"" + OctetsDeLogo.sha256(maximal) + "\"");

        HttpResponse<byte[]> trop = envoyerLogo(patron, x, OctetsDeLogo.pngDeTaille(OctetsDeLogo.TAILLE_MAXIMALE + 1),
                "logo.png", "image/png");
        assertErreur(trop, 413, "LOGO_TROP_VOLUMINEUX", "Fichier trop volumineux", TROP_VOLUMINEUX_DETAIL);
        assertThat(lireLogoPublic(x, Map.of()).headers().firstValue("etag")).hasValue(etag);

        assertErreur(envoyerLogo(patron, x, new byte[0], "vide.png", "image/png"), 400, "LOGO_REQUIS",
                "Requête invalide", "Le fichier du logo est obligatoire.");
        Multipart autre = new Multipart().champ("autre", "valeur");
        assertErreur(envoyerMultipart(patron, x, autre), 400, "LOGO_REQUIS", "Requête invalide",
                "Le fichier du logo est obligatoire.");

        HttpResponse<byte[]> enJson = api2("PUT", CHEMIN + "/" + x + "/logo", patron.entetes(), "application/json",
                "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(enJson.statusCode()).isEqualTo(415);
        HttpResponse<byte[]> sansBoundary = api2("PUT", CHEMIN + "/" + x + "/logo", patron.entetes(),
                "multipart/form-data", "pas du multipart".getBytes(StandardCharsets.UTF_8));
        assertErreur(sansBoundary, 400, "CORPS_ILLISIBLE", "Requête invalide", "Le corps de la requête est illisible.");
        assertThat(lireLogoPublic(x, Map.of()).headers().firstValue("etag")).hasValue(etag);

        assertThat(environnement.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("2MB");
        assertThat(OctetsDeLogo.TAILLE_MAXIMALE).isEqualTo(Logo.TAILLE_MAXIMALE_OCTETS);
        assertThatThrownBy(() -> Logo.depuis(OctetsDeLogo.pngDeTaille(OctetsDeLogo.TAILLE_MAXIMALE + 1)))
                .isInstanceOfSatisfying(LogoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(LogoInvalideException.Motif.TROP_VOLUMINEUX));
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : anonyme 401, Alice et Léo 403 ACCES_REFUSE pour PUT valide, PUT sans fichier, id inconnu et DELETE (jamais 400 ni 404), logo inchangé")
    void ca13_roles_insuffisants() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        assertThat(envoyerLogo(patron, x, OctetsDeLogo.png(), "a.png", "image/png").statusCode()).isEqualTo(200);
        String etag = lireLogoPublic(x, Map.of()).headers().firstValue("etag").orElseThrow();
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);

        for (String cible : List.of(x, ID_INCONNU)) {
            Map<String, Map<String, String>> identites = new java.util.LinkedHashMap<>();
            identites.put("anonyme", anonyme);
            identites.put("Alice", alice.entetes());
            identites.put("Léo", leo.entetes());
            for (Map.Entry<String, Map<String, String>> identite : identites.entrySet()) {
                boolean anonymeSeul = identite.getKey().equals("anonyme");
                List<HttpResponse<byte[]>> reponses = new ArrayList<>();
                Multipart valide = new Multipart().fichier("fichier", "a.png", "image/png", OctetsDeLogo.jpeg());
                reponses.add(api2("PUT", CHEMIN + "/" + cible + "/logo", identite.getValue(), valide.contentType(),
                        valide.corps()));
                Multipart sans = new Multipart().champ("autre", "v");
                reponses.add(api2("PUT", CHEMIN + "/" + cible + "/logo", identite.getValue(), sans.contentType(),
                        sans.corps()));
                reponses.add(api2("DELETE", CHEMIN + "/" + cible + "/logo", identite.getValue(), null, null));
                for (HttpResponse<byte[]> reponse : reponses) {
                    if (anonymeSeul) {
                        assertErreur(reponse, 401, "NON_AUTHENTIFIE", null, null);
                    } else {
                        assertErreur(reponse, 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
                    }
                }
            }
        }

        for (Map<String, String> entetes : List.of(Map.of("Cookie", "JSESSIONID=" + alice.id()),
                Map.of("Cookie", "JSESSIONID=" + leo.id() + "; XSRF-TOKEN=a", "X-XSRF-TOKEN", "b"),
                Map.of("Cookie", "JSESSIONID=" + patron.id()),
                Map.of("Cookie", "JSESSIONID=" + patron.id() + "; XSRF-TOKEN=a", "X-XSRF-TOKEN", "b"),
                Map.<String, String>of())) {
            Multipart valide = new Multipart().fichier("fichier", "a.png", "image/png", OctetsDeLogo.jpeg());
            assertCsrf(api2("PUT", CHEMIN + "/" + x + "/logo", entetes, valide.contentType(), valide.corps()));
            assertCsrf(api2("DELETE", CHEMIN + "/" + x + "/logo", entetes, null, null));
        }
        assertThat(lireLogoPublic(x, Map.of()).headers().firstValue("etag")).hasValue(
                "\"" + OctetsDeLogo.sha256(OctetsDeLogo.png()) + "\"");
        assertThat(etag).isNotBlank();
    }

    @Test
    @DisplayName("CA13 : seule la lecture GET du logo est publique, tout le reste sous /api/courses reste protégé (401 anonyme, 404 ou 405 connecté), GET d'administration sur le logo en 404")
    void ca13_seule_la_lecture_du_logo_est_publique() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        assertThat(envoyerLogo(patron, x, OctetsDeLogo.png(), "a.png", "image/png").statusCode()).isEqualTo(200);
        String jeton = api.jetonValide();
        Map<String, String> anonyme = Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton);
        Map<String, String> connecte = alice.entetes();

        assertThat(lireLogoPublic(x, Map.of()).statusCode()).isEqualTo(200);
        List<String[]> requetes = List.of(new String[] {"GET", "/api/courses"},
                new String[] {"GET", "/api/courses/" + x}, new String[] {"GET", "/api/courses/" + x + "/logo/extra"},
                new String[] {"HEAD", "/api/courses/" + x + "/logo"},
                new String[] {"POST", "/api/courses/" + x + "/logo"},
                new String[] {"PUT", "/api/courses/" + x + "/logo"},
                new String[] {"DELETE", "/api/courses/" + x + "/logo"});
        for (String[] requete : requetes) {
            HttpResponse<byte[]> refuse = api2(requete[0], requete[1], anonyme, null, null);
            assertThat(refuse.statusCode()).as("anonyme " + requete[0] + " " + requete[1]).isEqualTo(401);
            int statut = api2(requete[0], requete[1], connecte, null, null).statusCode();
            assertThat(statut).as("Alice " + requete[0] + " " + requete[1]).isIn(404, 405);
        }

        HttpResponse<byte[]> getAdmin = api2("GET", CHEMIN + "/" + x + "/logo", patron.enteteLecture(), null, null);
        assertThat(getAdmin.statusCode()).isIn(404, 405);
        if (getAdmin.statusCode() == 404) {
            assertThat(json.readTree(texte(getAdmin)).get("code").asString()).isEqualTo("RESSOURCE_INTROUVABLE");
        }
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : id inconnu ou non UUID, PUT (valide ou texte) et DELETE donnent 404 COURSE_INTROUVABLE sans écho, aucune ligne créée, Nadia idem")
    void ca14_course_introuvable() throws Exception {
        for (Session session : List.of(patron, nadia)) {
            for (String id : List.of(ID_INCONNU, "inexistant")) {
                for (byte[] fichier : List.of(OctetsDeLogo.png(), OctetsDeLogo.ascii("texte"))) {
                    HttpResponse<byte[]> reponse = envoyerLogo(session, id, fichier, "a.png", "image/png");
                    assertErreur(reponse, 404, "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
                    assertThat(withoutInstance(json.readTree(texte(reponse))).toString()).doesNotContain("inexistant");
                }
                assertErreur(api2("DELETE", CHEMIN + "/" + id + "/logo", session.entetes(), null, null), 404,
                        "COURSE_INTROUVABLE", "Introuvable", INTROUVABLE_DETAIL);
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from logo_course", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA14 : Course EN_COURS puis TERMINEE, PUT (valide, texte, sans fichier) et DELETE en 409 COURSE_NON_MODIFIABLE, logo inchangé et toujours servi, Nadia idem")
    void ca14_course_non_modifiable() throws Exception {
        for (Session session : List.of(patron, nadia)) {
            jdbc.update("delete from course");
            String x = creerCourse(patron, "Backyard des Crêtes");
            String sansLogo = creerCourse(patron, "Sans logo");
            byte[] png = OctetsDeLogo.png();
            assertThat(envoyerLogo(patron, x, png, "a.png", "image/png").statusCode()).isEqualTo(200);
            String etag = lireLogoPublic(x, Map.of()).headers().firstValue("etag").orElseThrow();

            for (String statut : List.of("EN_COURS", "TERMINEE")) {
                jdbc.update("update course set statut = ?", statut);
                for (byte[] fichier : List.of(OctetsDeLogo.jpeg(), OctetsDeLogo.ascii("texte"))) {
                    assertNonModifiable(envoyerLogo(session, x, fichier, "a.png", "image/png"));
                }
                assertNonModifiable(envoyerMultipart(session, x, new Multipart().champ("autre", "v")));
                assertNonModifiable(api2("DELETE", CHEMIN + "/" + x + "/logo", session.entetes(), null, null));
                assertNonModifiable(api2("DELETE", CHEMIN + "/" + sansLogo + "/logo", session.entetes(), null, null));
                HttpResponse<byte[]> lecture = lireLogoPublic(x, Map.of());
                assertThat(lecture.statusCode()).isEqualTo(200);
                assertThat(lecture.body()).isEqualTo(png);
                assertThat(lecture.headers().firstValue("etag")).hasValue(etag);
            }
            assertThat(jdbc.queryForObject("select count(*) from logo_course", Integer.class)).isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : plusieurs Courses en parallèle, remplacement sans toucher l'autre, PUT idempotent, DELETE idempotent (204 même sans logo), une seule ligne par Course")
    void ca15_isolation_remplacement_et_suppression() throws Exception {
        String a = creerCourse(patron, "Course A");
        String b = creerCourse(patron, "Course B");
        String c = creerCourse(patron, "Course C");
        byte[] png = OctetsDeLogo.png();
        byte[] jpeg = OctetsDeLogo.jpeg();

        assertThat(envoyerLogo(patron, a, png, "a.png", "image/png").statusCode()).isEqualTo(200);
        String etagPng = lireLogoPublic(a, Map.of()).headers().firstValue("etag").orElseThrow();
        assertThat(envoyerLogo(patron, b, jpeg, "b.jpg", "image/jpeg").statusCode()).isEqualTo(200);
        assertThat(envoyerLogo(patron, a, jpeg, "a.jpg", "image/jpeg").statusCode()).isEqualTo(200);

        HttpResponse<byte[]> lectureA = lireLogoPublic(a, Map.of());
        assertThat(lectureA.body()).isEqualTo(jpeg);
        assertThat(lectureA.headers().firstValue("etag").orElseThrow()).isNotEqualTo(etagPng);
        assertThat(lireLogoPublic(b, Map.of()).body()).isEqualTo(jpeg);
        assertThat(compterLogos(a)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from logo_course", Integer.class)).isEqualTo(2);

        HttpResponse<byte[]> premier = envoyerLogo(patron, a, png, "a.png", "image/png");
        HttpResponse<byte[]> second = envoyerLogo(patron, a, png, "a.png", "image/png");
        assertThat(premier.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(json.readTree(texte(second)).get("logoUrl")).isEqualTo(json.readTree(texte(premier)).get("logoUrl"));
        assertThat(compterLogos(a)).isEqualTo(1);

        Map<String, JsonNode> avant = listeParId(lister(patron));
        HttpResponse<byte[]> suppression = api2("DELETE", CHEMIN + "/" + a + "/logo", patron.entetes(), null, null);
        assertThat(suppression.statusCode()).isEqualTo(204);
        assertThat(suppression.body()).isEmpty();
        assertThat(compterLogos(a)).isZero();
        assertErreur(lireLogoPublic(a, Map.of()), 404, "LOGO_INTROUVABLE", "Introuvable", "Le logo est introuvable.");
        Map<String, JsonNode> apres = listeParId(lister(patron));
        assertThat(apres.get(a).get("logoUrl").isNull()).isTrue();
        assertThat(apres.get(b)).isEqualTo(avant.get(b));
        assertThat(apres.get(c).get("logoUrl").isNull()).isTrue();
        assertThat(api2("DELETE", CHEMIN + "/" + a + "/logo", patron.entetes(), null, null).statusCode())
                .isEqualTo(204);
        assertThat(api2("DELETE", CHEMIN + "/" + c + "/logo", patron.entetes(), null, null).statusCode())
                .isEqualTo(204);
        assertThat(lireLogoPublic(b, Map.of()).body()).isEqualTo(jpeg);
    }

    @Test
    @DisplayName("CA15 : deux PUT successifs de contenus différents, le second gagne et une seule ligne subsiste")
    void ca15_le_dernier_envoi_gagne() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        byte[] premier = OctetsDeLogo.png(OctetsDeLogo.motif(50, 4));
        byte[] second = OctetsDeLogo.png(OctetsDeLogo.motif(80, 5));

        assertThat(envoyerLogo(patron, x, premier, "a.png", "image/png").statusCode()).isEqualTo(200);
        assertThat(envoyerLogo(nadia, x, second, "a.png", "image/png").statusCode()).isEqualTo(200);

        assertThat(lireLogoPublic(x, Map.of()).body()).isEqualTo(second);
        assertThat(compterLogos(x)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : lecture publique en 404 LOGO_INTROUVABLE identique pour Course sans logo, id inconnu et id non UUID, sans écho du chemin ni Set-Cookie")
    void ca16_logo_introuvable() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        String z = creerCourse(patron, "Avec logo");
        assertThat(envoyerLogo(patron, z, OctetsDeLogo.png(), "a.png", "image/png").statusCode()).isEqualTo(200);

        List<JsonNode> corps = new ArrayList<>();
        for (String id : List.of(x, ID_INCONNU, "inexistant")) {
            HttpResponse<byte[]> reponse = lireLogoPublic(id, Map.of());
            assertErreur(reponse, 404, "LOGO_INTROUVABLE", "Introuvable", "Le logo est introuvable.");
            assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
            assertThat(withoutInstance(json.readTree(texte(reponse))).toString()).doesNotContain("inexistant")
                    .doesNotContain(x).doesNotContain(ID_INCONNU);
            corps.add(json.readTree(texte(reponse)));
        }
        for (JsonNode element : corps) {
            assertThat(withoutInstance(element)).isEqualTo(withoutInstance(corps.get(0)));
        }
        assertThat(lireLogoPublic(z, Map.of()).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : le PUT de 2.2 et le POST ignorent logo et logoUrl, le logo et son ETag restent identiques, PUT et DELETE du logo ne changent aucune colonne de course")
    void ca17_coherence_avec_la_modification_de_course() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        byte[] png = OctetsDeLogo.png();
        assertThat(envoyerLogo(patron, x, png, "a.png", "image/png").statusCode()).isEqualTo(200);
        String etag = lireLogoPublic(x, Map.of()).headers().firstValue("etag").orElseThrow();
        String logoUrl = listeParId(lister(patron)).get(x).get("logoUrl").asString();
        Map<String, Object> ligneAvant = jdbc.queryForMap("select * from course where id = ?", UUID.fromString(x));

        assertThat(envoyerLogo(patron, x, OctetsDeLogo.jpeg(), "a.jpg", "image/jpeg").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForMap("select * from course where id = ?", UUID.fromString(x))).isEqualTo(ligneAvant);
        assertThat(api2("DELETE", CHEMIN + "/" + x + "/logo", patron.entetes(), null, null).statusCode())
                .isEqualTo(204);
        assertThat(jdbc.queryForMap("select * from course where id = ?", UUID.fromString(x))).isEqualTo(ligneAvant);
        assertThat(envoyerLogo(patron, x, png, "a.png", "image/png").statusCode()).isEqualTo(200);

        String corps = "{\"nom\":\"Backyard des Alpes\",\"date\":\"2026-12-05\",\"distanceBoucleMetres\":8000,"
                + "\"dureeBoucleMinutes\":45,\"denivelePositifBoucleMetres\":0,\"nombreMaxParticipants\":80,"
                + "\"nombreMaxBoucles\":12,\"logo\":\"y\",\"logoUrl\":\"z\"}";
        HttpResponse<byte[]> modification = api2("PUT", CHEMIN + "/" + x, patron.entetes(), "application/json",
                corps.getBytes(StandardCharsets.UTF_8));

        assertThat(modification.statusCode()).isEqualTo(200);
        assertThat(json.readTree(texte(modification)).get("logoUrl").asString()).isEqualTo(logoUrl);
        HttpResponse<byte[]> lecture = lireLogoPublic(x, Map.of());
        assertThat(lecture.headers().firstValue("etag")).hasValue(etag);
        assertThat(lecture.body()).isEqualTo(png);

        String creation = "{\"nom\":\"Nouvelle\",\"date\":\"2026-12-05\",\"distanceBoucleMetres\":8000,"
                + "\"dureeBoucleMinutes\":45,\"denivelePositifBoucleMetres\":0,\"nombreMaxParticipants\":80,"
                + "\"nombreMaxBoucles\":12,\"logoUrl\":\"z\"}";
        HttpResponse<byte[]> creee = api2("POST", CHEMIN, patron.entetes(), "application/json",
                creation.getBytes(StandardCharsets.UTF_8));
        assertThat(creee.statusCode()).isEqualTo(201);
        assertThat(json.readTree(texte(creee)).get("logoUrl").isNull()).isTrue();
    }

    // ---------------------------------------------------------------- CA19

    @Test
    @DisplayName("CA19 : journal, INFO d'enregistrement deux fois et de suppression une fois avec l'id de la Course, rien pour les refus ni pour un DELETE sans logo, aucun nom, type déclaré ni octet")
    void ca19_journal_sans_contenu() throws Exception {
        String x = creerCourse(patron, "Backyard des Crêtes");
        String sans = creerCourse(patron, "Sans logo");
        byte[] secret = OctetsDeLogo.png(OctetsDeLogo.ascii("OCTETS-RECONNAISSABLES-LOGO"));
        byte[] autre = OctetsDeLogo.jpeg();

        assertThat(envoyerLogo(patron, x, secret, "mon-logo-secret.png", "image/x-type-declare-secret")
                .statusCode()).isEqualTo(200);
        assertThat(envoyerLogo(patron, x, autre, "mon-logo-secret.jpg", "image/x-type-declare-secret")
                .statusCode()).isEqualTo(200);
        assertThat(api2("DELETE", CHEMIN + "/" + x + "/logo", patron.entetes(), null, null).statusCode())
                .isEqualTo(204);
        int apresSucces = journal.list.size();

        assertThat(envoyerLogo(patron, x, OctetsDeLogo.ascii("texte"), "mon-logo-secret.png", "image/png")
                .statusCode()).isEqualTo(415);
        assertThat(envoyerLogo(patron, x, OctetsDeLogo.pngDeTaille(OctetsDeLogo.TAILLE_MAXIMALE + 1),
                "mon-logo-secret.png", "image/x-type-declare-secret").statusCode()).isEqualTo(413);
        assertThat(api2("DELETE", CHEMIN + "/" + ID_INCONNU + "/logo", patron.entetes(), null, null).statusCode())
                .isEqualTo(404);
        assertThat(envoyerLogo(patron, ID_INCONNU, secret, "mon-logo-secret.png", "image/png").statusCode())
                .isEqualTo(404);
        jdbc.update("update course set statut = 'EN_COURS' where id = ?", UUID.fromString(x));
        assertThat(envoyerLogo(patron, x, secret, "mon-logo-secret.png", "image/png").statusCode()).isEqualTo(409);
        jdbc.update("update course set statut = 'EN_PREPARATION' where id = ?", UUID.fromString(x));
        assertThat(api2("DELETE", CHEMIN + "/" + sans + "/logo", patron.entetes(), null, null).statusCode())
                .isEqualTo(204);

        List<ILoggingEvent> evenements = List.copyOf(journal.list);
        List<String> infos = evenements.stream().limit(apresSucces)
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("Logo de la course")).toList();
        assertThat(infos).containsExactlyInAnyOrder("Logo de la course enregistré (course " + x + ")",
                "Logo de la course enregistré (course " + x + ")", "Logo de la course supprimé (course " + x + ")");
        List<String> apresRefus = evenements.stream().skip(apresSucces)
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(apresRefus).as("aucune ligne INFO ou supérieure pour les refus et le DELETE sans logo").isEmpty();

        String tout = String.join("\n", evenements.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(tout).doesNotContain("mon-logo-secret").doesNotContain("Backyard des Crêtes")
                .doesNotContain("x-type-declare-secret").doesNotContain("OCTETS-RECONNAISSABLES-LOGO")
                .doesNotContain(Base64.getEncoder().encodeToString(secret))
                .doesNotContain(Base64.getEncoder().encodeToString(OctetsDeLogo.ascii("OCTETS-RECONNAISSABLES")))
                .doesNotContain("Content-Disposition");
        assertThat(evenements.stream().filter(e -> e.getThrowableProxy() != null)
                .map(e -> e.getThrowableProxy().getMessage()).toList())
                .noneMatch(m -> m != null && m.contains("mon-logo-secret"));
    }

    // ---------------------------------------------------------------- utilitaires

    private static final class Multipart {
        private final String frontiere = "----frontiere" + UUID.randomUUID();
        private final ByteArrayOutputStream corps = new ByteArrayOutputStream();

        Multipart fichier(String nom, String nomFichier, String type, byte[] octets) {
            corps.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"" + nom
                    + "\"; filename=\"" + nomFichier + "\"\r\nContent-Type: " + type + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            corps.writeBytes(octets);
            corps.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            return this;
        }

        Multipart champ(String nom, String valeur) {
            corps.writeBytes(("--" + frontiere + "\r\nContent-Disposition: form-data; name=\"" + nom + "\"\r\n\r\n"
                    + valeur + "\r\n").getBytes(StandardCharsets.UTF_8));
            return this;
        }

        String contentType() {
            return "multipart/form-data; boundary=" + frontiere;
        }

        byte[] corps() {
            ByteArrayOutputStream complet = new ByteArrayOutputStream();
            complet.writeBytes(corps.toByteArray());
            complet.writeBytes(("--" + frontiere + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return complet.toByteArray();
        }
    }

    private HttpResponse<byte[]> api2(String methode, String chemin, Map<String, String> entetes, String contentType,
                                      byte[] corps) throws Exception {
        HttpRequest.Builder requete = HttpRequest.newBuilder(URI.create("http://localhost:" + port + chemin))
                .header("Accept", "application/json")
                .method(methode, corps == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(corps));
        if (contentType != null) {
            requete.header("Content-Type", contentType);
        }
        entetes.forEach(requete::header);
        return client.send(requete.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> envoyerLogo(Session session, String id, byte[] octets, String nomFichier,
                                             String typeDeclare) throws Exception {
        return envoyerMultipart(session, id, new Multipart().fichier("fichier", nomFichier, typeDeclare, octets));
    }

    private HttpResponse<byte[]> envoyerMultipart(Session session, String id, Multipart multipart) throws Exception {
        return api2("PUT", CHEMIN + "/" + id + "/logo", session.entetes(), multipart.contentType(),
                multipart.corps());
    }

    /** Lecture publique : aucun cookie, aucune session, aucun jeton CSRF. */
    private HttpResponse<byte[]> lireLogoPublic(String id, Map<String, String> entetes) throws Exception {
        return api2("GET", "/api/courses/" + id + "/logo", entetes, null, null);
    }

    private static String texte(HttpResponse<byte[]> reponse) {
        return new String(reponse.body(), StandardCharsets.UTF_8);
    }

    private String creerCourse(Session session, String nom) throws Exception {
        String corps = "{\"nom\":\"" + nom + "\",\"date\":\"2026-11-14\",\"distanceBoucleMetres\":6706,"
                + "\"dureeBoucleMinutes\":60,\"denivelePositifBoucleMetres\":120,\"nombreMaxParticipants\":50,"
                + "\"nombreMaxBoucles\":24}";
        HttpResponse<byte[]> reponse = api2("POST", CHEMIN, session.entetes(), "application/json",
                corps.getBytes(StandardCharsets.UTF_8));
        assertThat(reponse.statusCode()).isEqualTo(201);
        return json.readTree(texte(reponse)).get("id").asString();
    }

    private HttpResponse<byte[]> lister(Session session) throws Exception {
        return api2("GET", CHEMIN, session.enteteLecture(), null, null);
    }

    private Map<String, JsonNode> listeParId(HttpResponse<byte[]> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(200);
        Map<String, JsonNode> parId = new HashMap<>();
        json.readTree(texte(reponse)).forEach(c -> parId.put(c.get("id").asString(), c));
        return parId;
    }

    private int compterLogos(String idCourse) {
        return jdbc.queryForObject("select count(*) from logo_course where course_id = ?", Integer.class,
                UUID.fromString(idCourse));
    }

    private JsonNode withoutInstance(JsonNode probleme) {
        return ((tools.jackson.databind.node.ObjectNode) probleme.deepCopy()).without("instance");
    }

    private void insererCompte(String pseudo, String role) {
        jdbc.update(INSERTION_COMPTE, UUID.randomUUID(), pseudo, pseudo.toLowerCase(Locale.ROOT),
                encodeur.encode(MOT_DE_PASSE), role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
    }

    private void assertNonModifiable(HttpResponse<byte[]> reponse) throws Exception {
        assertErreur(reponse, 409, "COURSE_NON_MODIFIABLE", "Conflit", NON_MODIFIABLE_DETAIL);
    }

    private void assertCsrf(HttpResponse<byte[]> reponse) throws Exception {
        assertErreur(reponse, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
    }

    /** Titre et détail non contrôlés quand ils valent null (cas du 401, dont le format relève de 1.2). */
    private void assertErreur(HttpResponse<byte[]> reponse, int statut, String code, String titre, String detail)
            throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        JsonNode corps = json.readTree(texte(reponse));
        assertThat(corps.get("code").asString()).isEqualTo(code);
        assertThat(corps.get("status").asInt()).isEqualTo(statut);
        assertThat(corps.get("title").asString()).isNotBlank();
        assertThat(corps.get("detail").asString()).isNotBlank();
        if (titre != null) {
            assertThat(corps.get("title").asString()).isEqualTo(titre);
        }
        if (detail != null) {
            assertThat(corps.get("detail").asString()).isEqualTo(detail);
        }
    }
}
