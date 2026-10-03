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
 * Contrat de l'incrément 1.6a : création et liste des comptes BENEVOLE par un ADMIN ou l'ADMIN_MASTER
 * (CA9 à CA16). Les rôles ADMIN et COUREUR sont insérés directement en base ; les bénévoles sont créés par l'API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class BenevolesIntegrationTest {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN = "/api/administration/benevoles";
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

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : Patron et un ADMIN créent un BENEVOLE (201), champ role injecté ignoré, empreinte Argon2id, un seul ADMIN_MASTER")
    void ca9_creation_d_un_benevole_par_les_admins() throws Exception {
        inserer("Nadia", "ADMIN");
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        Session nadia = ouvrir("Nadia", MOT_DE_PASSE);

        HttpResponse<String> reponse = creer(patron,
                "{\"pseudo\":\"Léo\",\"motDePasse\":\"" + MOT_DE_PASSE_BENEVOLE + "\",\"role\":\"ADMIN\"}");

        assertThat(reponse.statusCode()).isEqualTo(201);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe");
        assertThat(UUID.fromString(corps.get("id").asString())).isNotNull();
        assertThat(corps.get("pseudo").asString()).isEqualTo("Léo");
        assertThat(corps.get("role").asString()).isEqualTo("BENEVOLE");
        assertThat(Instant.parse(corps.get("creeLe").asString())).isNotNull();
        assertThat(reponse.body()).doesNotContain(MOT_DE_PASSE_BENEVOLE).doesNotContain("argon2")
                .doesNotContainIgnoringCase("pseudoNormalise");
        Map<String, Object> ligne = jdbc.queryForMap(
                "select role, empreinte_mot_de_passe, id from compte where pseudo = 'Léo'");
        assertThat(ligne.get("role")).isEqualTo("BENEVOLE");
        assertThat((String) ligne.get("empreinte_mot_de_passe")).startsWith("$argon2id$")
                .isNotEqualTo(MOT_DE_PASSE_BENEVOLE);
        assertThat(ligne.get("id").toString()).isEqualTo(corps.get("id").asString());

        HttpResponse<String> parAdmin = creer(nadia,
                "{\"pseudo\":\"Marc\",\"motDePasse\":\"" + MOT_DE_PASSE_BENEVOLE + "\",\"role\":\"ADMIN_MASTER\"}");
        assertThat(parAdmin.statusCode()).isEqualTo(201);
        assertThat(json.readTree(parAdmin.body()).get("role").asString()).isEqualTo("BENEVOLE");
        assertThat(jdbc.queryForObject("select role from compte where pseudo = 'Marc'", String.class))
                .isEqualTo("BENEVOLE");
        assertThat(compter("role = 'ADMIN_MASTER'")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : anonyme 401, COUREUR et BENEVOLE 403 ACCES_REFUSE (POST et GET), CSRF absent ou différent 403 CSRF_INVALIDE, corps vide non admin 403, chemin inexistant")
    void ca10_securite_des_endpoints() throws Exception {
        inserer("Nadia", "ADMIN");
        inserer("Alice", "COUREUR");
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        assertThat(creer(patron, corps("Léo", MOT_DE_PASSE_BENEVOLE)).statusCode()).isEqualTo(201);
        int avant = compter("true");

        assertErreur(api.postJson(CHEMIN, corps("Intrus1", MOT_DE_PASSE), api.jetonValide()), 401,
                "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.get(CHEMIN), 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.get(CHEMIN + "/inexistant"), 401, "NON_AUTHENTIFIE", "Authentification requise",
                "Vous devez être connecté.");

        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        Session leo = ouvrir("Léo", MOT_DE_PASSE_BENEVOLE);
        for (Session session : List.of(alice, leo)) {
            assertErreur(creer(session, corps("Intrus1", MOT_DE_PASSE)), 403, "ACCES_REFUSE", "Accès refusé",
                    ACCES_REFUSE_DETAIL);
            assertErreur(lister(session), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(creer(session, "{}"), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
            assertErreur(get(session, CHEMIN + "/inexistant"), 403, "ACCES_REFUSE", "Accès refusé",
                    ACCES_REFUSE_DETAIL);
        }

        Session nadia = ouvrir("Nadia", MOT_DE_PASSE);
        for (Session session : List.of(alice, leo, nadia, patron)) {
            HttpResponse<String> sansEntete = api.requete("POST", CHEMIN,
                    Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf()),
                    "application/json", corps("Intrus1", MOT_DE_PASSE));
            assertErreur(sansEntete, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
            HttpResponse<String> different = api.requete("POST", CHEMIN,
                    Map.of("Cookie", "JSESSIONID=" + session.id() + "; XSRF-TOKEN=" + session.xsrf(),
                            "X-XSRF-TOKEN", "autre-valeur"), "application/json", corps("Intrus1", MOT_DE_PASSE));
            assertErreur(different, 403, "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");
        }
        assertErreur(api.post(CHEMIN, "application/json", corps("Intrus1", MOT_DE_PASSE), new Jeton(null, null)), 403,
                "CSRF_INVALIDE", "Accès refusé", "Jeton CSRF absent ou invalide.");

        assertErreur(get(nadia, CHEMIN + "/inexistant"), 404, "RESSOURCE_INTROUVABLE", "Introuvable",
                "La ressource demandée est introuvable.");
        assertThat(lister(nadia).statusCode()).isEqualTo(200);
        assertThat(compter("true")).isEqualTo(avant);
        assertThat(compter("pseudo = 'Intrus1'")).isZero();
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : validation 400 (toutes violations), corps illisible, 415, mots de passe de 12 et 128 acceptés, 129 refusé")
    void ca11_validation() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        int avant = compter("true");

        HttpResponse<String> deuxViolations = creer(patron, corps("ab", "court-secre"));
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

        JsonNode caracteres = verifierValidation(creer(patron, corps("a@b", MOT_DE_PASSE)));
        assertThat(erreur(caracteres, "pseudo").get("code").asString()).isEqualTo("PSEUDO_CARACTERES");

        assertErreur(creer(patron, "pas du json {"), 400, "CORPS_ILLISIBLE", "Requête invalide",
                "Le corps de la requête est illisible.");
        assertThat(api.requete("POST", CHEMIN, patron.entetes(), "text/plain", "Léo").statusCode()).isEqualTo(415);

        assertThat(creer(patron, corps("Douze", "d".repeat(12))).statusCode()).isEqualTo(201);
        assertThat(creer(patron, corps("Cent28", "d".repeat(128))).statusCode()).isEqualTo(201);
        JsonNode trop = verifierValidation(creer(patron, corps("Cent29", "d".repeat(129))));
        assertThat(trop).hasSize(1);
        assertThat(erreur(trop, "motDePasse").get("code").asString()).isEqualTo("MOT_DE_PASSE_TROP_LONG");
        assertThat(compter("pseudo = 'Cent29'")).isZero();
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : pseudo déjà pris (coureur, admin, master, bénévole) en 409 quelle que soit la casse ; 400 prioritaire ; doublon concurrent : un 201, un 409")
    void ca12_pseudo_deja_utilise_et_concurrence() throws Exception {
        inserer("Alice", "COUREUR");
        inserer("Nadia", "ADMIN");
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        assertThat(creer(patron, corps("Léo", MOT_DE_PASSE_BENEVOLE)).statusCode()).isEqualTo(201);
        int avant = compter("true");

        for (String pseudo : List.of("alice", "NADIA", "PATRON", "léo")) {
            assertErreur(creer(patron, corps(pseudo, MOT_DE_PASSE)), 409, "PSEUDO_DEJA_UTILISE", "Conflit",
                    "Ce pseudo est déjà utilisé.");
        }
        assertThat(compter("true")).isEqualTo(avant);
        assertThat(jdbc.queryForObject("select role from compte where pseudo = 'Alice'", String.class))
                .isEqualTo("COUREUR");
        assertThat(jdbc.queryForObject("select role from compte where pseudo = 'Nadia'", String.class))
                .isEqualTo("ADMIN");

        HttpResponse<String> court = creer(patron, corps("alice", "court-secre"));
        assertThat(court.statusCode()).isEqualTo(400);
        assertThat(json.readTree(court.body()).get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");

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

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : la liste contient les BENEVOLE dans l'ordre de création pour Patron et pour un ADMIN, sans autre rôle ; [] si aucun")
    void ca13_liste_des_benevoles() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        HttpResponse<String> vide = lister(patron);
        assertThat(vide.statusCode()).isEqualTo(200);
        assertThat(json.readTree(vide.body()).isArray()).isTrue();
        assertThat(json.readTree(vide.body())).isEmpty();

        inserer("Nadia", "ADMIN");
        inserer("Alice", "COUREUR");
        Session nadia = ouvrir("Nadia", MOT_DE_PASSE);
        for (String pseudo : List.of("Marc", "Léo", "Zoé")) {
            assertThat(creer(patron, corps(pseudo, MOT_DE_PASSE_BENEVOLE)).statusCode()).isEqualTo(201);
        }

        for (Session session : List.of(patron, nadia)) {
            HttpResponse<String> reponse = lister(session);
            assertThat(reponse.statusCode()).isEqualTo(200);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                    c -> assertThat(c).contains("application/json"));
            JsonNode liste = json.readTree(reponse.body());
            assertThat(liste).hasSize(3);
            List<String> pseudos = new ArrayList<>();
            for (JsonNode element : liste) {
                pseudos.add(element.get("pseudo").asString());
                assertThat(element.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe");
                assertThat(element.get("role").asString()).isEqualTo("BENEVOLE");
            }
            assertThat(pseudos).containsExactly("Marc", "Léo", "Zoé");
            assertThat(reponse.body()).doesNotContain("argon2").doesNotContain("Patron").doesNotContain("Alice")
                    .doesNotContain("Nadia");
        }
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : le bénévole créé se connecte (casse libre), 403 sur /administration/**, /comptes/moi 200, limitation de connexion appliquée")
    void ca14_connexion_du_benevole_cree() throws Exception {
        inserer("Nadia", "ADMIN");
        Session nadia = ouvrir("Nadia", MOT_DE_PASSE);
        assertThat(creer(nadia, corps("Léo", MOT_DE_PASSE_BENEVOLE)).statusCode()).isEqualTo(201);

        HttpResponse<String> connexion = connecter("LÉO", MOT_DE_PASSE_BENEVOLE);
        assertThat(connexion.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(connexion.body());
        assertThat(corps.get("pseudo").asString()).isEqualTo("Léo");
        assertThat(corps.get("role").asString()).isEqualTo("BENEVOLE");

        Session leo = new Session(ApiHttp.valeurCookie(connexion, "JSESSIONID"), jetonDeConnexion);
        assertErreur(get(leo, "/api/administration/acces"), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(lister(leo), 403, "ACCES_REFUSE", "Accès refusé", ACCES_REFUSE_DETAIL);
        assertErreur(creer(leo, corps("Intrus1", MOT_DE_PASSE)), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
        assertErreur(get(leo, "/api/administration/admins"), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);
        HttpResponse<String> moi = get(leo, "/api/comptes/moi");
        assertThat(moi.statusCode()).isEqualTo(200);
        assertThat(json.readTree(moi.body()).get("role").asString()).isEqualTo("BENEVOLE");
        assertThat(compter("pseudo = 'Intrus1'")).isZero();

        registre.vider();
        for (int i = 0; i < 5; i++) {
            assertThat(connecter("Léo", "mauvais-mot-de-passe-1").statusCode()).as("échec " + i).isEqualTo(401);
        }
        HttpResponse<String> bloquee = connecter("Léo", MOT_DE_PASSE_BENEVOLE);
        assertThat(bloquee.statusCode()).isEqualTo(429);
        assertThat(json.readTree(bloquee.body()).get("code").asString()).isEqualTo("TENTATIVES_EXCESSIVES");
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : PUT, PATCH, DELETE sur /benevoles/{id} jamais 2xx, Léo intact ; POST /api/comptes avec role BENEVOLE crée un COUREUR ; /admins 403 pour ADMIN ; migrations inchangées")
    void ca15_non_regression_et_absence_de_modification() throws Exception {
        inserer("Nadia", "ADMIN");
        HttpResponse<String> coureur = api.postJson("/api/comptes",
                "{\"pseudo\":\"Bob12\",\"motDePasse\":\"" + MOT_DE_PASSE + "\",\"role\":\"BENEVOLE\"}",
                api.jetonValide());
        assertThat(coureur.statusCode()).isEqualTo(201);
        assertThat(json.readTree(coureur.body()).get("role").asString()).isEqualTo("COUREUR");
        assertThat(jdbc.queryForObject("select role from compte where pseudo = 'Bob12'", String.class))
                .isEqualTo("COUREUR");

        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        String idLeo = json.readTree(creer(patron, corps("Léo", MOT_DE_PASSE_BENEVOLE)).body()).get("id").asString();
        Map<String, Object> avant = jdbc.queryForMap("select * from compte where pseudo = 'Léo'");

        for (String methode : List.of("PUT", "PATCH", "DELETE")) {
            HttpResponse<String> reponse = api.requete(methode, CHEMIN + "/" + idLeo, patron.entetes(),
                    "application/json", "{\"pseudo\":\"Pirate\",\"motDePasse\":\"" + MOT_DE_PASSE + "\"}");
            assertThat(reponse.statusCode()).as(methode).isIn(404, 405);
        }
        assertThat(jdbc.queryForMap("select * from compte where pseudo = 'Léo'")).isEqualTo(avant);
        assertThat(compter("pseudo = 'Pirate'")).isZero();

        Session nadia = ouvrir("Nadia", MOT_DE_PASSE);
        assertErreur(get(nadia, "/api/administration/admins"), 403, "ACCES_REFUSE", "Accès refusé",
                ACCES_REFUSE_DETAIL);

        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course");
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : aucun mot de passe ni pseudo (INFO et plus) dans le journal ; ligne de création avec identifiants ; toString du DTO masqué")
    void ca16_journal() throws Exception {
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        String idPatron = jdbc.queryForObject("select id::text from compte where role = 'ADMIN_MASTER'", String.class);

        HttpResponse<String> creation = creer(patron, corps("Léo", "secret-de-test-123"));
        assertThat(creation.statusCode()).isEqualTo(201);
        String idLeo = json.readTree(creation.body()).get("id").asString();
        assertThat(creer(patron, corps("Court1", "court-secre")).statusCode()).isEqualTo(400);
        assertThat(creer(patron, corps("Léo", "secret-de-test-123")).statusCode()).isEqualTo(409);
        assertThat(creer(patron, corps("Autre1", "court-secre")).statusCode()).isEqualTo(400);

        List<ILoggingEvent> evenements = List.copyOf(journal.list);
        for (ILoggingEvent evenement : evenements) {
            String texte = evenement.getFormattedMessage() + " " + evenement.getMDCPropertyMap()
                    + (evenement.getThrowableProxy() == null ? "" : evenement.getThrowableProxy().getMessage());
            assertThat(texte).as(evenement.getLevel() + " " + evenement.getLoggerName())
                    .doesNotContain("secret-de-test-123").doesNotContain("court-secre")
                    .doesNotContain(MOT_DE_PASSE_BENEVOLE);
            if (evenement.getLevel().isGreaterOrEqual(Level.INFO)) {
                assertThat(texte).as("INFO+ " + evenement.getLoggerName()).doesNotContain("Léo");
            }
        }
        assertThat(evenements).filteredOn(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains(idLeo) && e.getFormattedMessage().contains(idPatron))
                .hasSize(1);
        assertThat(evenements).filteredOn(e -> e.getFormattedMessage().startsWith("Bénévole créé")).hasSize(1);
        assertThat(new fr.backyard.tracker.comptes.exposition.CreerBenevoleRequete("Léo", "secret-de-test-123")
                .toString()).isEqualTo("CreerBenevoleRequete[pseudo=Léo, motDePasse=masqué]");
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
