package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.BackyardUltraTrackerApplication;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrat de l'incrément 1.4 au démarrage de l'api : initialisation de l'admin master par l'ApplicationRunner
 * (CA13 à CA17). Chaque test démarre de vraies applications Spring sur une base PostgreSQL migrée par Liquibase.
 */
@Testcontainers
class AdminMasterDemarrageIntegrationTest {

    static final String PSEUDO = "Patron";
    static final String MOT_DE_PASSE = "mot-de-passe-patron-1";
    static final String VALIDE = "secret-de-test-123";
    static final String TROP_COURT = "court-secre";
    static final String VARIABLE_PSEUDO = "ADMIN_MASTER_PSEUDO";
    static final String VARIABLE_MOT_DE_PASSE = "ADMIN_MASTER_MOT_DE_PASSE";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    ListAppender<ILoggingEvent> journal;

    @BeforeEach
    void preparer() {
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        journal = new ListAppender<>();
        journal.list = new java.util.concurrent.CopyOnWriteArrayList<>(); // liste sûre face aux threads qui journalisent
        journal.start();
        // Premier test : le schéma n'existe pas encore, une première application le crée.
        if (Boolean.FALSE.equals(jdbc.queryForObject("select to_regclass('public.compte') is not null", Boolean.class))) {
            demarrer(Map.of()).close();
        }
        jdbc.update("delete from compte");
        journal.list.clear();
    }

    @AfterEach
    void nettoyer() {
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.detachAppender(journal);
        racine.setLevel(Level.INFO);
    }

