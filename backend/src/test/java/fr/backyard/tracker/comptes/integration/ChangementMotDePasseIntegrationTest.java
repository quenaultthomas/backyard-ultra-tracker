package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.exposition.ChangerMotDePasseRequete;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/**
 * Contrat de l'incrément 1.6b : PUT /api/comptes/moi/mot-de-passe (CA9 à CA17, hors redémarrage du contexte
 * traité dans {@link ChangementMotDePasseRedemarrageIntegrationTest}). Les comptes COUREUR, ADMIN et ADMIN_MASTER
 * sont créés par dépôt (insertion et initialisation de l'admin master) ; le BENEVOLE de CA9 l'est par l'API de 1.6a.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class ChangementMotDePasseIntegrationTest {

    static final String CHEMIN = "/api/comptes/moi/mot-de-passe";
    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String NOUVEAU = "nouveau-mot-de-passe-1";
    static final String SECRET = "secret-de-test-123";
    static final String TROP_COURT = "court-secre";
    static final String FAUX = "mauvais-mot-de-passe-1";
    static final Instant T = Instant.parse("2026-09-15T10:00:00Z");
    static final String INSERTION = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le) "
            + "values (?, ?, ?, ?, ?, ?)";

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
        HorlogeMutable horlogeChangementMotDePasse() {
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
    org.springframework.security.crypto.password.PasswordEncoder encodeur;

    @Autowired
    InitialiserAdminMaster initialiserAdminMaster;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    @Autowired
    HorlogeMutable horloge;

    JdbcTemplate jdbc;
    ApiHttp api;
    final JsonMapper json = JsonMapper.builder().build();
    ListAppender<ILoggingEvent> journal;
    Level niveauAvant;

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
        horloge.fixer(T);
        api = new ApiHttp(port);
        journal = new ListAppender<>();
        journal.start();
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        niveauAvant = racine.getLevel();
        racine.addAppender(journal);
        // Racine en DEBUG ; le code de l'application en TRACE. Le TRACE de Tomcat journalise les octets bruts des
        // requêtes (dont le corps de la connexion), ce qui n'est pas le comportement de l'application testée.
        racine.setLevel(Level.DEBUG);
        ((Logger) LoggerFactory.getLogger("fr.backyard")).setLevel(Level.TRACE);
    }

    @AfterEach
    void nettoyer() {
        Logger racine = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        racine.detachAppender(journal);
        racine.setLevel(niveauAvant == null ? Level.INFO : niveauAvant);
        ((Logger) LoggerFactory.getLogger("fr.backyard")).setLevel(null);
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : les 4 rôles changent leur mot de passe (204), empreinte Argon2id modifiée, ancien refusé, nouveau accepté, id/pseudo du corps ignorés")
    void ca9_changement_par_les_quatre_roles() throws Exception {
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        inserer("Nadia", "ADMIN", MOT_DE_PASSE_ADMIN);
        Session patron = ouvrir("Patron", MOT_DE_PASSE_PATRON);
        HttpResponse<String> creation = api.requete("POST", "/api/administration/benevoles", patron.entetes(),
                "application/json", corps("Léo", MOT_DE_PASSE_BENEVOLE));
        assertThat(creation.statusCode()).isEqualTo(201);

        Map<String, String> motsDePasse = new LinkedHashMap<>();
        motsDePasse.put("Alice", MOT_DE_PASSE);
        motsDePasse.put("Léo", MOT_DE_PASSE_BENEVOLE);
        motsDePasse.put("Nadia", MOT_DE_PASSE_ADMIN);
        motsDePasse.put("Patron", MOT_DE_PASSE_PATRON);
        Map<String, String> roles = Map.of("Alice", "COUREUR", "Léo", "BENEVOLE", "Nadia", "ADMIN",
                "Patron", "ADMIN_MASTER");

        for (Map.Entry<String, String> compte : motsDePasse.entrySet()) {
            String pseudo = compte.getKey();
            String ancien = compte.getValue();
            Map<String, Object> avant = ligne(pseudo);
            Map<String, String> empreintesAvant = empreintes();
            String autrePseudo = pseudo.equals("Alice") ? "Nadia" : "Alice";
            Session session = ouvrir(pseudo, ancien);

            HttpResponse<String> reponse = changer(session, ancien, NOUVEAU,
                    ", \"id\":\"" + avant.get("id") + "x\", \"pseudo\":\"" + autrePseudo + "\"");
            String ctx = "rôle " + roles.get(pseudo);

            assertThat(reponse.statusCode()).as(ctx).isEqualTo(204);
            assertThat(reponse.body()).as(ctx).isEmpty();
            Map<String, Object> apres = ligne(pseudo);
            assertThat((String) apres.get("empreinte_mot_de_passe")).as(ctx).startsWith("$argon2id$")
                    .isNotEqualTo(NOUVEAU).isNotEqualTo(avant.get("empreinte_mot_de_passe"));
            assertThat(apres.get("pseudo")).isEqualTo(avant.get("pseudo"));
            assertThat(apres.get("role")).isEqualTo(roles.get(pseudo));
            assertThat(apres.get("cree_le")).isEqualTo(avant.get("cree_le"));
            assertThat(apres.get("id")).isEqualTo(avant.get("id"));
            Map<String, String> empreintesApres = empreintes();
            empreintesAvant.forEach((autre, empreinte) -> {
                if (!autre.equals(pseudo)) {
                    assertThat(empreintesApres.get(autre)).as(ctx + " : " + autre + " inchangé").isEqualTo(empreinte);
                }
            });
            assertThat(compter("true")).isEqualTo(4);

            HttpResponse<String> ancienRefuse = connecter(pseudo, ancien);
            assertThat(ancienRefuse.statusCode()).as(ctx).isEqualTo(401);
            assertThat(code(ancienRefuse)).isEqualTo("IDENTIFIANTS_INVALIDES");
            HttpResponse<String> nouveauAccepte = connecter(pseudo, NOUVEAU);
            assertThat(nouveauAccepte.statusCode()).as(ctx).isEqualTo(200);
            assertThat(json.readTree(nouveauAccepte.body()).get("role").asString()).isEqualTo(roles.get(pseudo));
        }
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : anonyme 401 (même avec {}), CSRF absent ou différent 403 prioritaire, corps {} authentifié 400, autres chemins 404")
    void ca10_securite_et_priorites() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        UUID idAlice = idDe("Alice");
        String valide = corps(MOT_DE_PASSE, NOUVEAU);
        Jeton jeton = api.jetonValide();
        Map<String, String> anonymeAvecJeton = Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie(),
                "X-XSRF-TOKEN", jeton.entete());

        assertErreur(api.requete("PUT", CHEMIN, anonymeAvecJeton, "application/json", valide), 401,
                "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertErreur(api.requete("PUT", CHEMIN, anonymeAvecJeton, "application/json", "{}"), 401,
                "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");

        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        List<Map<String, String>> sansBonJeton = List.of(
                Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie()),
                Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie(), "X-XSRF-TOKEN", "autre-valeur"),
                Map.of());
        for (Map<String, String> entetes : sansBonJeton) {
            assertErreur(api.requete("PUT", CHEMIN, entetes, "application/json", valide), 403, "CSRF_INVALIDE",
                    "Accès refusé", "Jeton CSRF absent ou invalide.");
            Map<String, String> avecSession = new LinkedHashMap<>(entetes);
            avecSession.merge("Cookie", "JSESSIONID=" + alice.id(), (a, b) -> a + "; " + b);
            if (!avecSession.containsKey("Cookie")) {
                avecSession.put("Cookie", "JSESSIONID=" + alice.id());
            }
            assertErreur(api.requete("PUT", CHEMIN, avecSession, "application/json", valide), 403, "CSRF_INVALIDE",
                    "Accès refusé", "Jeton CSRF absent ou invalide.");
        }
        assertErreur(api.requete("PUT", CHEMIN, Map.of(), "application/json", "{}"), 403, "CSRF_INVALIDE",
                "Accès refusé", "Jeton CSRF absent ou invalide.");

        HttpResponse<String> vide = changerBrut(alice, "{}");
        assertThat(vide.statusCode()).isEqualTo(400);
        assertThat(code(vide)).isEqualTo("VALIDATION_ECHOUEE");

        for (String autre : List.of("/api/comptes/" + idAlice + "/mot-de-passe", CHEMIN + "/", CHEMIN + "/autre",
                "/api/comptes/moi/mot-de-passes")) {
            HttpResponse<String> reponse = api.requete("PUT", autre, alice.entetes(), "application/json", valide);
            assertErreur(reponse, 404, "RESSOURCE_INTROUVABLE", "Introuvable", "La ressource demandée est introuvable.");
        }
        assertThat(empreinteDe("Alice")).isNotNull();
        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : validation 400 groupée, bornes 11/12/128/129, identique, corps illisible, 415, aucune valeur saisie renvoyée")
    void ca11_validation_et_formats() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        String empreinteInitiale = empreinteDe("Alice");

        HttpResponse<String> vide = changerBrut(alice, "{}");
        JsonNode erreurs = verifierValidation(vide);
        assertThat(erreurs).hasSize(2);
        assertThat(erreur(erreurs, "motDePasseActuel").get("code").asString()).isEqualTo("MOT_DE_PASSE_ACTUEL_REQUIS");
        assertThat(erreur(erreurs, "motDePasseActuel").get("message").asString())
                .isEqualTo("Le mot de passe actuel est obligatoire.");
        assertThat(erreur(erreurs, "nouveauMotDePasse").get("code").asString()).isEqualTo("MOT_DE_PASSE_REQUIS");

        for (String corps : List.of("{\"motDePasseActuel\":null,\"nouveauMotDePasse\":null}",
                "{\"motDePasseActuel\":\"\",\"nouveauMotDePasse\":\"\"}")) {
            JsonNode e = verifierValidation(changerBrut(alice, corps));
            assertThat(erreur(e, "motDePasseActuel").get("code").asString()).isEqualTo("MOT_DE_PASSE_ACTUEL_REQUIS");
            assertThat(erreur(e, "nouveauMotDePasse").get("code").asString()).isEqualTo("MOT_DE_PASSE_REQUIS");
        }

        HttpResponse<String> court = changer(alice, MOT_DE_PASSE, TROP_COURT);
        JsonNode eCourt = verifierValidation(court);
        assertThat(eCourt).hasSize(1);
        assertThat(erreur(eCourt, "nouveauMotDePasse").get("code").asString()).isEqualTo("MOT_DE_PASSE_TROP_COURT");
        assertThat(court.body()).doesNotContain(TROP_COURT).doesNotContain(MOT_DE_PASSE);

        String troisCentTrop = "n".repeat(129);
        HttpResponse<String> long129 = changer(alice, MOT_DE_PASSE, troisCentTrop);
        assertThat(erreur(verifierValidation(long129), "nouveauMotDePasse").get("code").asString())
                .isEqualTo("MOT_DE_PASSE_TROP_LONG");
        assertThat(long129.body()).doesNotContain(troisCentTrop);

        // Ces refus de validation n'ont rien modifié ni compté.
        assertThat(empreinteDe("Alice")).isEqualTo(empreinteInitiale);

        HttpResponse<String> identique = changer(alice, MOT_DE_PASSE, MOT_DE_PASSE);
        assertErreur(identique, 400, "NOUVEAU_MOT_DE_PASSE_IDENTIQUE", "Requête invalide",
                "Le nouveau mot de passe doit être différent de l'actuel.");
        assertThat(identique.body()).doesNotContain(MOT_DE_PASSE);
        assertThat(empreinteDe("Alice")).isEqualTo(empreinteInitiale);

        HttpResponse<String> illisible = changerBrut(alice, "ceci n'est pas du json " + SECRET);
        assertErreur(illisible, 400, "CORPS_ILLISIBLE", "Requête invalide", "Le corps de la requête est illisible.");
        assertThat(illisible.body()).doesNotContain(SECRET);
        assertErreur(changerBrut(alice, ""), 400, "CORPS_ILLISIBLE", "Requête invalide",
                "Le corps de la requête est illisible.");
        assertErreur(changerBrut(alice, "{\"motDePasseActuel\":12,\"nouveauMotDePasse\":[\"x\"]}"), 400,
                "CORPS_ILLISIBLE", "Requête invalide", "Le corps de la requête est illisible.");
        HttpResponse<String> texte = api.requete("PUT", CHEMIN, alice.entetes(), "text/plain",
                corps(MOT_DE_PASSE, NOUVEAU));
        assertThat(texte.statusCode()).isEqualTo(415);
        assertThat(texte.body()).doesNotContain(NOUVEAU);
        assertThat(empreinteDe("Alice")).isEqualTo(empreinteInitiale);

        // Bornes acceptées (ancien juste, comptes distincts) : 12 caractères, 128 caractères, 12 espaces.
        for (String nouveau : List.of("a".repeat(12), "b".repeat(128), " ".repeat(12))) {
            String pseudo = "Borne" + nouveau.length() + (nouveau.isBlank() ? "e" : "");
            inserer(pseudo, "COUREUR", MOT_DE_PASSE);
            Session session = ouvrir(pseudo, MOT_DE_PASSE);
            HttpResponse<String> reponse = changer(session, MOT_DE_PASSE, nouveau);
            assertThat(reponse.statusCode()).as(pseudo).isEqualTo(204);
            assertThat(connecter(pseudo, nouveau).statusCode()).as(pseudo + " nouveau").isEqualTo(200);
        }
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : ancien faux, de 129 caractères ou d'espaces : même 400 MOT_DE_PASSE_ACTUEL_INCORRECT, empreinte inchangée, session valide")
    void ca12_ancien_incorrect_indiscernable() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        String empreinteInitiale = empreinteDe("Alice");

        List<HttpResponse<String>> reponses = new ArrayList<>();
        for (String ancien : List.of(FAUX, "x".repeat(129), " ".repeat(12))) {
            HttpResponse<String> reponse = changer(alice, ancien, NOUVEAU);
            assertErreur(reponse, 400, "MOT_DE_PASSE_ACTUEL_INCORRECT", "Mot de passe incorrect",
                    "Le mot de passe actuel est incorrect.");
            assertThat(json.readTree(reponse.body()).has("erreurs")).isFalse();
            assertThat(reponse.body()).doesNotContain(ancien.strip().isEmpty() ? "   " : ancien)
                    .doesNotContain(NOUVEAU);
            reponses.add(reponse);
        }
        JsonNode premier = json.readTree(reponses.get(0).body());
        for (HttpResponse<String> reponse : reponses) {
            assertThat(json.readTree(reponse.body())).isEqualTo(premier);
        }
        assertThat(empreinteDe("Alice")).isEqualTo(empreinteInitiale);
        assertThat(get(alice, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : 5 anciens faux (400) puis 429 même avec le bon ancien, Retry-After cohérent, connexion aussi bloquée, session conservée")
    void ca13_blocage_apres_cinq_echecs() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        inserer("Léo", "BENEVOLE", MOT_DE_PASSE);
        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        String empreinteInitiale = empreinteDe("Alice");

        for (int i = 0; i < 5; i++) {
            assertThat(changer(alice, FAUX, NOUVEAU).statusCode()).as("échec " + i).isEqualTo(400);
        }
        HttpResponse<String> bloquee = changer(alice, MOT_DE_PASSE, NOUVEAU);
        assertErreur(bloquee, 429, "TENTATIVES_EXCESSIVES", "Trop de tentatives",
                "Trop de tentatives de connexion. Réessayez plus tard.");
        int secondes = json.readTree(bloquee.body()).get("reessayerDansSecondes").asInt();
        assertThat(secondes).isBetween(1, 900);
        assertThat(bloquee.headers().firstValue("retry-after")).hasValue(String.valueOf(secondes));
        assertThat(bloquee.headers().allValues("set-cookie")).isEmpty();
        assertThat(empreinteDe("Alice")).isEqualTo(empreinteInitiale);

        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        assertThat(get(alice, "/api/comptes/moi").statusCode()).isEqualTo(200);
        // Un autre compte n'est pas bloqué.
        assertThat(connecter("Léo", MOT_DE_PASSE).statusCode()).isEqualTo(200);

        // Fin du blocage : traitement normal.
        horloge.fixer(T.plusSeconds(900));
        assertThat(changer(alice, MOT_DE_PASSE, NOUVEAU).statusCode()).isEqualTo(204);
        assertThat(connecter("Alice", NOUVEAU).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA13 : compteur commun : 3 échecs de connexion puis 2 échecs de changement bloquent au 6e appel")
    void ca13_compteur_commun_connexion_et_changement() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session alice = ouvrir("Alice", MOT_DE_PASSE);

        for (int i = 0; i < 3; i++) {
            assertThat(connecter("Alice", FAUX).statusCode()).as("connexion " + i).isEqualTo(401);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(changer(alice, FAUX, NOUVEAU).statusCode()).as("changement " + i).isEqualTo(400);
        }
        assertThat(changer(alice, MOT_DE_PASSE, NOUVEAU).statusCode()).isEqualTo(429);
        assertThat(connecter("Alice", MOT_DE_PASSE).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("CA13 : blocage préalable par un tiers (échecs de connexion) : la victime connectée reçoit 429 sur le changement")
    void ca13_blocage_prealable_par_connexion() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        for (int i = 0; i < 5; i++) {
            assertThat(connecter("Alice", FAUX).statusCode()).isEqualTo(401);
        }
        assertThat(changer(alice, MOT_DE_PASSE, NOUVEAU).statusCode()).isEqualTo(429);
        assertThat(get(alice, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA13 : un changement réussi remet le compteur à zéro ; 400 de validation, identique et ancien hors bornes ne comptent pas")
    void ca13_reinitialisation_et_refus_non_comptes() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session alice = ouvrir("Alice", MOT_DE_PASSE);

        for (int i = 0; i < 4; i++) {
            assertThat(changer(alice, FAUX, NOUVEAU).statusCode()).isEqualTo(400);
        }
        assertThat(changer(alice, MOT_DE_PASSE, NOUVEAU).statusCode()).isEqualTo(204);

        // Compteur à 0 : un seul échec ne bloque pas, le changement suivant aboutit.
        Session apres = ouvrir("Alice", NOUVEAU);
        assertThat(changer(apres, FAUX, SECRET).statusCode()).isEqualTo(400);
        assertThat(changer(apres, NOUVEAU, SECRET).statusCode()).isEqualTo(204);

        // Refus non comptés : 20 refus ne bloquent pas.
        Session finale = ouvrir("Alice", SECRET);
        for (int i = 0; i < 6; i++) {
            assertThat(changerBrut(finale, "{}").statusCode()).isEqualTo(400);
            assertThat(changer(finale, SECRET, SECRET).statusCode()).isEqualTo(400);
            assertThat(changer(finale, "x".repeat(129), NOUVEAU).statusCode()).isEqualTo(400);
            assertThat(changer(finale, SECRET, TROP_COURT).statusCode()).isEqualTo(400);
        }
        assertThat(changer(finale, SECRET, NOUVEAU).statusCode()).isEqualTo(204);
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : A change, A garde une session valide à identifiant renouvelé, B (même compte) est invalidée, un autre compte intact")
    void ca14_invalidation_des_autres_sessions() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        inserer("Léo", "BENEVOLE", MOT_DE_PASSE);
        Session a = ouvrir("Alice", MOT_DE_PASSE);
        Session b = ouvrir("Alice", MOT_DE_PASSE);
        Session leo = ouvrir("Léo", MOT_DE_PASSE);
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(get(b, "/api/comptes/moi").statusCode()).isEqualTo(200);

        HttpResponse<String> reponse = changer(a, MOT_DE_PASSE, NOUVEAU);

        assertThat(reponse.statusCode()).isEqualTo(204);
        String nouvelIdentifiant = ApiHttp.valeurCookie(reponse, "JSESSIONID");
        assertThat(nouvelIdentifiant).isNotNull().isNotEqualTo(a.id());
        assertThat(get(new Session(nouvelIdentifiant, a.xsrf()), "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(get(a, "/api/comptes/moi").statusCode()).isEqualTo(401);
        HttpResponse<String> pourB = get(b, "/api/comptes/moi");
        assertErreur(pourB, 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertThat(get(leo, "/api/comptes/moi").statusCode()).isEqualTo(200);

        // Nouveau jeton CSRF obtenu avec la nouvelle session : un second changement aboutit.
        HttpResponse<String> csrf = api.requete("GET", "/api/csrf", Map.of("Cookie", "JSESSIONID=" + nouvelIdentifiant),
                null, null);
        assertThat(csrf.statusCode()).isIn(200, 204);
        String jeton = ApiHttp.valeurCookieXsrf(csrf);
        Session apres = new Session(nouvelIdentifiant, jeton);
        HttpResponse<String> second = changer(apres, NOUVEAU, SECRET);
        assertThat(second.statusCode()).isEqualTo(204);
        assertThat(connecter("Alice", SECRET).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA14 : un échec de changement (ancien faux) n'invalide aucune session")
    void ca14_un_echec_n_invalide_rien() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session a = ouvrir("Alice", MOT_DE_PASSE);
        Session b = ouvrir("Alice", MOT_DE_PASSE);

        assertThat(changer(a, FAUX, NOUVEAU).statusCode()).isEqualTo(400);
        assertThat(changer(a, MOT_DE_PASSE, MOT_DE_PASSE).statusCode()).isEqualTo(400);
        assertThat(changerBrut(a, "{}").statusCode()).isEqualTo(400);

        assertThat(get(a, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(get(b, "/api/comptes/moi").statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : empreinte mise à null en base pendant la session : 401, session invalidée, aucune modification")
    void ca15_compte_sans_empreinte() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        jdbc.update("update compte set empreinte_mot_de_passe = null where pseudo = 'Alice'");

        HttpResponse<String> reponse = changer(alice, MOT_DE_PASSE, NOUVEAU);

        assertErreur(reponse, 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertThat(get(alice, "/api/comptes/moi").statusCode()).isEqualTo(401);
        assertThat(empreinteDe("Alice")).isNull();
        assertThat(compter("pseudo = 'Alice'")).isEqualTo(1);
    }

    @Test
    @DisplayName("CA15 : compte supprimé pendant la session : 401, session invalidée")
    void ca15_compte_supprime() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session alice = ouvrir("Alice", MOT_DE_PASSE);
        jdbc.update("delete from compte where pseudo = 'Alice'");

        HttpResponse<String> reponse = changer(alice, MOT_DE_PASSE, NOUVEAU);

        assertErreur(reponse, 401, "NON_AUTHENTIFIE", "Authentification requise", "Vous devez être connecté.");
        assertThat(get(alice, "/api/comptes/moi").statusCode()).isEqualTo(401);
        assertThat(compter("true")).isZero();
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : journaux (tous niveaux) sans mot de passe, INFO+ sans pseudo, lignes de succès, d'échec et de blocage présentes")
    void ca16_journaux() throws Exception {
        inserer("Alice", "COUREUR", SECRET);
        UUID id = idDe("Alice");
        Session alice = ouvrir("Alice", SECRET);

        assertThat(changer(alice, SECRET, NOUVEAU).statusCode()).isEqualTo(204);
        Session apres = ouvrir("Alice", NOUVEAU);
        assertThat(changer(apres, TROP_COURT, TROP_COURT).statusCode()).isEqualTo(400);
        for (int i = 0; i < 5; i++) {
            assertThat(changer(apres, SECRET, TROP_COURT.repeat(2)).statusCode()).as("échec " + i).isEqualTo(400);
        }
        assertThat(changer(apres, NOUVEAU, SECRET).statusCode()).isEqualTo(429);

        List<ILoggingEvent> evenements = new ArrayList<>(journal.list);
        assertThat(evenements).isNotEmpty();
        assertThat(lignes(Level.TRACE)).noneMatch(l -> l.contains(SECRET) || l.contains(TROP_COURT)
                || l.contains(NOUVEAU) || l.contains(MOT_DE_PASSE));
        assertThat(lignes(Level.INFO)).noneMatch(l -> l.contains("Alice"));
        assertThat(evenements).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("Mot de passe modifié").contains(id.toString());
        });
        assertThat(evenements).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("Échec de changement de mot de passe").contains(id.toString());
        });
        assertThat(evenements).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("Connexion bloquée temporairement");
        });
        assertThat(evenements).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("Connexion refusée : blocage en cours");
        });
    }

    @Test
    @DisplayName("CA16 : le toString() du DTO masque les deux mots de passe")
    void ca16_to_string_du_dto() {
        ChangerMotDePasseRequete requete = new ChangerMotDePasseRequete(SECRET, NOUVEAU);

        assertThat(requete.toString())
                .isEqualTo("ChangerMotDePasseRequete[motDePasseActuel=masqué, nouveauMotDePasse=masqué]")
                .doesNotContain(SECRET).doesNotContain(NOUVEAU);
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : deux connexions simultanées d'un même compte restent possibles ; aucun changeset ajouté")
    void ca17_non_regression() throws Exception {
        inserer("Alice", "COUREUR", MOT_DE_PASSE);
        Session a = ouvrir("Alice", MOT_DE_PASSE);
        Session b = ouvrir("Alice", MOT_DE_PASSE);

        assertThat(get(a, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(get(b, "/api/comptes/moi").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("select id from databasechangelog order by orderexecuted", String.class))
                .containsExactly("0002-compte", "0003-admin-master-unique", "0004-course", "0005-logo-course");
    }

    // ---------------------------------------------------------------- utilitaires

    private String jetonDeConnexion;

    private void inserer(String pseudo, String role, String motDePasse) {
        jdbc.update(INSERTION, UUID.randomUUID(), pseudo, pseudo.toLowerCase(Locale.ROOT),
                encodeur.encode(motDePasse), role, Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
    }

    private int compter(String condition) {
        return jdbc.queryForObject("select count(*) from compte where " + condition, Integer.class);
    }

    private Map<String, Object> ligne(String pseudo) {
        return jdbc.queryForMap("select * from compte where pseudo = ?", pseudo);
    }

    private Map<String, String> empreintes() {
        Map<String, String> resultat = new LinkedHashMap<>();
        jdbc.query("select pseudo, empreinte_mot_de_passe from compte",
                rs -> {
                    resultat.put(rs.getString(1), rs.getString(2));
                });
        return resultat;
    }

    private String empreinteDe(String pseudo) {
        return jdbc.queryForObject("select empreinte_mot_de_passe from compte where pseudo = ?", String.class, pseudo);
    }

    private UUID idDe(String pseudo) {
        return jdbc.queryForObject("select id from compte where pseudo = ?", UUID.class, pseudo);
    }

    private static String corps(String pseudoOuActuel, String motDePasseOuNouveau) {
        return "{\"pseudo\":\"" + pseudoOuActuel + "\",\"motDePasse\":\"" + motDePasseOuNouveau + "\"}";
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

    private HttpResponse<String> changer(Session session, String actuel, String nouveau) throws Exception {
        return changer(session, actuel, nouveau, "");
    }

    private HttpResponse<String> changer(Session session, String actuel, String nouveau, String supplement)
            throws Exception {
        return changerBrut(session, "{\"motDePasseActuel\":\"" + actuel + "\",\"nouveauMotDePasse\":\"" + nouveau
                + "\"" + supplement + "}");
    }

    private HttpResponse<String> changerBrut(Session session, String corps) throws Exception {
        return api.requete("PUT", CHEMIN, session.entetes(), "application/json", corps);
    }

    private HttpResponse<String> get(Session session, String chemin) throws Exception {
        return api.requete("GET", chemin, Map.of("Cookie", "JSESSIONID=" + session.id()), null, null);
    }

    private String code(HttpResponse<String> reponse) throws Exception {
        return json.readTree(reponse.body()).get("code").asString();
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
}
