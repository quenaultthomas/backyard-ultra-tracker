package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.infrastructure.EncodeurMotDePasseArgon2;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contrat d'API de l'incrément 1.3 : blocage temporaire des connexions (429 TENTATIVES_EXCESSIVES). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConnexionBlocageIntegrationTest {

    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String MOT_DE_PASSE_FAUX = "mauvais-mot-de-passe-1";
    static final Instant T = Instant.parse("2026-09-15T10:00:00Z");
    static final String DETAIL_BLOCAGE = "Trop de tentatives de connexion. Réessayez plus tard.";

    /** Horloge de l'application remplacée dans ce contexte de test : fixe, déplacée par les tests. */
    static final class HorlogeMutable extends Clock {
        private volatile Instant instant = T;

        void fixer(Instant nouvelInstant) {
            instant = nouvelInstant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class HorlogeDeTestConfiguration {
        @Bean
        @Primary
        HorlogeMutable horlogeDeTest() {
            return new HorlogeMutable();
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    EncodeurMotDePasseArgon2 encodeur;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    @Autowired
    HorlogeMutable horloge;

    @Autowired
    PolitiqueBlocage politique;

    JdbcTemplate jdbc;
    ApiHttp api;
    final JsonMapper json = JsonMapper.builder().build();
    ListAppender<ILoggingEvent> journal;
    static String empreinteDeReference;

    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registre.vider();
        horloge.fixer(T);
        api = new ApiHttp(port);
        if (empreinteDeReference == null) {
            empreinteDeReference = encodeur.encoder(new fr.backyard.tracker.comptes.domaine.MotDePasse(MOT_DE_PASSE));
        }
        journal = new ListAppender<>();
        journal.list = new java.util.concurrent.CopyOnWriteArrayList<>(); // liste sûre face aux threads qui journalisent
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

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : pseudo existant, compte sans empreinte et pseudo inexistant donnent des 429 strictement identiques")
    void ca14_blocage_indiscernable() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        creerCompte("Anonyme1", null);

        List<HttpResponse<String>> bloquees = new ArrayList<>();
        for (String pseudo : List.of("Alice", "Anonyme1", "Inconnu")) {
            for (int i = 0; i < 5; i++) {
                assertThat(connecter(pseudo, MOT_DE_PASSE_FAUX).statusCode()).as(pseudo + " essai " + i).isEqualTo(401);
            }
            bloquees.add(connecter(pseudo, MOT_DE_PASSE_FAUX));
        }

        for (HttpResponse<String> reponse : bloquees) {
            assertThat(reponse.statusCode()).isEqualTo(429);
            assertThat(reponse.headers().firstValue("retry-after")).hasValue("900");
            assertThat(reponse.body()).doesNotContain("Alice").doesNotContain("Anonyme1").doesNotContain("Inconnu");
        }
        assertThat(bloquees.get(1).body()).isEqualTo(bloquees.get(0).body());
        assertThat(bloquees.get(2).body()).isEqualTo(bloquees.get(0).body());
        assertThat(entetesSansDate(bloquees.get(1))).isEqualTo(entetesSansDate(bloquees.get(0)));
        assertThat(entetesSansDate(bloquees.get(2))).isEqualTo(entetesSansDate(bloquees.get(0)));
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : fin du blocage à l'instant exact (Clock mutable) ; 5 nouveaux échecs nécessaires pour rebloquer")
    void ca15_expiration_du_blocage() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        for (int i = 0; i < 5; i++) {
            assertThat(connecter("Alice", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
        }
        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(429);

        horloge.fixer(T.plusSeconds(899).plusMillis(500));
        HttpResponse<String> presqueFini = connecter("Alice", MOT_DE_PASSE);
        assertThat(presqueFini.statusCode()).isEqualTo(429);
        assertThat(presqueFini.headers().firstValue("retry-after")).hasValue("1");
        assertThat(json.readTree(presqueFini.body()).get("reessayerDansSecondes").asInt()).isEqualTo(1);

        horloge.fixer(T.plusSeconds(900));
        HttpResponse<String> reussie = connecter("Alice", MOT_DE_PASSE);
        assertThat(reussie.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(reussie.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe");
        assertThat(corps.get("pseudo").asString()).isEqualTo("Alice");

        // Nouvelle série (client anonyme) : 5 échecs en 401 sont de nouveau nécessaires.
        for (int i = 0; i < 5; i++) {
            assertThat(connecter("Alice", MOT_DE_PASSE_FAUX).statusCode()).as("essai " + i).isEqualTo(401);
        }
        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : un succès remet le compteur à zéro (4 échecs, succès, déconnexion, 4 échecs, succès)")
    void ca16_succes_remet_le_compteur_a_zero() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);

        for (int serie = 0; serie < 2; serie++) {
            for (int i = 0; i < 4; i++) {
                assertThat(connecter("Alice", MOT_DE_PASSE_FAUX).statusCode()).as("série " + serie).isEqualTo(401);
            }
            Jeton jeton = api.jetonValide();
            HttpResponse<String> reponse = api.postJson("/api/connexion", ApiHttp.CORPS_VALIDE.formatted("Alice"), jeton);
            assertThat(reponse.statusCode()).as("connexion série " + serie).isEqualTo(200);
            String session = ApiHttp.valeurCookie(reponse, "JSESSIONID");
            HttpResponse<String> deconnexion = api.requete("POST", "/api/deconnexion",
                    Map.of("Cookie", "JSESSIONID=" + session + "; XSRF-TOKEN=" + jeton.cookie(),
                            "X-XSRF-TOKEN", jeton.entete()), null, null);
            assertThat(deconnexion.statusCode()).isEqualTo(204);
        }
    }

    // ---------------------------------------------------------------- CA17 (défauts ; paramétrage dans ConnexionConfigurationIntegrationTest)

    @Test
    @DisplayName("CA17 : sans propriété, la politique vaut 5 échecs et 900 secondes")
    void ca17_valeurs_par_defaut() {
        assertThat(politique.echecsMax()).isEqualTo(5);
        assertThat(politique.blocageSecondes()).isEqualTo(900);
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : 403, 409, 400 et 401 hors bornes priment sur le 429 d'un pseudo bloqué")
    void ca18_priorite_des_controles() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        creerCompte("Bob", MOT_DE_PASSE);
        bloquer("Alice");
        Jeton jeton = api.jetonValide();

        // 403 : pas de jeton CSRF.
        HttpResponse<String> sansJeton = api.postJson("/api/connexion", corps("Alice", MOT_DE_PASSE), null);
        assertThat(sansJeton.statusCode()).isEqualTo(403);
        assertThat(json.readTree(sansJeton.body()).get("code").asString()).isEqualTo("CSRF_INVALIDE");

        // 400 : champs requis.
        HttpResponse<String> vide = api.postJson("/api/connexion", "{}", jeton);
        assertThat(vide.statusCode()).isEqualTo(400);
        assertThat(json.readTree(vide.body()).get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");

        // 401 hors bornes : pseudo de 31 caractères, mot de passe de 129 caractères pour Alice.
        for (String saisie : List.of(corps("a".repeat(31), MOT_DE_PASSE), corps("Alice", "x".repeat(129)))) {
            HttpResponse<String> horsBornes = api.postJson("/api/connexion", saisie, jeton);
            assertThat(horsBornes.statusCode()).isEqualTo(401);
            assertThat(json.readTree(horsBornes.body()).get("code").asString()).isEqualTo("IDENTIFIANTS_INVALIDES");
        }

        // 409 : session ouverte (Bob), même avec le pseudo d'Alice bloquée.
        Map<String, String> entetesBob = sessionOuverte("Bob");
        HttpResponse<String> dejaConnecte = api.requete("POST", "/api/connexion", entetesBob, "application/json",
                corps("Alice", MOT_DE_PASSE));
        assertThat(dejaConnecte.statusCode()).isEqualTo(409);
        assertThat(json.readTree(dejaConnecte.body()).get("code").asString()).isEqualTo("DEJA_CONNECTE");

        // Alice est toujours bloquée.
        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- CA19

    @Test
    @DisplayName("CA19 : 400, 403, 401 hors bornes et 409 ne comptent pas comme des échecs")
    void ca19_refus_non_comptes() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        creerCompte("Bob", MOT_DE_PASSE);
        Jeton jeton = api.jetonValide();
        Map<String, String> entetesBob = sessionOuverte("Bob");

        for (int i = 0; i < 10; i++) {
            assertThat(api.postJson("/api/connexion", "{}", jeton).statusCode()).isEqualTo(400);
            assertThat(api.postJson("/api/connexion", corps("Alice", MOT_DE_PASSE_FAUX), null).statusCode())
                    .isEqualTo(403);
            assertThat(api.postJson("/api/connexion", corps("a".repeat(31), MOT_DE_PASSE_FAUX), jeton).statusCode())
                    .isEqualTo(401);
            assertThat(api.postJson("/api/connexion", corps("Alice", "x".repeat(129)), jeton).statusCode())
                    .isEqualTo(401);
            assertThat(api.requete("POST", "/api/connexion", entetesBob, "application/json",
                    corps("Alice", MOT_DE_PASSE_FAUX)).statusCode()).isEqualTo(409);
        }

        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA20

    @Test
    @DisplayName("CA20 : journal WARN au déclenchement, INFO par refus, sans pseudo ni mot de passe")
    void ca20_journal_du_blocage() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(connecter("Inconnu", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
        }
        assertThat(connecter("Inconnu", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        assertThat(connecter("Inconnu", MOT_DE_PASSE).statusCode()).isEqualTo(429);

        List<ILoggingEvent> evenements = new ArrayList<>(journal.list);
        assertThat(evenements.stream().filter(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("Connexion bloquée temporairement"))).hasSize(1);
        assertThat(evenements.stream().filter(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("Connexion refusée : blocage en cours"))).hasSize(2);
        assertThat(lignes(Level.INFO)).noneMatch(l -> l.contains("Inconnu") || l.contains(MOT_DE_PASSE_FAUX)
                || l.contains(MOT_DE_PASSE));
        assertThat(lignes(Level.TRACE)).noneMatch(l -> l.contains(MOT_DE_PASSE_FAUX) || l.contains(MOT_DE_PASSE));
    }

    // ---------------------------------------------------------------- CA21

    @Test
    @DisplayName("CA21 : 20 échecs simultanés sur un pseudo, uniquement des 401 ou 429, au moins 5 en 401, puis 429")
    void ca21_echecs_concurrents() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        int nombre = 20;
        List<Jeton> jetons = new ArrayList<>();
        for (int i = 0; i < nombre; i++) {
            jetons.add(api.jetonValide());
        }
        ExecutorService executeur = Executors.newFixedThreadPool(nombre);
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<Integer>> futurs = new ArrayList<>();
        try {
            for (Jeton jeton : jetons) {
                Callable<Integer> tache = () -> {
                    depart.await();
                    return api.postJson("/api/connexion", corps("Alice", MOT_DE_PASSE_FAUX), jeton).statusCode();
                };
                futurs.add(executeur.submit(tache));
            }
            depart.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> futur : futurs) {
                codes.add(futur.get());
            }
            assertThat(codes).allMatch(code -> code == 401 || code == 429);
            assertThat(codes.stream().filter(code -> code == 401).count()).isGreaterThanOrEqualTo(5);
        } finally {
            executeur.shutdownNow();
        }

        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- CA23

    @Test
    @DisplayName("CA23 : le blocage d'Alice ne ferme pas sa session et n'affecte ni Bob, ni la déconnexion, ni la création de compte")
    void ca23_isolation_et_non_regression() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        creerCompte("Bob", MOT_DE_PASSE);
        Jeton jetonAlice = api.jetonValide();
        HttpResponse<String> ouverture = api.postJson("/api/connexion", corps("Alice", MOT_DE_PASSE), jetonAlice);
        assertThat(ouverture.statusCode()).isEqualTo(200);
        String sessionAlice = ApiHttp.valeurCookie(ouverture, "JSESSIONID");

        // Alice est bloquée depuis un autre client (sans session).
        bloquer("Alice");
        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(429);

        HttpResponse<String> moi = api.requete("GET", "/api/comptes/moi",
                Map.of("Cookie", "JSESSIONID=" + sessionAlice), null, null);
        assertThat(moi.statusCode()).isEqualTo(200);
        assertThat(json.readTree(moi.body()).get("pseudo").asString()).isEqualTo("Alice");

        HttpResponse<String> deconnexion = api.requete("POST", "/api/deconnexion",
                Map.of("Cookie", "JSESSIONID=" + sessionAlice + "; XSRF-TOKEN=" + jetonAlice.cookie(),
                        "X-XSRF-TOKEN", jetonAlice.entete()), null, null);
        assertThat(deconnexion.statusCode()).isEqualTo(204);

        HttpResponse<String> bob = connecter("Bob", MOT_DE_PASSE);
        assertThat(bob.statusCode()).isEqualTo(200);
        assertThat(json.readTree(bob.body()).get("pseudo").asString()).isEqualTo("Bob");

        HttpResponse<String> creation = api.postJson("/api/comptes", corps("Nouveau", MOT_DE_PASSE), api.jetonValide());
        assertThat(creation.statusCode()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- utilitaires

    private void bloquer(String pseudo) throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(connecter(pseudo, MOT_DE_PASSE_FAUX).statusCode()).as("échec " + i).isEqualTo(401);
        }
    }

    private static String corps(String pseudo, String motDePasse) {
        return "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}";
    }

    /** Ouvre une session et retourne les en-têtes (cookies + CSRF) d'un client connecté. */
    private Map<String, String> sessionOuverte(String pseudo) throws Exception {
        Jeton jeton = api.jetonValide();
        HttpResponse<String> reponse = api.postJson("/api/connexion", corps(pseudo, MOT_DE_PASSE), jeton);
        assertThat(reponse.statusCode()).isEqualTo(200);
        Map<String, String> entetes = new HashMap<>();
        entetes.put("Cookie", "JSESSIONID=" + ApiHttp.valeurCookie(reponse, "JSESSIONID") + "; XSRF-TOKEN="
                + jeton.cookie());
        entetes.put("X-XSRF-TOKEN", jeton.entete());
        return entetes;
    }

    private UUID creerCompte(String pseudo, String motDePasse) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le)
                values (?, ?, ?, ?, 'COUREUR', ?)""",
                id, pseudo, pseudo.toLowerCase(Locale.ROOT),
                motDePasse == null ? null : empreinteDeReference, java.sql.Timestamp.from(T));
        return id;
    }

    private HttpResponse<String> connecter(String pseudo, String motDePasse) throws Exception {
        return api.postJson("/api/connexion", corps(pseudo, motDePasse), api.jetonValide());
    }

    private List<String> lignes(Level niveauMinimal) {
        List<String> lignes = new ArrayList<>();
        for (ILoggingEvent evenement : new ArrayList<>(journal.list)) {
            if (!evenement.getLevel().isGreaterOrEqual(niveauMinimal)) {
                continue;
            }
            lignes.add(evenement.getFormattedMessage());
            if (evenement.getThrowableProxy() != null) {
                lignes.add(ThrowableProxyUtil.asString(evenement.getThrowableProxy()));
            }
        }
        return lignes;
    }

    private static Map<String, List<String>> entetesSansDate(HttpResponse<?> reponse) {
        Map<String, List<String>> entetes = new TreeMap<>(reponse.headers().map());
        entetes.remove("date");
        return entetes;
    }
}
