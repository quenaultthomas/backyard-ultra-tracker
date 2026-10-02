package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contrat d'API de l'incrément 1.1 (création d'un compte coureur, CSRF, sécurité par défaut). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(CompteIntegrationTest.HorlogeDeTest.class)
class CompteIntegrationTest {

    static final Instant INSTANT_FIXE = Instant.parse("2026-10-02T10:00:00Z");
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String MOT_DE_PASSE_COURT = "court-12345";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @TestConfiguration(proxyBeanMethods = false)
    static class HorlogeDeTest {
        @Bean
        @Primary
        Clock horlogeDeTest() {
            return Clock.fixed(INSTANT_FIXE, ZoneOffset.UTC);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder encodeur;

    JdbcTemplate jdbc;
    ApiHttp api;
    final JsonMapper json = JsonMapper.builder().build();
    ListAppender<ILoggingEvent> journal;

    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        api = new ApiHttp(port);
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

    // ---------------------------------------------------------------- CA9, CA10, CA11, CA15

    @Test
    @DisplayName("CA9 : création d'un compte coureur, 201 avec id, pseudo sans espaces, rôle COUREUR, sans secret")
    void ca9_creation_nominale() throws Exception {
        Jeton jeton = api.jetonValide();

        HttpResponse<String> reponse = api.postJson("/api/comptes",
                "{\"pseudo\":\"  Alice \",\"motDePasse\":\"" + MOT_DE_PASSE + "\"}", jeton);

        assertThat(reponse.statusCode()).isEqualTo(201);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("json"));
        assertThat(reponse.headers().firstValue("location")).isEmpty();
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe");
        assertThat(UUID.fromString(corps.get("id").asString())).isNotNull();
        assertThat(corps.get("pseudo").asString()).isEqualTo("Alice");
        assertThat(corps.get("role").asString()).isEqualTo("COUREUR");
        assertThat(Instant.parse(corps.get("creeLe").asString())).isEqualTo(INSTANT_FIXE);
        assertThat(reponse.body()).doesNotContain(MOT_DE_PASSE).doesNotContain("argon2").doesNotContain("pseudoNormalise");
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA10 : la ligne en base porte le pseudo normalisé et une empreinte Argon2id vérifiable")
    void ca10_persistance_et_empreinte_argon2id() throws Exception {
        api.postJson("/api/comptes", "{\"pseudo\":\"  Alice \",\"motDePasse\":\"" + MOT_DE_PASSE + "\"}",
                api.jetonValide());

        Map<String, Object> ligne = jdbc.queryForMap("select * from compte");
        assertThat(ligne.get("pseudo")).isEqualTo("Alice");
        assertThat(ligne.get("pseudo_normalise")).isEqualTo("alice");
        assertThat(ligne.get("role")).isEqualTo("COUREUR");
        String empreinte = (String) ligne.get("empreinte_mot_de_passe");
        assertThat(empreinte).startsWith("$argon2id$").contains("m=19456,t=2,p=1").doesNotContain(MOT_DE_PASSE);
        assertThat(encodeur.matches(MOT_DE_PASSE, empreinte)).isTrue();
        assertThat(encodeur.matches("un-autre-mot-de-passe", empreinte)).isFalse();
    }

    @Test
    @DisplayName("CA11 : deux comptes de même mot de passe ont des empreintes différentes")
    void ca11_empreintes_differentes_pour_un_meme_mot_de_passe() throws Exception {
        Jeton jeton = api.jetonValide();
        assertThat(api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Premier"), jeton).statusCode())
                .isEqualTo(201);
        assertThat(api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Second"), jeton).statusCode())
                .isEqualTo(201);

        List<String> empreintes = jdbc.queryForList("select empreinte_mot_de_passe from compte", String.class);
        assertThat(empreintes).hasSize(2);
        assertThat(empreintes.get(0)).isNotEqualTo(empreintes.get(1));
    }

    @Test
    @DisplayName("CA15 : un champ role envoyé est ignoré, le compte est COUREUR (réponse et base)")
    void ca15_role_injecte_ignore() throws Exception {
        HttpResponse<String> reponse = api.postJson("/api/comptes",
                "{\"pseudo\":\"Zoe\",\"motDePasse\":\"" + MOT_DE_PASSE + "\",\"role\":\"ADMIN\",\"inconnu\":1}",
                api.jetonValide());

        assertThat(reponse.statusCode()).isEqualTo(201);
        assertThat(json.readTree(reponse.body()).get("role").asString()).isEqualTo("COUREUR");
        assertThat(jdbc.queryForObject("select role from compte where pseudo = 'Zoe'", String.class))
                .isEqualTo("COUREUR");
    }

    // ---------------------------------------------------------------- CA12, CA20

    @Test
    @DisplayName("CA12 : pseudo déjà utilisé, insensible à la casse, 409 PSEUDO_DEJA_UTILISE et une seule ligne")
    void ca12_unicite_insensible_a_la_casse() throws Exception {
        Jeton jeton = api.jetonValide();
        assertThat(api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Alice"), jeton).statusCode())
                .isEqualTo(201);

        for (String variante : List.of("alice", "ALICE")) {
            HttpResponse<String> reponse = api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted(variante), jeton);
            assertThat(reponse.statusCode()).as(variante).isEqualTo(409);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
            JsonNode corps = json.readTree(reponse.body());
            assertThat(corps.get("code").asString()).isEqualTo("PSEUDO_DEJA_UTILISE");
            assertThat(corps.get("detail").asString()).isEqualTo("Ce pseudo est déjà utilisé.");
            assertThat(corps.get("title").asString()).isEqualTo("Conflit");
            assertThat(corps.get("status").asInt()).isEqualTo(409);
        }
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select pseudo from compte", String.class)).isEqualTo("Alice");
    }

    @Test
    @DisplayName("CA20 : 5 créations simultanées du même pseudo, une 201, quatre 409, aucune 500")
    void ca20_concurrence_un_seul_gagnant() throws Exception {
        Jeton jeton = api.jetonValide();
        int nombre = 5;
        CountDownLatch depart = new CountDownLatch(1);
        List<Integer> statuts = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(nombre)) {
            List<Future<Integer>> futurs = new ArrayList<>();
            for (int i = 0; i < nombre; i++) {
                futurs.add(pool.submit(() -> {
                    depart.await();
                    return api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Course"), jeton).statusCode();
                }));
            }
            depart.countDown();
            for (Future<Integer> futur : futurs) {
                statuts.add(futur.get());
            }
        }

        assertThat(statuts).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuts).filteredOn(s -> s == 409).hasSize(4);
        assertThat(statuts).noneMatch(s -> s >= 500);
        assertThat(jdbc.queryForList("select pseudo from compte", String.class)).containsExactly("Course");
    }

    // ---------------------------------------------------------------- CA13, CA14

    @Test
    @DisplayName("CA13 : plusieurs violations dans un seul 400, sans écho du mot de passe, rien créé")
    void ca13_validation_plusieurs_violations() throws Exception {
        HttpResponse<String> reponse = api.postJson("/api/comptes",
                "{\"pseudo\":\"ab\",\"motDePasse\":\"" + MOT_DE_PASSE_COURT + "\"}", api.jetonValide());

        assertThat(reponse.statusCode()).isEqualTo(400);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
        assertThat(corps.get("title").asString()).isEqualTo("Requête invalide");
        assertThat(corps.get("detail").asString()).isEqualTo("Certains champs sont invalides.");
        assertThat(corps.get("type").asString()).isEqualTo("about:blank");
        assertThat(erreurs(corps)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pseudo", "PSEUDO_LONGUEUR", "motDePasse", "MOT_DE_PASSE_TROP_COURT"));
        assertThat(reponse.body()).doesNotContain(MOT_DE_PASSE_COURT);
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA13 : messages et codes de chaque règle de pseudo et de mot de passe du contrat")
    void ca13_messages_du_contrat() throws Exception {
        Jeton jeton = api.jetonValide();
        assertErreur(jeton, "ab", MOT_DE_PASSE, "pseudo", "PSEUDO_LONGUEUR",
                "Le pseudo doit faire entre 3 et 30 caractères.");
        assertErreur(jeton, "a".repeat(31), MOT_DE_PASSE, "pseudo", "PSEUDO_LONGUEUR",
                "Le pseudo doit faire entre 3 et 30 caractères.");
        assertErreur(jeton, "a b", MOT_DE_PASSE, "pseudo", "PSEUDO_CARACTERES",
                "Le pseudo ne peut contenir que des lettres, des chiffres, « . », « _ » et « - ».");
        assertErreur(jeton, "a@b", MOT_DE_PASSE, "pseudo", "PSEUDO_CARACTERES",
                "Le pseudo ne peut contenir que des lettres, des chiffres, « . », « _ » et « - ».");
        assertErreur(jeton, "   ", MOT_DE_PASSE, "pseudo", "PSEUDO_REQUIS", "Le pseudo est obligatoire.");
        assertErreur(jeton, "Bob", "x".repeat(11), "motDePasse", "MOT_DE_PASSE_TROP_COURT",
                "Le mot de passe doit faire au moins 12 caractères.");
        assertErreur(jeton, "Bob", "x".repeat(129), "motDePasse", "MOT_DE_PASSE_TROP_LONG",
                "Le mot de passe ne doit pas dépasser 128 caractères.");
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA13 : bornes acceptées, pseudo de 3 et 30 caractères, mot de passe de 12, 128, 12 espaces, 12 é")
    void ca13_bornes_acceptees() throws Exception {
        Jeton jeton = api.jetonValide();
        String[][] cas = {
                {"Bob", "x".repeat(12)}, {"b".repeat(30), "x".repeat(128)},
                {"Espaces", " ".repeat(12)}, {"éloïse_89", "é".repeat(12)}};
        for (String[] c : cas) {
            HttpResponse<String> reponse = api.postJson("/api/comptes",
                    "{\"pseudo\":\"" + c[0] + "\",\"motDePasse\":\"" + c[1] + "\"}", jeton);
            assertThat(reponse.statusCode()).as(c[0]).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("CA14 : champs absents ou null, 400 PSEUDO_REQUIS et MOT_DE_PASSE_REQUIS")
    void ca14_champs_requis() throws Exception {
        Jeton jeton = api.jetonValide();
        for (String corps : List.of("{}", "{\"pseudo\":null,\"motDePasse\":null}")) {
            HttpResponse<String> reponse = api.postJson("/api/comptes", corps, jeton);
            assertThat(reponse.statusCode()).as(corps).isEqualTo(400);
            JsonNode noeud = json.readTree(reponse.body());
            assertThat(noeud.get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
            assertThat(erreurs(noeud)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "pseudo", "PSEUDO_REQUIS", "motDePasse", "MOT_DE_PASSE_REQUIS"));
        }
    }

    @Test
    @DisplayName("CA14 : corps illisible ou mal typé, 400 CORPS_ILLISIBLE sans écho ; mauvais Content-Type, 415")
    void ca14_corps_illisible_et_content_type() throws Exception {
        Jeton jeton = api.jetonValide();
        for (String corps : List.of("{\"pseudo\":123,\"motDePasse\":\"un-mot-de-passe-12\"}", "pas-du-json", "")) {
            HttpResponse<String> reponse = api.postJson("/api/comptes", corps, jeton);
            assertThat(reponse.statusCode()).as(corps).isEqualTo(400);
            JsonNode noeud = json.readTree(reponse.body());
            assertThat(noeud.get("code").asString()).isEqualTo("CORPS_ILLISIBLE");
            assertThat(noeud.get("title").asString()).isEqualTo("Requête invalide");
            assertThat(noeud.get("detail").asString()).isEqualTo("Le corps de la requête est illisible.");
            assertThat(noeud.has("erreurs")).isFalse();
            assertThat(reponse.body()).doesNotContain(MOT_DE_PASSE).doesNotContain("pas-du-json");
        }
        HttpResponse<String> reponse = api.post("/api/comptes", "text/plain",
                ApiHttp.CORPS_VALIDE.formatted("Texte"), jeton);
        assertThat(reponse.statusCode()).isEqualTo(415);
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CA14 : 400 prioritaire sur 409 quand le format est invalide")
    void ca14_validation_prioritaire_sur_le_conflit() throws Exception {
        Jeton jeton = api.jetonValide();
        api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Alice"), jeton);

        HttpResponse<String> reponse = api.postJson("/api/comptes",
                "{\"pseudo\":\"Alice\",\"motDePasse\":\"court\"}", jeton);

        assertThat(reponse.statusCode()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- CA16, CA17

    @Test
    @DisplayName("CA16 : sans en-tête CSRF ou avec un en-tête différent du cookie, 403 CSRF_INVALIDE, rien créé ; jeton valide, 201")
    void ca16_csrf() throws Exception {
        String corps = ApiHttp.CORPS_VALIDE.formatted("Csrf");
        Jeton valide = api.jetonValide();

        List<Jeton> invalides = List.of(
                new Jeton(null, null),
                new Jeton(valide.cookie(), null),
                new Jeton(valide.cookie(), "valeur-differente"),
                new Jeton(null, valide.entete()));
        for (Jeton jeton : invalides) {
            HttpResponse<String> reponse = api.postJson("/api/comptes", corps, jeton);
            assertThat(reponse.statusCode()).as(jeton.toString()).isEqualTo(403);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
            JsonNode noeud = json.readTree(reponse.body());
            assertThat(noeud.get("code").asString()).isEqualTo("CSRF_INVALIDE");
            assertThat(noeud.get("title").asString()).isEqualTo("Accès refusé");
            assertThat(noeud.get("detail").asString()).isEqualTo("Jeton CSRF absent ou invalide.");
        }
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isZero();

        assertThat(api.postJson("/api/comptes", corps, valide).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("CA17 : GET /api/csrf répond 204 sans corps, cookie XSRF-TOKEN SameSite=Lax non HttpOnly, pas de JSESSIONID")
    void ca17_endpoint_csrf_et_aucune_session() throws Exception {
        HttpResponse<String> reponse = api.get("/api/csrf");

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        List<String> cookies = reponse.headers().allValues("set-cookie");
        String xsrf = cookies.stream().filter(c -> c.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
        assertThat(xsrf).containsIgnoringCase("SameSite=Lax").containsIgnoringCase("Path=/")
                .doesNotContainIgnoringCase("HttpOnly");
        assertThat(ApiHttp.valeurCookieXsrf(reponse)).isNotBlank();
        assertThat(cookies).noneMatch(c -> c.contains("JSESSIONID"));

        HttpResponse<String> creation = api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Sans"), api.jetonValide());
        assertThat(creation.statusCode()).isEqualTo(201);
        assertThat(creation.headers().allValues("set-cookie")).noneMatch(c -> c.contains("JSESSIONID"));
    }

    @Test
    @DisplayName("CA17 : derrière le proxy HTTPS (X-Forwarded-Proto), le cookie XSRF-TOKEN est Secure")
    void ca17_cookie_secure_derriere_proxy() throws Exception {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var requete = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://localhost:" + port + "/api/csrf"))
                    .header("X-Forwarded-Proto", "https").GET().build();
            HttpResponse<String> reponse = client.send(requete, HttpResponse.BodyHandlers.ofString());
            assertThat(reponse.statusCode()).isEqualTo(204);
            assertThat(reponse.headers().allValues("set-cookie")).anyMatch(
                    c -> c.startsWith("XSRF-TOKEN=") && c.toLowerCase().contains("secure"));
        }
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : tout autre /api/** non authentifié répond 401 NON_AUTHENTIFIE sans WWW-Authenticate")
    void ca18_refus_par_defaut() throws Exception {
        List<HttpResponse<String>> reponses = List.of(
                api.get("/api/inexistant"),
                api.get("/api/comptes"),
                api.postJson("/api/inexistant", "{}", api.jetonValide()));
        for (HttpResponse<String> reponse : reponses) {
            assertThat(reponse.statusCode()).isEqualTo(401);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
            assertThat(reponse.headers().firstValue("www-authenticate")).isEmpty();
            JsonNode noeud = json.readTree(reponse.body());
            assertThat(noeud.get("code").asString()).isEqualTo("NON_AUTHENTIFIE");
            assertThat(noeud.get("title").asString()).isEqualTo("Authentification requise");
            assertThat(noeud.get("detail").asString()).isEqualTo("Vous devez être connecté.");
        }
    }

    @Test
    @DisplayName("CA18 : /actuator/health reste public en 200 UP, /api/sante n'est pas protégé, /actuator/env n'est pas 200")
    void ca18_endpoints_publics_et_actuator() throws Exception {
        HttpResponse<String> sante = api.get("/actuator/health");
        assertThat(sante.statusCode()).isEqualTo(200);
        assertThat(json.readTree(sante.body()).get("status").asString()).isEqualTo("UP");

        // /api/sante est servi par le proxy Caddy : côté API il est seulement non protégé (jamais 401).
        assertThat(api.get("/api/sante").statusCode()).isNotEqualTo(401);
        assertThat(api.get("/actuator/env").statusCode()).isNotEqualTo(200);
    }

    // ---------------------------------------------------------------- CA19

    @Test
    @DisplayName("CA19 : aucun mot de passe en clair dans les logs après succès, conflit, validation et corps illisible")
    void ca19_mot_de_passe_absent_des_logs() throws Exception {
        Jeton jeton = api.jetonValide();
        api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Alice"), jeton);
        api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("alice"), jeton);
        api.postJson("/api/comptes", "{\"pseudo\":\"ab\",\"motDePasse\":\"" + MOT_DE_PASSE_COURT + "\"}", jeton);
        api.postJson("/api/comptes", "{}", jeton);
        api.postJson("/api/comptes", "{\"pseudo\":123,\"motDePasse\":\"" + MOT_DE_PASSE + "\"}", jeton);
        api.postJson("/api/comptes", "pas-du-json " + MOT_DE_PASSE, jeton);

        List<String> lignes = new ArrayList<>();
        for (ILoggingEvent evenement : new ArrayList<>(journal.list)) {
            lignes.add(evenement.getFormattedMessage());
            if (evenement.getThrowableProxy() != null) {
                lignes.add(ThrowableProxyUtil.asString(evenement.getThrowableProxy()));
            }
        }
        assertThat(lignes).isNotEmpty();
        assertThat(lignes).noneMatch(l -> l.contains(MOT_DE_PASSE) || l.contains(MOT_DE_PASSE_COURT));
    }

    // ---------------------------------------------------------------- CA21

    @Test
    @DisplayName("CA21 : changeset 0002 appliqué, 0001 inchangé, table compte conforme à RG14")
    void ca21_schema_de_la_table_compte() {
        List<String> changesets = jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class);
        assertThat(changesets).containsExactly("0002-compte", "0003-admin-master-unique");

        Map<String, Map<String, Object>> colonnes = new java.util.HashMap<>();
        jdbc.queryForList("""
                select column_name, data_type, character_maximum_length, is_nullable
                from information_schema.columns where table_schema = 'public' and table_name = 'compte'""")
                .forEach(c -> colonnes.put((String) c.get("column_name"), c));
        assertThat(colonnes.keySet()).containsExactlyInAnyOrder(
                "id", "pseudo", "pseudo_normalise", "empreinte_mot_de_passe", "role", "cree_le");
        assertThat(colonnes.get("id").get("data_type")).isEqualTo("uuid");
        assertThat(colonnes.get("pseudo").get("character_maximum_length")).isEqualTo(30);
        assertThat(colonnes.get("pseudo").get("is_nullable")).isEqualTo("NO");
        assertThat(colonnes.get("pseudo_normalise").get("character_maximum_length")).isEqualTo(30);
        assertThat(colonnes.get("pseudo_normalise").get("is_nullable")).isEqualTo("NO");
        assertThat(colonnes.get("empreinte_mot_de_passe").get("character_maximum_length")).isEqualTo(255);
        assertThat(colonnes.get("empreinte_mot_de_passe").get("is_nullable")).isEqualTo("YES");
        assertThat(colonnes.get("role").get("character_maximum_length")).isEqualTo(20);
        assertThat(colonnes.get("role").get("is_nullable")).isEqualTo("NO");
        assertThat(colonnes.get("cree_le").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(colonnes.get("cree_le").get("is_nullable")).isEqualTo("NO");
    }

    @Test
    @DisplayName("CA21 : contraintes en base, clé primaire, unicité de pseudo_normalise, rôle contraint, empreinte nulle permise")
    void ca21_contraintes_de_la_table_compte() {
        String insertion = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le) "
                + "values (?, ?, ?, ?, ?, now())";
        jdbc.update(insertion, UUID.randomUUID(), "Anonyme", "anonyme", null, "COUREUR");
        assertThat(jdbc.queryForObject("select count(*) from compte where empreinte_mot_de_passe is null", Integer.class))
                .isEqualTo(1);

        assertRejete(() -> jdbc.update(insertion, UUID.randomUUID(), "ANONYME", "anonyme", null, "COUREUR"));
        assertRejete(() -> jdbc.update(insertion, UUID.randomUUID(), "Autre", "autre", null, "SUPER_ADMIN"));
        UUID id = UUID.randomUUID();
        jdbc.update(insertion, id, "Role", "role", null, "ADMIN_MASTER");
        assertRejete(() -> jdbc.update(insertion, id, "Role2", "role2", null, "ADMIN"));
        for (String role : List.of("ADMIN", "BENEVOLE")) {
            jdbc.update(insertion, UUID.randomUUID(), role, role.toLowerCase(), null, role);
        }
        Set<String> roles = new HashSet<>(jdbc.queryForList("select role from compte", String.class));
        assertThat(roles).containsExactlyInAnyOrder("COUREUR", "ADMIN_MASTER", "ADMIN", "BENEVOLE");
    }

    // ---------------------------------------------------------------- utilitaires

    private void assertErreur(Jeton jeton, String pseudo, String motDePasse, String champ, String code, String message)
            throws Exception {
        HttpResponse<String> reponse = api.postJson("/api/comptes",
                "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}", jeton);
        assertThat(reponse.statusCode()).as(pseudo).isEqualTo(400);
        JsonNode erreurs = json.readTree(reponse.body()).get("erreurs");
        assertThat(erreurs.size()).isEqualTo(1);
        assertThat(erreurs.get(0).get("champ").asString()).isEqualTo(champ);
        assertThat(erreurs.get(0).get("code").asString()).isEqualTo(code);
        assertThat(erreurs.get(0).get("message").asString()).isEqualTo(message);
    }

    private static Map<String, String> erreurs(JsonNode corps) {
        Map<String, String> resultat = new java.util.LinkedHashMap<>();
        for (JsonNode erreur : corps.get("erreurs")) {
            assertThat(resultat).doesNotContainKey(erreur.get("champ").asString());
            resultat.put(erreur.get("champ").asString(), erreur.get("code").asString());
        }
        return resultat;
    }

    private static void assertRejete(Runnable action) {
        org.assertj.core.api.Assertions.assertThatThrownBy(action::run)
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
