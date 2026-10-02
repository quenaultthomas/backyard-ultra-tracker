package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 1.5 : création et liste des comptes ADMIN par l'admin master
 * (CA10 à CA20). Les rôles BENEVOLE et COUREUR sont insérés directement en base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class AdminsIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/admins";
    static final String INSERTION = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le) "
            + "values (?, ?, ?, ?, ?, ?)";
    static final String ACCES_REFUSE_DETAIL = "Vous n'avez pas les droits nécessaires.";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder encodeur;

    @Autowired
    InitialiserAdminMaster initialiserAdminMaster;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    JdbcTemplate jdbc;
    ApiHttp api;
    final JsonMapper json = JsonMapper.builder().build();
    String empreinte;
    ListAppender<ILoggingEvent> journal;

    /** Cookies d'une session ouverte. */
    record Session(String id, String xsrf) {
        Map<String, String> entetes() {
            return Map.of("Cookie", "JSESSIONID=" + id + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf);
        }
    }

    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registre.vider();
        api = new ApiHttp(port);
        empreinte = encodeur.encode(MOT_DE_PASSE);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        journal = new ListAppender<>();
        journal.start();
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.addAppender(journal);
        racine.setLevel(Level.DEBUG);
    }

    @AfterEach
    void nettoyer() {
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.detachAppender(journal);
        racine.setLevel(Level.INFO);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : l'admin master crée un ADMIN (201), le champ role injecté est ignoré, empreinte Argon2id, un seul ADMIN_MASTER")
    void ca10_creation_d_un_admin() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);

        HttpResponse<String> reponse = creer(patron,
                "{\"pseudo\":\"Nadia\",\"motDePasse\":\"" + MOT_DE_PASSE_ADMIN + "\",\"role\":\"ADMIN_MASTER\"}");

        assertThat(reponse.statusCode()).isEqualTo(201);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe");
        assertThat(UUID.fromString(corps.get("id").asString())).isNotNull();
        assertThat(corps.get("pseudo").asString()).isEqualTo("Nadia");
        assertThat(corps.get("role").asString()).isEqualTo("ADMIN");
        assertThat(Instant.parse(corps.get("creeLe").asString())).isNotNull();
        assertThat(reponse.body()).doesNotContain(MOT_DE_PASSE_ADMIN).doesNotContain("argon2")
                .doesNotContainIgnoringCase("pseudoNormalise");

        Map<String, Object> ligne = jdbc.queryForMap(
                "select pseudo, role, empreinte_mot_de_passe, id from compte where pseudo = 'Nadia'");
        assertThat(ligne.get("role")).isEqualTo("ADMIN");
        assertThat((String) ligne.get("empreinte_mot_de_passe")).startsWith("$argon2id$")
                .isNotEqualTo(MOT_DE_PASSE_ADMIN);
        assertThat(ligne.get("id").toString()).isEqualTo(corps.get("id").asString());
        assertThat(compter("role = 'ADMIN_MASTER'")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : POST valide, anonyme 401, ADMIN, BENEVOLE et COUREUR 403 ACCES_REFUSE, aucune ligne créée")
    void ca11_post_refuse_selon_le_role() throws Exception {
        inserer("Nadia", "ADMIN");
        inserer("Benevole1", "BENEVOLE");
        inserer("Alice", "COUREUR");
        int avant = compter("true");

        Jeton jeton = api.jetonValide();
        assertErreur(api.postJson(CHEMIN, corps("Intrus1", MOT_DE_PASSE), jeton), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
        for (String pseudo : List.of("Nadia", "Benevole1", "Alice")) {
            Session session = ouvrir(pseudo, MOT_DE_PASSE);
            assertErreur(creer(session, corps("Intrus1", MOT_DE_PASSE)), 403, "ACCES_REFUSE", "Accès refusé",
                    ACCES_REFUSE_DETAIL);
        }

        assertThat(compter("true")).isEqualTo(avant);
        assertThat(compter("pseudo = 'Intrus1'")).isZero();
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : GET /admins et chemin inexistant selon le rôle ; /acces reste 204 pour ADMIN et ADMIN_MASTER")
    void ca12_get_controle_des_roles() throws Exception {
        inserer("Nadia", "ADMIN");
        inserer("Benevole1", "BENEVOLE");
        inserer("Alice", "COUREUR");

        assertErreur(api.get(CHEMIN), 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.get(CHEMIN + "/inexistant"), 401, "NON_AUTHENTIFIE", "Authentification requise",
                "Vous devez être connecté.");

        for (String pseudo : List.of("Nadia", "Benevole1", "Alice")) {
            Session session = ouvrir(pseudo, MOT_DE_PASSE);
            assertErreur(lister(session), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(get(session, CHEMIN + "/inexistant"), 403, "ACCES_REFUSE", "Accès refusé",
                    ACCES_REFUSE_DETAIL);
        }
        Session nadia = ouvrir("Nadia", MOT_DE_PASSE);
        assertThat(get(nadia, "/api/administration/acces").statusCode()).isEqualTo(204);

        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        assertThat(lister(patron).statusCode()).isEqualTo(200);
        assertErreur(get(patron, CHEMIN + "/inexistant"), 404, "RESSOURCE_INTROUVABLE", "Introuvable",
                "La ressource demandée est introuvable.");
        assertThat(get(patron, "/api/administration/acces").statusCode()).isEqualTo(204);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : CSRF absent ou différent 403 CSRF_INVALIDE avant tout ; jeton valide, ADMIN corps vide 403, anonyme corps vide 401")
    void ca13_csrf_et_ordre_des_controles() throws Exception {
        inserer("Nadia", "ADMIN");
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        Session nadia = ouvrir("Nadia", MOT_DE_PASSE);
        String corpsValide = corps("Intrus1", MOT_DE_PASSE);

        for (Session session : List.of(patron, nadia)) {
            HttpResponse<String> sansEntete = api.requete("POST", CHEMIN,
                    Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf()),
                    "application/json", corpsValide);
            assertErreur(sansEntete, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
            HttpResponse<String> different = api.requete("POST", CHEMIN,
                    Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf(),
                            "X-XSRF-TOKEN", "autre-valeur"), "application/json", corpsValide);
            assertErreur(different, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
        }
        assertErreur(api.post(CHEMIN, "application/json", corpsValide, new Jeton(null, null)), 403, "CSRF_INVALIDE",
                "Accès refusé", "Jeton CSRF absent ou invalide.");
        assertErreur(api.post(CHEMIN, "application/json", corpsValide, new Jeton("a", "b")), 403, "CSRF_INVALIDE",
                "Accès refusé", "Jeton CSRF absent ou invalide.");

        assertErreur(creer(nadia, "{}"), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(api.postJson(CHEMIN, "{}", api.jetonValide()), 401, "NON_AUTHENTIFIE",
                "Authentification requise", "Vous devez être connecté.");
        assertThat(compter("pseudo = 'Intrus1'")).isZero();
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : validation 400 (toutes violations), corps illisible, 415, bornes du mot de passe 12 et 128 acceptées, 129 refusé")
    void ca14_validation() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        int avant = compter("true");

        HttpResponse<String> deuxViolations = creer(patron, corps("ab", "court-secre"));
        assertThat(deuxViolations.statusCode()).isEqualTo(400);
        assertThat(deuxViolations.body()).doesNotContain("court-secre");
        JsonNode erreurs = verifierValidation(deuxViolations);
        assertThat(erreurs).hasSize(2);
        assertThat(erreur(erreurs, "pseudo").get("code").asString()).isEqualTo("PSEUDO_LONGUEUR");
        assertThat(erreur(erreurs, "motDePasse").get("code").asString()).isEqualTo("MOT_DE_PASSE_TROP_COURT");
        assertThat(compter("true")).isEqualTo(avant);

        JsonNode vide = verifierValidation(creer(patron, "{}"));
        assertThat(vide).hasSize(2);
        assertThat(erreur(vide, "pseudo").get("code").asString()).isEqualTo("PSEUDO_REQUIS");
        assertThat(erreur(vide, "motDePasse").get("code").asString()).isEqualTo("MOT_DE_PASSE_REQUIS");

        assertErreur(creer(patron, "pas du json {"), 400, "CORPS_ILLISIBLE", "Requête invalide",
                "Le corps de la requête est illisible.");
        HttpResponse<String> texte = api.requete("POST", CHEMIN, patron.entetes(), "text/plain", "Nadia");
        assertThat(texte.statusCode()).isEqualTo(415);

        assertThat(creer(patron, corps("Douze", "d".repeat(12))).statusCode()).isEqualTo(201);
        assertThat(creer(patron, corps("Cent28", "d".repeat(128))).statusCode()).isEqualTo(201);
        JsonNode trop = verifierValidation(creer(patron, corps("Cent29", "d".repeat(129))));
        assertThat(trop).hasSize(1);
        assertThat(erreur(trop, "motDePasse").get("code").asString()).isEqualTo("MOT_DE_PASSE_TROP_LONG");
        assertThat(compter("pseudo = 'Cent29'")).isZero();
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : pseudo déjà pris (coureur, admin master, admin) en 409 quelle que soit la casse ; 400 prioritaire sur 409")
    void ca15_pseudo_deja_utilise() throws Exception {
        inserer("Alice", "COUREUR");
        inserer("Nadia", "ADMIN");
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        int avant = compter("true");

        for (String pseudo : List.of("alice", "PATRON", "NADIA")) {
            assertErreur(creer(patron, corps(pseudo, MOT_DE_PASSE)), 409, "PSEUDO_DEJA_UTILISE", "Conflit",
                    "Ce pseudo est déjà utilisé.");
        }
        assertThat(compter("true")).isEqualTo(avant);
        assertThat(jdbc.queryForObject("select role from compte where pseudo = 'Alice'", String.class))
                .isEqualTo("COUREUR");

        HttpResponse<String> court = creer(patron, corps("alice", "court-secre"));
        assertThat(court.statusCode()).isEqualTo(400);
        assertThat(json.readTree(court.body()).get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : deux créations simultanées du même pseudo, un 201 et un 409, jamais de 500, une seule ligne")
    void ca16_creation_concurrente() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        CountDownLatch depart = new CountDownLatch(1);
        Callable<HttpResponse<String>> envoi = () -> {
            depart.await();
            return creer(patron, corps("Concurrent", MOT_DE_PASSE));
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> futurs = new ArrayList<>();
            futurs.add(pool.submit(envoi));
            futurs.add(pool.submit(envoi));
            depart.countDown();
            List<Integer> statuts = new ArrayList<>();
            for (Future<HttpResponse<String>> futur : futurs) {
                HttpResponse<String> reponse = futur.get();
                statuts.add(reponse.statusCode());
                if (reponse.statusCode() == 409) {
                    assertThat(json.readTree(reponse.body()).get("code").asString()).isEqualTo("PSEUDO_DEJA_UTILISE");
                }
            }
            assertThat(statuts).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(compter("pseudo_normalise = 'concurrent'")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : la liste contient les ADMIN dans l'ordre de création, sans master, bénévole ni coureur ; [] si aucun")
    void ca17_liste_des_admins() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        HttpResponse<String> vide = lister(patron);
        assertThat(vide.statusCode()).isEqualTo(200);
        assertThat(json.readTree(vide.body()).isArray()).isTrue();
        assertThat(json.readTree(vide.body())).isEmpty();

        inserer("Benevole1", "BENEVOLE");
        inserer("Alice", "COUREUR");
        for (String pseudo : List.of("Marc", "Nadia", "Zoe")) {
            assertThat(creer(patron, corps(pseudo, MOT_DE_PASSE_ADMIN)).statusCode()).isEqualTo(201);
        }

        HttpResponse<String> reponse = lister(patron);
        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/json"));
        JsonNode liste = json.readTree(reponse.body());
        assertThat(liste).hasSize(3);
        List<String> pseudos = new ArrayList<>();
        for (JsonNode element : liste) {
            pseudos.add(element.get("pseudo").asString());
            assertThat(element.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe");
            assertThat(element.get("role").asString()).isEqualTo("ADMIN");
        }
        assertThat(pseudos).containsExactly("Marc", "Nadia", "Zoe");
        assertThat(reponse.body()).doesNotContain("argon2").doesNotContain("Patron").doesNotContain("Alice")
                .doesNotContain("Benevole1");
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : l'admin créé se connecte (casse libre), a accès à /acces mais 403 sur /admins, limitation de connexion appliquée")
    void ca18_connexion_de_l_admin_cree() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        assertThat(creer(patron, corps("Nadia", MOT_DE_PASSE_ADMIN)).statusCode()).isEqualTo(201);

        HttpResponse<String> connexion = connecter("nadia", MOT_DE_PASSE_ADMIN);
        assertThat(connexion.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(connexion.body());
        assertThat(corps.get("pseudo").asString()).isEqualTo("Nadia");
        assertThat(corps.get("role").asString()).isEqualTo("ADMIN");

        Session nadia = new Session(ApiHttp.valeurCookie(connexion, "JSESSIONID"), jetonDeConnexion);
        assertThat(get(nadia, "/api/administration/acces").statusCode()).isEqualTo(204);
        assertErreur(lister(nadia), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(creer(nadia, corps("Intrus1", MOT_DE_PASSE)), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
        assertThat(compter("pseudo = 'Intrus1'")).isZero();

        registre.vider();
        for (int i = 0; i < 5; i++) {
            assertThat(connecter("Nadia", "mauvais-mot-de-passe-1").statusCode()).as("échec " + i).isEqualTo(401);
        }
        HttpResponse<String> bloquee = connecter("Nadia", MOT_DE_PASSE_ADMIN);
        assertThat(bloquee.statusCode()).isEqualTo(429);
        assertThat(json.readTree(bloquee.body()).get("code").asString()).isEqualTo("TENTATIVES_EXCESSIVES");
    }

    // ---------------------------------------------------------------- CA19

    @Test
    @DisplayName("CA19 : aucun mot de passe ni pseudo (INFO et plus) dans le journal ; la ligne de création porte les deux identifiants")
    void ca19_journal() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        String idPatron = jdbc.queryForObject("select id::text from compte where role = 'ADMIN_MASTER'", String.class);

        HttpResponse<String> creation = creer(patron, corps("Nadia", "secret-de-test-123"));
        assertThat(creation.statusCode()).isEqualTo(201);
        String idNadia = json.readTree(creation.body()).get("id").asString();
        assertThat(creer(patron, corps("Court1", "court-secre")).statusCode()).isEqualTo(400);
        assertThat(creer(patron, corps("Nadia", "secret-de-test-123")).statusCode()).isEqualTo(409);
        assertThat(creer(patron, corps("Autre1", "court-secre")).statusCode()).isEqualTo(400);

        List<ILoggingEvent> evenements = List.copyOf(journal.list);
        for (ILoggingEvent evenement : evenements) {
            String texte = evenement.getFormattedMessage() + " " + evenement.getMDCPropertyMap()
                    + (evenement.getThrowableProxy() == null ? "" : evenement.getThrowableProxy().getMessage());
            assertThat(texte).as(evenement.getLevel() + " " + evenement.getLoggerName())
                    .doesNotContain("secret-de-test-123").doesNotContain("court-secre");
            if (evenement.getLevel().isGreaterOrEqual(Level.INFO)) {
                assertThat(texte).as("INFO+ " + evenement.getLoggerName()).doesNotContain("Nadia");
            }
        }
        assertThat(evenements).filteredOn(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains(idNadia) && e.getFormattedMessage().contains(idPatron))
                .hasSize(1);
        assertThat(evenements).filteredOn(e -> e.getFormattedMessage().startsWith("Admin créé")).hasSize(1);
    }

    // ---------------------------------------------------------------- CA20

    @Test
    @DisplayName("CA20 : POST /api/comptes avec role ADMIN crée un COUREUR ; PUT, PATCH, DELETE sur /admins/{id} jamais 2xx, Nadia intact ; migrations inchangées")
    void ca20_non_regression_et_absence_de_modification() throws Exception {
        HttpResponse<String> coureur = api.postJson("/api/comptes",
                "{\"pseudo\":\"Bob12\",\"motDePasse\":\"" + MOT_DE_PASSE + "\",\"role\":\"ADMIN\"}", api.jetonValide());
        assertThat(coureur.statusCode()).isEqualTo(201);
        assertThat(json.readTree(coureur.body()).get("role").asString()).isEqualTo("COUREUR");
        assertThat(jdbc.queryForObject("select role from compte where pseudo = 'Bob12'", String.class))
                .isEqualTo("COUREUR");

        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        HttpResponse<String> creation = creer(patron, corps("Nadia", MOT_DE_PASSE_ADMIN));
        String idNadia = json.readTree(creation.body()).get("id").asString();
        Map<String, Object> avant = jdbc.queryForMap("select * from compte where pseudo = 'Nadia'");

        for (String methode : List.of("PUT", "PATCH", "DELETE")) {
            HttpResponse<String> reponse = api.requete(methode, CHEMIN + "/" + idNadia, patron.entetes(),
                    "application/json", "{\"pseudo\":\"Pirate\",\"motDePasse\":\"" + MOT_DE_PASSE + "\"}");
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        assertThat(jdbc.queryForMap("select * from compte where pseudo = 'Nadia'")).isEqualTo(avant);
        assertThat(compter("pseudo = 'Pirate'")).isZero();

        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique");
    }

    // ---------------------------------------------------------------- utilitaires

    private String jetonDeConnexion;

    private void inserer(String pseudo, String role) {
        jdbc.update(INSERTION, UUID.randomUUID(), pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte, role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
    }

    private int compter(String condition) {
        return jdbc.queryForObject("select count(*) from compte where " + condition, Integer.class);
    }

    private static String corps(String pseudo, String motDePasse) {
        return "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}";
    }

    private HttpResponse<String> connecter(String pseudo, String motDePasse) throws Exception {
        Jeton jeton = api.jetonValide();
        jetonDeConnexion = jeton.cookie();
        return api.postJson("/api/connexion", corps(pseudo, motDePasse), jeton);
    }

    private Session ouvrir(String pseudo, String motDePasse) throws Exception {
        HttpResponse<String> reponse = connecter(pseudo, motDePasse);
        assertThat(reponse.statusCode()).as("connexion de " + pseudo).isEqualTo(200);
        return new Session(ApiHttp.valeurCookie(reponse, "JSESSIONID"), jetonDeConnexion);
    }

    private HttpResponse<String> creer(Session session, String corps) throws Exception {
        return api.requete("POST", CHEMIN, session.entetes(), "application/json", corps);
    }

    private HttpResponse<String> lister(Session session) throws Exception {
        return get(session, CHEMIN);
    }

    private HttpResponse<String> get(Session session, String chemin) throws Exception {
        return api.requete("GET", chemin, Map.of("Cookie", "JSESSIONID=" + session.id()), null, null);
    }

    private JsonNode verifierValidation(HttpResponse<String> reponse) throws Exception {
        assertErreur(reponse, 400, "VALIDATION_ECHOUEE", "Requête invalide", "Certains champs sont invalides.");
        return json.readTree(reponse.body()).get("erreurs");
    }

    private static JsonNode erreur(JsonNode erreurs, String champ) {
        for (JsonNode erreur : erreurs) {
            if (champ.equals(erreur.get("champ").asString())) {
                return erreur;
            }
        }
        throw new AssertionError("Aucune erreur pour le champ " + champ + " dans " + erreurs);
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
