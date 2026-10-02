package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.infrastructure.EncodeurMotDePasseArgon2;
import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.catalina.Context;
import org.apache.catalina.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contrat d'API de l'incrément 1.2 : connexion, session serveur, déconnexion, règles d'accès. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ConnexionIntegrationTest {

    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String MOT_DE_PASSE_FAUX = "mauvais-mot-de-passe-1";
    static final Instant CREE_LE = Instant.parse("2026-09-01T08:30:00Z");
    static final String DETAIL_GENERIQUE = "Pseudo ou mot de passe incorrect.";

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
    ServletWebServerApplicationContext contexteWeb;

    @MockitoSpyBean
    EncodeurMotDePasseArgon2 encodeurEspionne;

    JdbcTemplate jdbc;
    ApiHttp api;
    final JsonMapper json = JsonMapper.builder().build();
    ListAppender<ILoggingEvent> journal;
    static String empreinteDeReference;

    /** Cookies d'un client connecté. */
    record Connexion(String session, String xsrf) {
        String cookie() {
            return "JSESSIONID=" + session + "; XSRF-TOKEN=" + xsrf;
        }
    }

    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        api = new ApiHttp(port);
        if (empreinteDeReference == null) {
            empreinteDeReference = encodeur.encode(MOT_DE_PASSE);
        }
        clearInvocations(encodeurEspionne);
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

    // ---------------------------------------------------------------- CA10, CA11

    @Test
    @DisplayName("CA10 : connexion nominale, 200 avec le compte sans secret et un cookie JSESSIONID")
    void ca10_connexion_nominale() throws Exception {
        UUID id = creerCompte("Alice", MOT_DE_PASSE);

        HttpResponse<String> reponse = connecter("Alice", MOT_DE_PASSE);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe");
        assertThat(UUID.fromString(corps.get("id").asString())).isEqualTo(id);
        assertThat(corps.get("pseudo").asString()).isEqualTo("Alice");
        assertThat(corps.get("role").asString()).isEqualTo("COUREUR");
        assertThat(Instant.parse(corps.get("creeLe").asString())).isEqualTo(CREE_LE);
        assertThat(reponse.body()).doesNotContain(MOT_DE_PASSE).doesNotContain("argon2")
                .doesNotContain("pseudoNormalise").doesNotContain("empreinte");
        assertThat(ApiHttp.valeurCookie(reponse, "JSESSIONID")).isNotBlank();
    }

    @Test
    @DisplayName("CA11 : pseudo insensible à la casse et aux espaces, mot de passe sensible à la casse")
    void ca11_casse_et_espaces() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);

        for (String saisie : List.of("ALICE", "  alice ")) {
            HttpResponse<String> reponse = connecter(saisie, MOT_DE_PASSE);
            assertThat(reponse.statusCode()).as(saisie).isEqualTo(200);
            assertThat(json.readTree(reponse.body()).get("pseudo").asString()).isEqualTo("Alice");
        }
        HttpResponse<String> reponse = connecter("Alice", "UN-MOT-DE-PASSE-12");
        assertThat(reponse.statusCode()).isEqualTo(401);
        assertThat(connecter("Alice", MOT_DE_PASSE + " ").statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- CA12, CA13, CA14

    @Test
    @DisplayName("CA12 : mot de passe faux, pseudo inconnu et compte sans empreinte donnent la même réponse 401")
    void ca12_echecs_indiscernables() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        creerCompte("Anonyme1", null);

        List<HttpResponse<String>> reponses = List.of(
                connecter("Alice", MOT_DE_PASSE_FAUX),
                connecter("Inconnu", MOT_DE_PASSE),
                connecter("Anonyme1", MOT_DE_PASSE));

        for (HttpResponse<String> reponse : reponses) {
            assertErreurGenerique(reponse);
        }
        assertThat(reponses.get(1).body()).isEqualTo(reponses.get(0).body());
        assertThat(reponses.get(2).body()).isEqualTo(reponses.get(0).body());
        assertThat(entetesSansDate(reponses.get(1))).isEqualTo(entetesSansDate(reponses.get(0)));
        assertThat(entetesSansDate(reponses.get(2))).isEqualTo(entetesSansDate(reponses.get(0)));
        for (HttpResponse<String> reponse : reponses) {
            assertThat(reponse.body()).doesNotContain("Alice").doesNotContain("Inconnu")
                    .doesNotContain("Anonyme1").doesNotContain(MOT_DE_PASSE).doesNotContain(MOT_DE_PASSE_FAUX);
        }
    }

    @Test
    @DisplayName("CA13 : chaque échec d'identification provoque exactement 1 vérification, contre une empreinte Argon2id m=19456,t=2,p=1")
    void ca13_une_verification_par_echec() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        creerCompte("Anonyme1", null);

        assertThat(mockingDetails(encodeurEspionne).isSpy()).isTrue();
        List<String> pseudos = List.of("Alice", "Inconnu", "Anonyme1");
        List<String> empreintes = new ArrayList<>();
        for (String pseudo : pseudos) {
            clearInvocations(encodeurEspionne);
            assertThat(connecter(pseudo, MOT_DE_PASSE_FAUX).statusCode()).as(pseudo).isEqualTo(401);

            ArgumentCaptor<String> empreinte = ArgumentCaptor.forClass(String.class);
            verify(encodeurEspionne, times(1)).verifier(anyString(), empreinte.capture());
            empreintes.add(empreinte.getValue());
        }
        assertThat(empreintes.get(0)).isEqualTo(empreinteEnBase("Alice"));
        for (String factice : empreintes.subList(1, 3)) {
            assertThat(factice).startsWith("$argon2id$").contains("m=19456,t=2,p=1");
            assertThat(factice).isNotEqualTo(empreintes.get(0));
        }
    }

    @Test
    @DisplayName("CA14 : valeurs hors bornes et pseudo au format invalide, 401 générique ; calcul seulement dans les bornes")
    void ca14_bornes_de_connexion() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        HttpResponse<String> reference = connecter("Alice", MOT_DE_PASSE_FAUX);

        // Hors bornes : refus immédiat, sans calcul.
        for (String[] saisie : new String[][]{{"a".repeat(31), MOT_DE_PASSE}, {"Alice", "x".repeat(129)}}) {
            clearInvocations(encodeurEspionne);
            HttpResponse<String> reponse = connecter(saisie[0], saisie[1]);
            assertErreurGenerique(reponse);
            assertThat(reponse.body()).isEqualTo(reference.body());
            verify(encodeurEspionne, never()).verifier(anyString(), anyString());
        }
        // Format invalide mais dans les bornes : 401 après une vérification.
        for (String[] saisie : new String[][]{{"a b", "court-12345"}, {"<script>", "court"}, {"b".repeat(30), MOT_DE_PASSE}}) {
            clearInvocations(encodeurEspionne);
            HttpResponse<String> reponse = connecter(saisie[0], saisie[1]);
            assertErreurGenerique(reponse);
            assertThat(reponse.body()).isEqualTo(reference.body());
            verify(encodeurEspionne, times(1)).verifier(anyString(), anyString());
        }
        // 128 caractères exactement : traité normalement (vérifié, 401 car faux).
        clearInvocations(encodeurEspionne);
        assertErreurGenerique(connecter("Alice", "x".repeat(128)));
        verify(encodeurEspionne, times(1)).verifier(anyString(), anyString());
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : champs requis absents, null ou vides, 400 VALIDATION_ECHOUEE avec les codes du contrat")
    void ca15_champs_requis() throws Exception {
        Map<String, Map<String, String>> attendus = new java.util.LinkedHashMap<>();
        attendus.put("{}", Map.of("pseudo", "PSEUDO_REQUIS", "motDePasse", "MOT_DE_PASSE_REQUIS"));
        attendus.put("{\"pseudo\":null,\"motDePasse\":null}",
                Map.of("pseudo", "PSEUDO_REQUIS", "motDePasse", "MOT_DE_PASSE_REQUIS"));
        attendus.put("{\"pseudo\":\"  \",\"motDePasse\":\"x\"}", Map.of("pseudo", "PSEUDO_REQUIS"));
        attendus.put("{\"pseudo\":\"Alice\",\"motDePasse\":\"\"}", Map.of("motDePasse", "MOT_DE_PASSE_REQUIS"));
        Jeton jeton = api.jetonValide();

        for (var cas : attendus.entrySet()) {
            HttpResponse<String> reponse = api.postJson("/api/connexion", cas.getKey(), jeton);
            assertThat(reponse.statusCode()).as(cas.getKey()).isEqualTo(400);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
            JsonNode corps = json.readTree(reponse.body());
            assertThat(corps.get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
            assertThat(corps.get("title").asString()).isEqualTo("Requête invalide");
            assertThat(corps.get("detail").asString()).isEqualTo("Certains champs sont invalides.");
            Map<String, String> erreurs = new java.util.HashMap<>();
            for (JsonNode erreur : corps.get("erreurs")) {
                erreurs.put(erreur.get("champ").asString(), erreur.get("code").asString());
                String message = erreur.get("message").asString();
                assertThat(message).isEqualTo(erreur.get("champ").asString().equals("pseudo")
                        ? "Le pseudo est obligatoire." : "Le mot de passe est obligatoire.");
            }
            assertThat(erreurs).isEqualTo(cas.getValue());
            assertThat(ApiHttp.valeurCookie(reponse, "JSESSIONID")).isNull();
        }
    }

    @Test
    @DisplayName("CA15 : un mot de passe d'espaces est une valeur, traité comme un mot de passe faux (401)")
    void ca15_mot_de_passe_d_espaces_est_une_valeur() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);

        assertErreurGenerique(connecter("Alice", "            "));
    }

    @Test
    @DisplayName("CA15 : corps illisible ou mal typé, 400 CORPS_ILLISIBLE sans écho ; Content-Type text/plain, 415 ; champ inconnu ignoré")
    void ca15_corps_illisible_et_content_type() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        Jeton jeton = api.jetonValide();

        for (String corps : List.of("{\"pseudo\":123,\"motDePasse\":\"x\"}", "pas-du-json")) {
            HttpResponse<String> reponse = api.postJson("/api/connexion", corps, jeton);
            assertThat(reponse.statusCode()).as(corps).isEqualTo(400);
            JsonNode noeud = json.readTree(reponse.body());
            assertThat(noeud.get("code").asString()).isEqualTo("CORPS_ILLISIBLE");
            assertThat(noeud.get("title").asString()).isEqualTo("Requête invalide");
            assertThat(noeud.get("detail").asString()).isEqualTo("Le corps de la requête est illisible.");
            assertThat(noeud.has("erreurs")).isFalse();
            assertThat(reponse.body()).doesNotContain("pas-du-json").doesNotContain("123");
        }
        assertThat(api.post("/api/connexion", "text/plain", ApiHttp.CORPS_VALIDE.formatted("Alice"), jeton)
                .statusCode()).isEqualTo(415);
        assertThat(api.postJson("/api/connexion",
                "{\"pseudo\":\"Alice\",\"motDePasse\":\"" + MOT_DE_PASSE + "\",\"role\":\"ADMIN\"}", jeton)
                .statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : aucun mot de passe ni pseudo saisi dans les logs (succès, mot de passe faux, pseudo inconnu)")
    void ca16_secrets_absents_des_logs() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);

        connecter("Alice", MOT_DE_PASSE);
        connecter("Alice", MOT_DE_PASSE_FAUX);
        connecter("Inconnu", MOT_DE_PASSE);

        // Mots de passe : absents à tous les niveaux (DEBUG compris).
        assertThat(lignes(Level.TRACE)).isNotEmpty();
        assertThat(lignes(Level.TRACE)).noneMatch(l -> l.contains(MOT_DE_PASSE) || l.contains(MOT_DE_PASSE_FAUX));
        // Pseudo saisi : absent des logs de niveau INFO et supérieur (RG4, RG8).
        assertThat(lignes(Level.INFO)).isNotEmpty();
        assertThat(lignes(Level.INFO)).noneMatch(l -> l.contains("Inconnu"));
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

    // ---------------------------------------------------------------- CA17, CA18, CA19, CA20

    @Test
    @DisplayName("CA17 : cookie de session HttpOnly, SameSite=Lax, Path=/, sans Max-Age ni Expires, sans Secure en HTTP")
    void ca17_attributs_du_cookie_en_http() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);

        String cookie = cookieSession(connecter("Alice", MOT_DE_PASSE));

        assertThat(cookie).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax")
                .containsIgnoringCase("Path=/").doesNotContainIgnoringCase("Max-Age")
                .doesNotContainIgnoringCase("Expires").doesNotContainIgnoringCase("Secure");
    }

    @Test
    @DisplayName("CA17 : derrière le proxy HTTPS (X-Forwarded-Proto), le cookie de session est Secure")
    void ca17_cookie_secure_derriere_proxy() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        Jeton jeton = api.jetonValide();

        HttpResponse<String> reponse = api.requete("POST", "/api/connexion",
                Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie(), "X-XSRF-TOKEN", jeton.entete(),
                        "X-Forwarded-Proto", "https"),
                "application/json", ApiHttp.CORPS_VALIDE.formatted("Alice"));

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(cookieSession(reponse)).containsIgnoringCase("Secure").containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax");
    }

    @Test
    @DisplayName("CA18 : cookie JSESSIONID forgé avant la connexion, l'identifiant renvoyé est neuf et le forgé n'ouvre rien")
    void ca18_fixation_de_session() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        Jeton jeton = api.jetonValide();

        HttpResponse<String> reponse = api.requete("POST", "/api/connexion",
                Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie() + "; JSESSIONID=forge",
                        "X-XSRF-TOKEN", jeton.entete()),
                "application/json", ApiHttp.CORPS_VALIDE.formatted("Alice"));

        assertThat(reponse.statusCode()).isEqualTo(200);
        String nouveau = ApiHttp.valeurCookie(reponse, "JSESSIONID");
        assertThat(nouveau).isNotBlank().isNotEqualTo("forge");
        assertThat(moi("forge").statusCode()).isEqualTo(401);
        assertThat(moi(nouveau).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA18 : avec une session déjà authentifiée, une nouvelle connexion est refusée (409) et l'identifiant reste valide")
    void ca18_session_existante_non_remplacee() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        Connexion alice = ouvrirSession("Alice");

        HttpResponse<String> reponse = posterConnecte("/api/connexion", alice, ApiHttp.CORPS_VALIDE.formatted("Alice"));

        assertThat(reponse.statusCode()).isEqualTo(409);
        assertThat(ApiHttp.valeurCookie(reponse, "JSESSIONID")).isNull();
        assertThat(moi(alice.session()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA19 : aucune requête anonyme (csrf, création de compte, connexion échouée, moi sans session) ne pose de JSESSIONID")
    void ca19_aucune_session_pour_les_requetes_anonymes() throws Exception {
        Jeton jeton = api.jetonValide();
        List<HttpResponse<String>> reponses = List.of(
                api.get("/api/csrf"),
                api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Nouveau"), jeton),
                connecter("Inconnu", MOT_DE_PASSE),
                api.get("/api/comptes/moi"),
                api.get("/api/inexistant"),
                api.postJson("/api/connexion", "{}", jeton));

        assertThat(reponses.get(1).statusCode()).isEqualTo(201);
        for (HttpResponse<String> reponse : reponses) {
            assertThat(reponse.headers().allValues("set-cookie")).noneMatch(c -> c.contains("JSESSIONID"));
        }
    }

    @Test
    @DisplayName("CA20 : session de 12 h d'inactivité portant l'identifiant du compte et le rôle COUREUR ; session expirée, 401")
    void ca20_session_serveur_et_expiration() throws Exception {
        UUID id = creerCompte("Alice", MOT_DE_PASSE);
        Connexion alice = ouvrirSession("Alice");

        Session session = sessionServeur(alice.session());
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(43_200);
        SecurityContext contexte = (SecurityContext) session.getSession().getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(contexte).isNotNull();
        assertThat(contexte.getAuthentication().getPrincipal()).isEqualTo(id);
        assertThat(contexte.getAuthentication().getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_COUREUR");

        session.expire();

        assertThat(moi(alice.session()).statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- CA21, CA22

    @Test
    @DisplayName("CA21 : déconnexion 204 sans corps, cookie effacé, ancien cookie sans accès ; idempotente ; l'autre session du compte survit")
    void ca21_deconnexion() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        Connexion premiere = ouvrirSession("Alice");
        Connexion seconde = ouvrirSession("Alice");

        HttpResponse<String> reponse = posterConnecte("/api/deconnexion", premiere, null);

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertThat(reponse.body()).isEmpty();
        String cookie = cookieSession(reponse);
        assertThat(cookie).startsWith("JSESSIONID=;").containsIgnoringCase("Max-Age=0").containsIgnoringCase("Path=/");
        assertThat(moi(premiere.session()).statusCode()).isEqualTo(401);
        assertThat(moi(seconde.session()).statusCode()).isEqualTo(200);

        Jeton jeton = api.jetonValide();
        HttpResponse<String> sansSession = api.post("/api/deconnexion", null, "", jeton);
        assertThat(sansSession.statusCode()).isEqualTo(204);
        assertThat(sansSession.body()).isEmpty();
    }

    @Test
    @DisplayName("CA22 : connexion et déconnexion sans en-tête CSRF ou avec un en-tête différent du cookie, 403 CSRF_INVALIDE sans effet sur la session")
    void ca22_csrf() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        Jeton valide = api.jetonValide();
        List<Jeton> invalides = List.of(new Jeton(valide.cookie(), null), new Jeton(valide.cookie(), "different"));

        for (Jeton jeton : invalides) {
            HttpResponse<String> reponse = api.postJson("/api/connexion", ApiHttp.CORPS_VALIDE.formatted("Alice"), jeton);
            assertCsrfInvalide(reponse);
            assertThat(ApiHttp.valeurCookie(reponse, "JSESSIONID")).isNull();
        }
        HttpResponse<String> connexion = api.postJson("/api/connexion", ApiHttp.CORPS_VALIDE.formatted("Alice"), valide);
        assertThat(connexion.statusCode()).isEqualTo(200);
        Connexion alice = new Connexion(ApiHttp.valeurCookie(connexion, "JSESSIONID"), valide.cookie());

        for (Jeton jeton : invalides) {
            HttpResponse<String> reponse = api.requete("POST", "/api/deconnexion",
                    enTetesCsrf(alice, jeton.entete()), null, null);
            assertCsrfInvalide(reponse);
            assertThat(moi(alice.session()).statusCode()).isEqualTo(200);
        }
        assertThat(posterConnecte("/api/deconnexion", alice, null).statusCode()).isEqualTo(204);
    }

    // ---------------------------------------------------------------- CA23, CA24

    @Test
    @DisplayName("CA23 : GET /api/comptes/moi renvoie le compte relu en base ; sans session, 401 NON_AUTHENTIFIE")
    void ca23_comptes_moi() throws Exception {
        UUID id = creerCompte("Alice", MOT_DE_PASSE);
        Connexion alice = ouvrirSession("Alice");

        HttpResponse<String> reponse = moi(alice.session());

        assertThat(reponse.statusCode()).isEqualTo(200);
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrder("id", "pseudo", "role", "creeLe");
        assertThat(corps.get("id").asString()).isEqualTo(id.toString());
        assertThat(corps.get("pseudo").asString()).isEqualTo("Alice");
        assertThat(corps.get("role").asString()).isEqualTo("COUREUR");
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();

        jdbc.update("update compte set pseudo = 'Alicia', pseudo_normalise = 'alicia' where id = ?", id);
        assertThat(json.readTree(moi(alice.session()).body()).get("pseudo").asString()).isEqualTo("Alicia");

        assertNonAuthentifie(api.get("/api/comptes/moi"));
    }

    @Test
    @DisplayName("CA24 : compte supprimé ou sans empreinte pendant la session, 401 NON_AUTHENTIFIE et session invalidée")
    void ca24_compte_disparu() throws Exception {
        UUID id = creerCompte("Alice", MOT_DE_PASSE);

        Connexion supprime = ouvrirSession("Alice");
        jdbc.update("delete from compte where id = ?", id);
        assertNonAuthentifie(moi(supprime.session()));
        assertThat(sessionServeur(supprime.session())).isNull();
        assertNonAuthentifie(moi(supprime.session()));

        creerCompte("Alice", MOT_DE_PASSE);
        Connexion anonymise = ouvrirSession("Alice");
        jdbc.update("update compte set empreinte_mot_de_passe = null where pseudo = 'Alice'");
        assertNonAuthentifie(moi(anonymise.session()));
        assertThat(sessionServeur(anonymise.session())).isNull();
        jdbc.update("update compte set empreinte_mot_de_passe = ? where pseudo = 'Alice'", empreinteDeReference);
        assertNonAuthentifie(moi(anonymise.session()));
    }

    // ---------------------------------------------------------------- CA25

    @Test
    @DisplayName("CA25 : sans session, endpoints publics accessibles et tout autre /api/** en 401 NON_AUTHENTIFIE sans WWW-Authenticate")
    void ca25_acces_sans_session() throws Exception {
        assertThat(api.get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(api.get("/api/csrf").statusCode()).isEqualTo(204);
        // /api/sante est servi par Caddy : côté API il est public (jamais 401).
        assertThat(api.get("/api/sante").statusCode()).isNotEqualTo(401);

        Jeton jeton = api.jetonValide();
        List<HttpResponse<String>> refus = List.of(
                api.get("/api/comptes/moi"),
                api.get("/api/comptes"),
                api.get("/api/inexistant"),
                api.postJson("/api/inexistant", "{}", jeton));
        for (HttpResponse<String> reponse : refus) {
            assertNonAuthentifie(reponse);
        }
        assertThat(api.get("/actuator/env").statusCode()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("CA25 : avec session, chemin inexistant en 404 RESSOURCE_INTROUVABLE (GET et POST), /actuator/env jamais 200")
    void ca25_acces_avec_session() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        Connexion alice = ouvrirSession("Alice");

        List<HttpResponse<String>> reponses = List.of(
                api.requete("GET", "/api/inexistant", Map.of("Cookie", alice.cookie()), null, null),
                posterConnecte("/api/inexistant", alice, "{}"));

        for (HttpResponse<String> reponse : reponses) {
            assertThat(reponse.statusCode()).isEqualTo(404);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
            JsonNode corps = json.readTree(reponse.body());
            assertThat(corps.get("code").asString()).isEqualTo("RESSOURCE_INTROUVABLE");
            assertThat(corps.get("title").asString()).isEqualTo("Introuvable");
            assertThat(corps.get("detail").asString()).isEqualTo("La ressource demandée est introuvable.");
            assertThat(corps.get("status").asInt()).isEqualTo(404);
        }
        HttpResponse<String> env = api.requete("GET", "/actuator/env", Map.of("Cookie", alice.cookie()), null, null);
        assertThat(env.statusCode()).isNotEqualTo(200);
    }

    // ---------------------------------------------------------------- CA26

    @Test
    @DisplayName("CA26 : utilisateur déjà connecté, 409 DEJA_CONNECTE sur connexion et création de compte (même corps invalide), rien créé")
    void ca26_deja_connecte() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        creerCompte("Bob", MOT_DE_PASSE);
        Connexion alice = ouvrirSession("Alice");

        List<HttpResponse<String>> reponses = List.of(
                posterConnecte("/api/connexion", alice, ApiHttp.CORPS_VALIDE.formatted("Alice")),
                posterConnecte("/api/connexion", alice, ApiHttp.CORPS_VALIDE.formatted("Bob")),
                posterConnecte("/api/comptes", alice, ApiHttp.CORPS_VALIDE.formatted("Nouveau")),
                posterConnecte("/api/comptes", alice, "{}"));

        for (HttpResponse<String> reponse : reponses) {
            assertThat(reponse.statusCode()).isEqualTo(409);
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
            JsonNode corps = json.readTree(reponse.body());
            assertThat(corps.get("code").asString()).isEqualTo("DEJA_CONNECTE");
            assertThat(corps.get("title").asString()).isEqualTo("Conflit");
            assertThat(corps.get("detail").asString()).isEqualTo("Vous êtes déjà connecté.");
            assertThat(ApiHttp.valeurCookie(reponse, "JSESSIONID")).isNull();
        }
        assertThat(jdbc.queryForObject("select count(*) from compte where pseudo = 'Nouveau'", Integer.class)).isZero();
        assertThat(json.readTree(moi(alice.session()).body()).get("pseudo").asString()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("CA26 : sans jeton CSRF, le 403 CSRF_INVALIDE passe avant le 409 DEJA_CONNECTE")
    void ca26_csrf_prioritaire_sur_deja_connecte() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);
        Connexion alice = ouvrirSession("Alice");

        for (String chemin : List.of("/api/connexion", "/api/comptes")) {
            HttpResponse<String> reponse = api.requete("POST", chemin, Map.of("Cookie", "JSESSIONID=" + alice.session()),
                    "application/json", ApiHttp.CORPS_VALIDE.formatted("Nouveau"));
            assertCsrfInvalide(reponse);
        }
        assertThat(jdbc.queryForObject("select count(*) from compte where pseudo = 'Nouveau'", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- CA27

    @Test
    @DisplayName("CA27 : 10 échecs d'affilée puis une connexion correcte, 200 (aucune limitation en 1.2)")
    void ca27_aucune_limitation_des_essais() throws Exception {
        creerCompte("Alice", MOT_DE_PASSE);

        for (int i = 0; i < 10; i++) {
            assertThat(connecter("Alice", MOT_DE_PASSE_FAUX).statusCode()).as("essai " + i).isEqualTo(401);
        }

        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- utilitaires

    /** Insère un compte COUREUR ; une empreinte nulle simule un compte anonymisé. */
    private UUID creerCompte(String pseudo, String motDePasse) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le)
                values (?, ?, ?, ?, 'COUREUR', ?)""",
                id, pseudo, pseudo.toLowerCase(java.util.Locale.ROOT),
                motDePasse == null ? null : empreinteDeReference, java.sql.Timestamp.from(CREE_LE));
        return id;
    }

    private String empreinteEnBase(String pseudo) {
        return jdbc.queryForObject("select empreinte_mot_de_passe from compte where pseudo = ?", String.class, pseudo);
    }

    private HttpResponse<String> connecter(String pseudo, String motDePasse) throws Exception {
        String corps = "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}";
        return api.postJson("/api/connexion", corps, api.jetonValide());
    }

    private Connexion ouvrirSession(String pseudo) throws Exception {
        Jeton jeton = api.jetonValide();
        HttpResponse<String> reponse = api.postJson("/api/connexion", ApiHttp.CORPS_VALIDE.formatted(pseudo), jeton);
        assertThat(reponse.statusCode()).isEqualTo(200);
        return new Connexion(ApiHttp.valeurCookie(reponse, "JSESSIONID"), jeton.cookie());
    }

    private HttpResponse<String> moi(String idSession) throws Exception {
        return api.requete("GET", "/api/comptes/moi", Map.of("Cookie", "JSESSIONID=" + idSession), null, null);
    }

    private HttpResponse<String> posterConnecte(String chemin, Connexion connexion, String corps) throws Exception {
        return api.requete("POST", chemin, enTetesCsrf(connexion, connexion.xsrf()),
                corps == null ? null : "application/json", corps);
    }

    private static Map<String, String> enTetesCsrf(Connexion connexion, String entete) {
        Map<String, String> entetes = new java.util.HashMap<>();
        entetes.put("Cookie", connexion.cookie());
        if (entete != null) {
            entetes.put("X-XSRF-TOKEN", entete);
        }
        return entetes;
    }

    private Session sessionServeur(String idSession) throws Exception {
        TomcatWebServer serveur = (TomcatWebServer) contexteWeb.getWebServer();
        Context contexte = (Context) serveur.getTomcat().getHost().findChildren()[0];
        return contexte.getManager().findSession(idSession);
    }

    private static String cookieSession(HttpResponse<?> reponse) {
        return reponse.headers().allValues("set-cookie").stream()
                .filter(c -> c.startsWith("JSESSIONID="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Aucun Set-Cookie JSESSIONID dans " + reponse.headers().map()));
    }

    private static Map<String, List<String>> entetesSansDate(HttpResponse<?> reponse) {
        Map<String, List<String>> entetes = new TreeMap<>(reponse.headers().map());
        entetes.remove("date");
        return entetes;
    }

    private void assertErreurGenerique(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(401);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
        assertThat(reponse.headers().firstValue("www-authenticate")).isEmpty();
        assertThat(reponse.headers().allValues("set-cookie")).noneMatch(c -> c.contains("JSESSIONID"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo("IDENTIFIANTS_INVALIDES");
        assertThat(corps.get("title").asString()).isEqualTo("Authentification échouée");
        assertThat(corps.get("detail").asString()).isEqualTo(DETAIL_GENERIQUE);
        assertThat(corps.get("status").asInt()).isEqualTo(401);
    }

    private void assertNonAuthentifie(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(401);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
        assertThat(reponse.headers().firstValue("www-authenticate")).isEmpty();
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo("NON_AUTHENTIFIE");
        assertThat(corps.get("title").asString()).isEqualTo("Authentification requise");
        assertThat(corps.get("detail").asString()).isEqualTo("Vous devez être connecté.");
    }

    private void assertCsrfInvalide(HttpResponse<String> reponse) throws Exception {
        assertThat(reponse.statusCode()).isEqualTo(403);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(c -> assertThat(c).contains("problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo("CSRF_INVALIDE");
        assertThat(corps.get("title").asString()).isEqualTo("Accès refusé");
        assertThat(corps.get("detail").asString()).isEqualTo("Jeton CSRF absent ou invalide.");
    }
}