    /** L'arrêt d'une application retire le pont JUL : on le remet pour les autres tests du module. */
    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : base vide et variables valides, un ADMIN_MASTER Patron en Argon2id, connexion 'patron' en 200 ADMIN_MASTER")
    void ca13_creation_au_demarrage() throws Exception {
        try (ConfigurableApplicationContext contexte = demarrer(Map.of(
                "backyard.admin-master.pseudo", PSEUDO, "backyard.admin-master.mot-de-passe", MOT_DE_PASSE))) {

            List<Map<String, Object>> lignes = jdbc.queryForList("select * from compte");
            assertThat(lignes).hasSize(1);
            Map<String, Object> ligne = lignes.get(0);
            assertThat(ligne.get("pseudo")).isEqualTo(PSEUDO);
            assertThat(ligne.get("pseudo_normalise")).isEqualTo("patron");
            assertThat(ligne.get("role")).isEqualTo("ADMIN_MASTER");
            assertThat((String) ligne.get("empreinte_mot_de_passe")).startsWith("$argon2id$")
                    .isNotEqualTo(MOT_DE_PASSE).doesNotContain(MOT_DE_PASSE);

            ApiHttp api = new ApiHttp(port(contexte));
            HttpResponse<String> connexion = connecter(api, "patron", MOT_DE_PASSE);
            assertThat(connexion.statusCode()).isEqualTo(200);
            JsonNode corps = json.readTree(connexion.body());
            assertThat(corps.get("pseudo").asString()).isEqualTo(PSEUDO);
            assertThat(corps.get("role").asString()).isEqualTo("ADMIN_MASTER");
            assertThat(corps.get("id").asString()).isEqualTo(ligne.get("id").toString());

            HttpResponse<String> moi = api.requete("GET", "/api/comptes/moi", Map.of("Cookie",
                    "JSESSIONID=" + ApiHttp.valeurCookie(connexion, "JSESSIONID")), null, null);
            assertThat(moi.statusCode()).isEqualTo(200);
            assertThat(json.readTree(moi.body()).get("role").asString()).isEqualTo("ADMIN_MASTER");
        }
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : redémarrages avec les mêmes variables puis avec d'autres, un seul ADMIN_MASTER Patron, empreinte inchangée, ancien mot de passe valable")
    void ca14_idempotence_au_redemarrage() throws Exception {
        try (ConfigurableApplicationContext premier = demarrer(proprietes(PSEUDO, MOT_DE_PASSE))) {
            assertThat(premier.isRunning()).isTrue();
        }
        String empreinte = empreinteAdminMaster();
        UUID id = idAdminMaster();

        try (ConfigurableApplicationContext deuxieme = demarrer(proprietes(PSEUDO, MOT_DE_PASSE))) {
            assertUnSeulAdminMaster(empreinte, id);
            assertThat(deuxieme.isRunning()).isTrue();
        }
        try (ConfigurableApplicationContext troisieme = demarrer(proprietes("Autre", "autre-mot-de-passe-9"))) {
            assertUnSeulAdminMaster(empreinte, id);
            assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(1);

            ApiHttp api = new ApiHttp(port(troisieme));
            assertThat(connecter(api, PSEUDO, MOT_DE_PASSE).statusCode()).isEqualTo(200);
            assertThat(connecter(api, PSEUDO, "autre-mot-de-passe-9").statusCode()).isEqualTo(401);
            assertThat(connecter(api, "Autre", "autre-mot-de-passe-9").statusCode()).isEqualTo(401);
        }
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : sans variables sur base vide, le contexte démarre, aucun ADMIN_MASTER et un WARN ; avec un admin master, INFO « déjà présent » et aucun WARN")
    void ca15_variables_absentes() {
        try (ConfigurableApplicationContext contexte = demarrer(Map.of())) {
            assertThat(contexte.isRunning()).isTrue();
        }
        assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN_MASTER'", Integer.class))
                .isZero();
        assertThat(lignes(Level.WARN)).anySatisfy(ligne -> assertThat(ligne)
                .contains("Aucun admin master n'existe et ADMIN_MASTER_PSEUDO / ADMIN_MASTER_MOT_DE_PASSE "
                        + "ne sont pas renseignées"));

        // Sur une base qui a déjà un admin master : variables absentes ignorées.
        journal.list.clear();
        try (ConfigurableApplicationContext premier = demarrer(proprietes(PSEUDO, MOT_DE_PASSE))) {
            assertThat(premier.isRunning()).isTrue();
        }
        journal.list.clear();
        try (ConfigurableApplicationContext contexte = demarrer(Map.of())) {
            assertThat(contexte.isRunning()).isTrue();
        }
        assertThat(lignes(Level.INFO)).anySatisfy(ligne -> assertThat(ligne).contains("Admin master déjà présent"));
        assertThat(lignes(Level.WARN)).noneSatisfy(ligne -> assertThat(ligne.toLowerCase()).contains("admin master"));
        assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN_MASTER'", Integer.class))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA16

    static List<Arguments> configurationsRefusees() {
        return List.of(
                Arguments.of("seulement le pseudo", PSEUDO, "", VARIABLE_MOT_DE_PASSE, false),
                Arguments.of("seulement le mot de passe", "", VALIDE, VARIABLE_PSEUDO, false),
                Arguments.of("pseudo ab", "ab", VALIDE, VARIABLE_PSEUDO, false),
                Arguments.of("mot de passe de 11 caractères", PSEUDO, TROP_COURT, VARIABLE_MOT_DE_PASSE, false),
                Arguments.of("pseudo d'un coureur existant", "Alice", VALIDE, VARIABLE_PSEUDO, true));
    }

    @ParameterizedTest(name = "CA16 : {0}")
    @MethodSource("configurationsRefusees")
    @DisplayName("CA16 : configuration refusée, démarrage en échec, message citant la variable sans le mot de passe, base inchangée")
    void ca16_demarrage_refuse(String cas, String pseudo, String motDePasse, String variable, boolean avecAlice) {
        String empreinteAlice = "$argon2id$v=19$m=19456,t=2,p=1$aaaaaaaaaaaa$bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        if (avecAlice) {
            jdbc.update("insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le) "
                            + "values (?, 'Alice', 'alice', ?, 'COUREUR', ?)", UUID.randomUUID(), empreinteAlice,
                    Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        }

        assertThatThrownBy(() -> demarrer(proprietes(pseudo, motDePasse)).close()).satisfies(erreur -> {
            String messages = messagesDeLaChaine(erreur);
            assertThat(messages).as(cas).contains(variable);
            assertThat(messages).doesNotContain(VALIDE).doesNotContain(TROP_COURT);
        });

        assertThat(jdbc.queryForObject("select count(*) from compte where role = 'ADMIN_MASTER'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(avecAlice ? 1 : 0);
        if (avecAlice) {
            Map<String, Object> alice = jdbc.queryForMap("select * from compte");
            assertThat(alice.get("pseudo")).isEqualTo("Alice");
            assertThat(alice.get("role")).isEqualTo("COUREUR");
            assertThat(alice.get("empreinte_mot_de_passe")).isEqualTo(empreinteAlice);
        }
        assertThat(texteDuJournal(null)).doesNotContain(VALIDE).doesNotContain(TROP_COURT);
    }

    @Test
    @DisplayName("CA16 : pseudo pris par un coureur (casse différente), le message dit « déjà utilisé » et cite ADMIN_MASTER_PSEUDO")
    void ca16_pseudo_deja_utilise_message() {
        jdbc.update("insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le) "
                        + "values (?, 'Alice', 'alice', null, 'COUREUR', ?)", UUID.randomUUID(),
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));

        assertThatThrownBy(() -> demarrer(proprietes("ALICE", VALIDE)).close()).satisfies(erreur ->
                assertThat(messagesDeLaChaine(erreur)).contains("ADMIN_MASTER_PSEUDO").contains("déjà utilisé"));
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : succès puis échec au démarrage, jamais les mots de passe dans le journal, jamais le pseudo à partir d'INFO, l'id du compte à la création")
    void ca17_journaux_sans_secret() {
        try (ConfigurableApplicationContext contexte = demarrer(proprietes(PSEUDO, VALIDE))) {
            assertThat(contexte.isRunning()).isTrue();
        }
        String idCompte = idAdminMaster().toString();
        assertThat(lignes(Level.INFO)).anySatisfy(ligne -> assertThat(ligne)
                .contains("Admin master créé").contains(idCompte));

        jdbc.update("delete from compte");
        assertThatThrownBy(() -> demarrer(proprietes(PSEUDO, TROP_COURT)).close())
                .satisfies(erreur -> assertThat(messagesDeLaChaine(erreur)).contains(VARIABLE_MOT_DE_PASSE));

        assertThat(texteDuJournal(null)).doesNotContain(VALIDE).doesNotContain(TROP_COURT);
        assertThat(texteDuJournal(Level.INFO)).doesNotContain(PSEUDO);
        assertThat(journal.list).isNotEmpty();
    }

    // ---------------------------------------------------------------- utilitaires

    private static Map<String, String> proprietes(String pseudo, String motDePasse) {
        Map<String, String> proprietes = new java.util.LinkedHashMap<>();
        if (!pseudo.isEmpty()) {
            proprietes.put("backyard.admin-master.pseudo", pseudo);
        }
        if (!motDePasse.isEmpty()) {
            proprietes.put("backyard.admin-master.mot-de-passe", motDePasse);
        }
        return proprietes;
    }

    /** Démarre l'application complète sur la base de test, sur un port libre, journal capturé. */
    private ConfigurableApplicationContext demarrer(Map<String, String> proprietes) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0"));
        proprietes.forEach((cle, valeur) -> arguments.add("--" + cle + "=" + valeur));
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        return new SpringApplicationBuilder(BackyardUltraTrackerApplication.class)
                // Après l'initialisation du système de journalisation par Spring Boot, qui réinitialise Logback.
                .listeners(evenement -> {
                    if (evenement instanceof ApplicationPreparedEvent) {
                        // L'arrêt d'une application précédente du même JVM retire le pont JUL (System.Logger).
                        SLF4JBridgeHandler.removeHandlersForRootLogger();
                        SLF4JBridgeHandler.install();
                        // La réinitialisation de Logback par Spring Boot arrête les appenders encore attachés.
                        racine.detachAppender(journal);
                        journal.start();
                        racine.addAppender(journal);
                        racine.setLevel(Level.DEBUG);
                    }
                })
                .run(arguments.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext contexte) {
        return Integer.parseInt(contexte.getEnvironment().getProperty("local.server.port"));
    }

    private HttpResponse<String> connecter(ApiHttp api, String pseudo, String motDePasse) {
        try {
            return api.postJson("/api/connexion",
                    "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}", api.jetonValide());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String empreinteAdminMaster() {
        return jdbc.queryForObject("select empreinte_mot_de_passe from compte where role = 'ADMIN_MASTER'",
                String.class);
    }

    private UUID idAdminMaster() {
        return jdbc.queryForObject("select id from compte where role = 'ADMIN_MASTER'", UUID.class);
    }

    private void assertUnSeulAdminMaster(String empreinte, UUID id) {
        assertThat(jdbc.queryForList("select pseudo from compte where role = 'ADMIN_MASTER'", String.class))
                .containsExactly(PSEUDO);
        assertThat(empreinteAdminMaster()).isEqualTo(empreinte);
        assertThat(idAdminMaster()).isEqualTo(id);
    }

    private static String messagesDeLaChaine(Throwable erreur) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = erreur; t != null; t = t.getCause()) {
            messages.add(t + " " + t.getMessage());
        }
        return String.join(" | ", messages);
    }

    private List<String> lignes(Level niveauMinimal) {
        return journal.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(niveauMinimal))
                .map(AdminMasterDemarrageIntegrationTest::texte).toList();
    }

    /** Tout le journal capturé (message, arguments formatés, exceptions), à partir d'un niveau ou tous niveaux. */
    private String texteDuJournal(Level niveauMinimal) {
        return journal.list.stream().filter(e -> niveauMinimal == null || e.getLevel().isGreaterOrEqual(niveauMinimal))
                .map(AdminMasterDemarrageIntegrationTest::texte).collect(Collectors.joining("\n"));
    }

    private static String texte(ILoggingEvent evenement) {
        String exception = evenement.getThrowableProxy() == null ? ""
                : "\n" + ThrowableProxyUtil.asString(evenement.getThrowableProxy());
        return evenement.getFormattedMessage() + exception;
    }
}
