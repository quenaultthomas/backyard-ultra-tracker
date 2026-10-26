package fr.backyard.tracker.comptes.integration;

import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.DETAIL_BLOCAGE;
import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.MOT_DE_PASSE;
import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.MOT_DE_PASSE_FAUX;
import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.corps;
import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.creerCompte;
import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.lireCorps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.domaine.AdresseSuivie;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.RegistreEchecsParAdresse;
import fr.backyard.tracker.comptes.infrastructure.EncodeurMotDePasseArgon2;
import fr.backyard.tracker.comptes.infrastructure.RegistreEchecsParAdresseEnMemoire;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat de l'incrément 8.4 (CA12 à CA19, CA21, CA22) : plafond d'échecs de connexion par adresse cliente. Le proxy de
 * confiance est le pair de test ({@code localhost}) : l'adresse cliente est celle de {@code X-Forwarded-For}. Plafond
 * (20, 60), horloge mutable, plafond de hachage par adresse à 1 avec attente longue (les rafales sont sérialisées, jamais
 * de 503).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.connexion.echecs-par-ip-max=20",
        "backyard.connexion.echecs-par-ip-fenetre-secondes=60",
        "backyard.limite-debit-public.proxy-de-confiance=localhost",
        "backyard.hachage.concurrence-max=32",
        "backyard.hachage.concurrence-max-par-ip=1",
        "backyard.hachage.attente-max-millisecondes=120000",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
class ConnexionPlafondAdresseIntegrationTest {

    static final Instant T = Instant.parse("2026-10-26T10:00:00Z");
    static final String A1 = "203.0.113.1";
    static final String A2 = "203.0.113.2";
    static final String A5 = "203.0.113.5";
    static final String A9 = "203.0.113.9";

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
    RegistreEchecsParAdresseEnMemoire registreAdresses;

    @Autowired
    RegistreTentativesConnexionEnMemoire registrePseudos;

    @Autowired
    HorlogeMutable horloge;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    ApplicationContext contexte;

    @MockitoSpyBean
    PasswordEncoder argon2;

    JdbcTemplate jdbc;
    ApiHttp api;
    ClientConnexionParAdresse client;
    JournalApplicatif journal;
    static String empreinteDeReference;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registreAdresses.vider();
        registrePseudos.vider();
        horloge.fixer(T);
        api = new ApiHttp(port);
        client = new ClientConnexionParAdresse(api);
        if (empreinteDeReference == null) {
            empreinteDeReference = encodeur.encoder(new fr.backyard.tracker.comptes.domaine.MotDePasse(MOT_DE_PASSE));
        }
        journal = JournalApplicatif.demarrer();
    }

    @AfterEach
    void nettoyer() {
        reset(argon2);
        journal.fermer();
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : 20 échecs sur 20 pseudos inexistants depuis une adresse, la 21e tentative reçoit 429 TENTATIVES_EXCESSIVES avec Retry-After 60 et aucune session ; une autre adresse n'est pas touchée")
    void ca12_vingt_echecs_puis_429_identique_au_blocage() throws Exception {
        compte("Karim", "COUREUR");

        client.echouer(A1, "Spray", 20);

        HttpResponse<String> refus = client.connecter(A1, "Karim", MOT_DE_PASSE);
        assertThat(refus.statusCode()).isEqualTo(429);
        JsonNode corps = lireCorps(refus);
        assertThat(corps.get("code").asString()).isEqualTo("TENTATIVES_EXCESSIVES");
        assertThat(corps.get("detail").asString()).isEqualTo(DETAIL_BLOCAGE);
        assertThat(corps.get("title").asString()).isEqualTo("Trop de tentatives");
        assertThat(corps.get("status").asInt()).isEqualTo(429);
        assertThat(corps.get("reessayerDansSecondes").asInt()).isEqualTo(60);
        assertThat(refus.headers().firstValue("retry-after")).hasValue("60");
        assertThat(refus.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));

        HttpResponse<String> autreAdresse = client.connecter(A2, "Karim", MOT_DE_PASSE);
        assertThat(autreAdresse.statusCode()).isEqualTo(200);
        assertThat(lireCorps(autreAdresse).get("pseudo").asString()).isEqualTo("Karim");

        // Aucun des 20 pseudos n'a plus d'un échec : le pseudo n'est pas bloqué (adresse libre, 401 et non 429).
        assertThat(client.connecter(A5, "Spray0", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : 20 échecs à T : 429 jusqu'à T+59 s (Retry-After 1) puis admis à T+60 s ; un refus ne prolonge rien")
    void ca13_fenetre_glissante_instant_exact() throws Exception {
        compte("Karim", "COUREUR");
        client.echouer(A1, "Spray", 20);

        horloge.fixer(T.plusSeconds(10));
        for (int i = 0; i < 100; i++) {
            assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        }

        horloge.fixer(T.plusSeconds(59));
        HttpResponse<String> presqueLibre = client.connecter(A1, "Karim", MOT_DE_PASSE);
        assertThat(presqueLibre.statusCode()).isEqualTo(429);
        assertThat(presqueLibre.headers().firstValue("retry-after")).hasValue("1");
        assertThat(lireCorps(presqueLibre).get("reessayerDansSecondes").asInt()).isEqualTo(1);

        horloge.fixer(T.plusSeconds(60));
        assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA13 : 10 échecs à T et 10 à T+30 s : 429 à T+30 s (Retry-After 30) et à T+59 s (1), tentative traitée à T+60 s")
    void ca13_echecs_repartis_dans_la_fenetre() throws Exception {
        client.echouer(A1, "PremiereSalve", 10);
        horloge.fixer(T.plusSeconds(30));
        client.echouer(A1, "SecondeSalve", 10);

        HttpResponse<String> plafonnee = client.connecter(A1, "Quelquun", MOT_DE_PASSE_FAUX);
        assertThat(plafonnee.statusCode()).isEqualTo(429);
        assertThat(plafonnee.headers().firstValue("retry-after")).hasValue("30");

        horloge.fixer(T.plusSeconds(59));
        HttpResponse<String> presqueLibre = client.connecter(A1, "Quelquun", MOT_DE_PASSE_FAUX);
        assertThat(presqueLibre.statusCode()).isEqualTo(429);
        assertThat(presqueLibre.headers().firstValue("retry-after")).hasValue("1");

        horloge.fixer(T.plusSeconds(60));
        assertThat(client.connecter(A1, "Quelquun", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : un succès n'efface pas le compteur d'adresse (19 échecs, succès, déconnexion, 1 échec, puis 429)")
    void ca14_un_succes_n_efface_pas_le_compteur_d_adresse() throws Exception {
        compte("Karim", "COUREUR");
        client.echouer(A1, "Spray", 19);

        HttpResponse<String> succes = client.connecter(A1, "Karim", MOT_DE_PASSE);
        assertThat(succes.statusCode()).isEqualTo(200);
        String session = ApiHttp.valeurCookie(succes, "JSESSIONID");
        Jeton jeton = client.jeton();
        assertThat(api.requete("POST", "/api/deconnexion", Map.of(
                "Cookie", "JSESSIONID=" + session + "; XSRF-TOKEN=" + jeton.cookie(),
                "X-XSRF-TOKEN", jeton.entete()), null, null).statusCode()).isEqualTo(204);

        assertThat(client.connecter(A1, "Dernier", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);

        assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("CA14 : le compteur par pseudo reste effacé par un succès (4 échecs, succès, 4 échecs ne bloquent pas Karim)")
    void ca14_le_compteur_par_pseudo_reste_efface_par_un_succes() throws Exception {
        compte("Karim", "COUREUR");
        for (int serie = 0; serie < 2; serie++) {
            for (int i = 0; i < 4; i++) {
                assertThat(client.connecter(A5, "Karim", MOT_DE_PASSE_FAUX).statusCode())
                        .as("série " + serie + " essai " + i).isEqualTo(401);
            }
            assertThat(client.connecter(A5, "Karim", MOT_DE_PASSE).statusCode()).as("série " + serie).isEqualTo(200);
        }
    }

    // ---------------------------------------------------------------- CA15

    @Test
    @DisplayName("CA15 : 400, 403, 401 hors bornes, 409 et 429 de pseudo bloqué ne comptent pas ; pseudo inconnu, mauvais mot de passe, compte désactivé et compte anonymisé comptent chacun pour un échec")
    void ca15_echecs_comptes_et_non_comptes() throws Exception {
        compte("Karim", "COUREUR");
        compte("Leo", "BENEVOLE");
        creerCompte(jdbc, "Desactive", "BENEVOLE", empreinteDeReference, false, T);
        creerCompte(jdbc, "Anonyme", "COUREUR", null, true, T);
        // Pseudo bloqué depuis une autre adresse : ses 5 échecs ne comptent pas pour A1.
        for (int i = 0; i < 5; i++) {
            assertThat(client.connecter(A9, "Leo", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
        }
        // Session déjà ouverte (Karim) pour provoquer des 409.
        HttpResponse<String> ouverture = client.connecter(A9, "Karim", MOT_DE_PASSE);
        assertThat(ouverture.statusCode()).isEqualTo(200);
        Map<String, String> sessionOuverte = new HashMap<>();
        sessionOuverte.put("Cookie", "JSESSIONID=" + ApiHttp.valeurCookie(ouverture, "JSESSIONID")
                + "; XSRF-TOKEN=" + client.jeton().cookie());
        sessionOuverte.put("X-XSRF-TOKEN", client.jeton().entete());
        sessionOuverte.put("X-Forwarded-For", A1);

        for (int i = 0; i < 30; i++) {
            assertThat(client.envoyer("/api/connexion", A1, "{}", client.jeton()).statusCode()).as("400").isEqualTo(400);
            assertThat(client.envoyer("/api/connexion", A1, corps("Karim", MOT_DE_PASSE_FAUX), null).statusCode()).as("403").isEqualTo(403);
            assertThat(client.connecter(A1, "p".repeat(200), MOT_DE_PASSE_FAUX).statusCode()).as("hors bornes")
                    .isEqualTo(401);
            assertThat(api.requete("POST", "/api/connexion", sessionOuverte, "application/json",
                    corps("Karim", MOT_DE_PASSE_FAUX)).statusCode()).as("409").isEqualTo(409);
            assertThat(client.connecter(A1, "Leo", MOT_DE_PASSE).statusCode()).as("pseudo bloqué").isEqualTo(429);
        }
        assertThat(registreAdresses.constater(AdresseSuivie.depuis(A1), T).instantsRetenus())
                .as("aucun échec compté pour l'adresse").isEmpty();

        // Quatre échecs comptés, un chacun.
        assertThat(client.connecter(A1, "Fantome", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
        assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
        assertThat(client.connecter(A1, "Desactive", MOT_DE_PASSE).statusCode()).isEqualTo(401);
        assertThat(client.connecter(A1, "Anonyme", MOT_DE_PASSE).statusCode()).isEqualTo(401);
        assertThat(registreAdresses.constater(AdresseSuivie.depuis(A1), T).instantsRetenus()).hasSize(4);

        client.echouer(A1, "Autre", 15);
        assertThat(client.connecter(A1, "Dernier", MOT_DE_PASSE_FAUX).statusCode()).as("20e échec admis").isEqualTo(401);
        assertThat(client.connecter(A1, "Dernier", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- CA16

    @Test
    @DisplayName("CA16 : adresse plafonnée, les 429 sont identiques quel que soit le pseudo (existant, désactivé, anonymisé, inconnu), sans requête SQL ni hachage ; même contrat que le blocage par pseudo")
    void ca16_refus_indiscernable_sans_sql_ni_hachage() throws Exception {
        compte("Karim", "COUREUR");
        creerCompte(jdbc, "Desactive", "BENEVOLE", empreinteDeReference, false, T);
        creerCompte(jdbc, "Anonyme", "COUREUR", null, true, T);
        client.echouer(A1, "Spray", 20);
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        clearInvocations(argon2);
        long avant = statistiques.getPrepareStatementCount();
        List<HttpResponse<String>> refus = new ArrayList<>();
        refus.add(client.connecter(A1, "Karim", MOT_DE_PASSE));
        refus.add(client.connecter(A1, "Karim", MOT_DE_PASSE_FAUX));
        refus.add(client.connecter(A1, "Fantome", MOT_DE_PASSE));
        refus.add(client.connecter(A1, "Desactive", MOT_DE_PASSE));
        refus.add(client.connecter(A1, "Anonyme", MOT_DE_PASSE));
        long apres = statistiques.getPrepareStatementCount();

        assertThat(apres - avant).as("requêtes SQL pendant les refus").isZero();
        verify(argon2, never()).matches(any(), anyString());
        for (HttpResponse<String> reponse : refus) {
            assertThat(reponse.statusCode()).isEqualTo(429);
            assertThat(sansInstance(reponse)).isEqualTo(sansInstance(refus.get(0)));
            assertThat(entetesSansDate(reponse)).isEqualTo(entetesSansDate(refus.get(0)));
            assertThat(reponse.body()).doesNotContain("Karim", "Fantome", "Desactive", "Anonyme");
            assertThat(lireCorps(reponse).propertyNames()).doesNotContain("pseudo", "adresse", "cause");
        }

        // Blocage par pseudo seul (autre adresse) : même contrat, seule la durée diffère.
        for (int i = 0; i < 5; i++) {
            assertThat(client.connecter(A2, "Karim", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
        }
        HttpResponse<String> parPseudo = client.connecter(A2, "Karim", MOT_DE_PASSE);
        assertThat(parPseudo.statusCode()).isEqualTo(429);
        JsonNode corpsPseudo = lireCorps(parPseudo);
        JsonNode corpsAdresse = lireCorps(refus.get(0));
        for (String champ : List.of("type", "title", "status", "detail", "code")) {
            assertThat(corpsPseudo.get(champ)).as(champ).isEqualTo(corpsAdresse.get(champ));
        }
        assertThat(new TreeSet<>(corpsPseudo.propertyNames())).isEqualTo(new TreeSet<>(corpsAdresse.propertyNames()));
        assertThat(entetesSansDate(parPseudo).keySet()).isEqualTo(entetesSansDate(refus.get(0)).keySet());
        assertThat(parPseudo.headers().firstValue("retry-after")).hasValue("900");
        assertThat(corpsPseudo.get("reessayerDansSecondes").asInt()).isEqualTo(900);
    }

    // ---------------------------------------------------------------- CA17

    @Test
    @DisplayName("CA17 : blocage du pseudo et plafond d'adresse coexistent ; la durée annoncée est le maximum des deux")
    void ca17_coexistence_et_maximum_des_deux_durees() throws Exception {
        compte("Karim", "COUREUR");
        compte("Leo", "BENEVOLE");
        compte("Nadia", "ADMIN");

        for (int i = 0; i < 5; i++) {
            assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
        }
        assertRetryAfterEntre(client.connecter(A1, "Karim", MOT_DE_PASSE), 899, 900);
        assertThat(client.connecter(A1, "Leo", MOT_DE_PASSE).statusCode()).as("adresse à 5 échecs").isEqualTo(200);

        client.echouer(A1, "Spray", 15);

        assertRetryAfterEntre(client.connecter(A1, "Karim", MOT_DE_PASSE), 899, 900);
        assertRetryAfterEntre(client.connecter(A1, "Nadia", MOT_DE_PASSE), 1, 60);
        assertThat(client.connecter(A2, "Nadia", MOT_DE_PASSE).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : adresse IPv4 lue dans X-Forwarded-For du proxy de confiance ; les autres adresses ne sont pas touchées")
    void ca18_adresse_via_x_forwarded_for() throws Exception {
        compte("Karim", "COUREUR");
        client.echouer(A1, "Spray", 20);

        assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        assertThat(client.connecter(A2, "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA18 : IPv6, les adresses d'un même /64 partagent le compteur, un autre /64 est indépendant")
    void ca18_ipv6_prefixe_64() throws Exception {
        compte("Karim", "COUREUR");
        for (int i = 1; i <= 20; i++) {
            HttpResponse<String> reponse = client.connecter("2001:db8:0:1::" + Integer.toHexString(i), "Spray" + i,
                    MOT_DE_PASSE_FAUX);
            assertThat(reponse.statusCode()).as("échec " + i).isEqualTo(401);
        }

        assertThat(client.connecter("2001:db8:0:1:aaaa::1", "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        assertThat(client.connecter("2001:db8:0:2::1", "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- CA19

    @Test
    @DisplayName("CA19 : 60 échecs simultanés depuis une adresse : aucune erreur 500, au moins 20 réponses 401, puis 429 ; 20 instants retenus au plus, une seule adresse suivie")
    void ca19_echecs_concurrents() throws Exception {
        int nombre = 60;
        ExecutorService executeur = Executors.newFixedThreadPool(nombre);
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<Integer>> futurs = new ArrayList<>();
        try {
            for (int i = 0; i < nombre; i++) {
                String pseudo = "Rafale" + i;
                futurs.add(executeur.submit(() -> {
                    depart.await();
                    return client.connecter(A1, pseudo, MOT_DE_PASSE_FAUX).statusCode();
                }));
            }
            depart.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> futur : futurs) {
                codes.add(futur.get());
            }
            assertThat(codes).allMatch(code -> code == 401 || code == 429);
            assertThat(codes.stream().filter(code -> code == 401).count()).isGreaterThanOrEqualTo(20);
        } finally {
            executeur.shutdownNow();
        }

        HttpResponse<String> suivante = client.connecter(A1, "Suivant", MOT_DE_PASSE_FAUX);
        assertThat(suivante.statusCode()).isEqualTo(429);
        assertRetryAfterEntre(suivante, 1, 60);
        assertThat(registreAdresses.constater(AdresseSuivie.depuis(A1), T).instantsRetenus()).hasSize(20);
        assertThat(registreAdresses.taille()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- CA21

    @Test
    @DisplayName("CA21 : journal : une seule ligne WARN au 20e échec, 10 lignes DEBUG pour les refus, aucun pseudo, mot de passe, cookie ni adresse")
    void ca21_journal_du_plafond() throws Exception {
        journal.niveau(Logger.ROOT_LOGGER_NAME, Level.DEBUG).niveau("org.hibernate", Level.INFO);
        compte("Karim", "COUREUR");

        client.echouer(A1, "Spray", 20);
        for (int i = 0; i < 10; i++) {
            assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(429);
        }

        List<ILoggingEvent> evenements = journal.evenements();
        assertThat(evenements.stream().filter(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("Connexion : plafond d'échecs par adresse atteint"))).hasSize(1);
        assertThat(evenements.stream().filter(e -> e.getLevel() == Level.DEBUG
                && e.getFormattedMessage().contains("Connexion refusée : plafond d'échecs par adresse atteint")))
                .hasSize(10);
        assertThat(evenements.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)
                && e.getFormattedMessage().contains("plafond d'échecs par adresse")
                && e.getFormattedMessage().contains("refusée"))).isEmpty();
        List<String> lignes = new ArrayList<>();
        for (ILoggingEvent evenement : evenements) {
            lignes.add(evenement.getFormattedMessage());
            if (evenement.getThrowableProxy() != null) {
                lignes.add(ThrowableProxyUtil.asString(evenement.getThrowableProxy()));
            }
        }
        // Mot de passe, adresse et cookie : à tous les niveaux.
        assertThat(lignes).noneMatch(l -> l.contains(MOT_DE_PASSE) || l.contains(MOT_DE_PASSE_FAUX) || l.contains(A1)
                || l.contains(client.jeton().cookie()) || l.contains("JSESSIONID"));
        // Pseudo : tout le journal de l'application à tous les niveaux, le reste à partir d'INFO (le DEBUG de
        // Spring MVC journalise le DTO de requête, pseudo compris : hors périmètre de l'application, comme CA20 de 1.3).
        assertThat(evenements.stream().filter(e -> e.getLoggerName().startsWith(JournalApplicatif.PAQUET_APPLICATION)
                || e.getLevel().isGreaterOrEqual(Level.INFO)).map(ILoggingEvent::getFormattedMessage))
                .noneMatch(l -> l.contains("Spray") || l.contains("Karim"));
    }

    @Test
    @DisplayName("CA21 : les lignes du blocage par pseudo sont inchangées (WARN au déclenchement, INFO par refus)")
    void ca21_lignes_du_blocage_par_pseudo_inchangees() throws Exception {
        journal.niveau(Logger.ROOT_LOGGER_NAME, Level.DEBUG).niveau("org.hibernate", Level.INFO);

        for (int i = 0; i < 5; i++) {
            assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE_FAUX).statusCode()).isEqualTo(401);
        }
        assertThat(client.connecter(A1, "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(429);

        List<ILoggingEvent> evenements = journal.evenements();
        assertThat(evenements.stream().filter(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("Connexion bloquée temporairement"))).hasSize(1);
        assertThat(evenements.stream().filter(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("Connexion refusée : blocage en cours"))).hasSize(1);
        assertThat(evenements.stream().filter(e -> e.getFormattedMessage().contains("plafond d'échecs par adresse")))
                .isEmpty();
    }

    // ---------------------------------------------------------------- CA22

    @Test
    @DisplayName("CA22 : le changement de mot de passe et la création de compte ne comptent pas dans le plafond d'adresse ; un seul bean de plafond et un seul registre")
    void ca22_portee_limitee_a_la_connexion() throws Exception {
        compte("Nadia", "ADMIN");
        compte("Leo", "BENEVOLE");

        HttpResponse<String> ouverture = client.connecter(A1, "Nadia", MOT_DE_PASSE);
        assertThat(ouverture.statusCode()).isEqualTo(200);
        String session = ApiHttp.valeurCookie(ouverture, "JSESSIONID");
        Map<String, String> entetes = Map.of(
                "Cookie", "JSESSIONID=" + session + "; XSRF-TOKEN=" + client.jeton().cookie(),
                "X-XSRF-TOKEN", client.jeton().entete(), "X-Forwarded-For", A1);

        for (int i = 1; i <= 30; i++) {
            HttpResponse<String> reponse = api.requete("PUT", "/api/comptes/moi/mot-de-passe", entetes,
                    "application/json", "{\"motDePasseActuel\":\"" + MOT_DE_PASSE_FAUX
                            + "\",\"nouveauMotDePasse\":\"nouveau-mot-de-passe-1\"}");
            if (i <= 5) {
                assertThat(reponse.statusCode()).as("essai " + i).isEqualTo(400);
                assertThat(lireCorps(reponse).get("code").asString()).isEqualTo("MOT_DE_PASSE_ACTUEL_INCORRECT");
            } else {
                assertThat(reponse.statusCode()).as("essai " + i).isEqualTo(429);
            }
        }
        assertThat(registreAdresses.taille()).as("aucune adresse suivie").isZero();

        assertThat(api.requete("POST", "/api/deconnexion", entetes, null, null).statusCode()).isEqualTo(204);
        assertThat(client.connecter(A1, "Leo", MOT_DE_PASSE).statusCode()).isEqualTo(200);
        HttpResponse<String> creation = client.envoyer("/api/comptes", A1, corps("NouveauCoureur", MOT_DE_PASSE), client.jeton());
        assertThat(creation.statusCode()).as("POST /api/comptes").isEqualTo(201);

        assertThat(contexte.getBeansOfType(PlafondEchecsParAdresse.class)).hasSize(1);
        assertThat(contexte.getBeansOfType(RegistreEchecsParAdresse.class)).hasSize(1);
        assertThat(contexte.getBean(PlafondEchecsParAdresse.class)).isEqualTo(new PlafondEchecsParAdresse(20, 60));
    }

    // ---------------------------------------------------------------- utilitaires

    private void compte(String pseudo, String role) {
        creerCompte(jdbc, pseudo, role, empreinteDeReference, true, T);
    }

    private static void assertRetryAfterEntre(HttpResponse<String> reponse, int min, int max) {
        assertThat(reponse.statusCode()).isEqualTo(429);
        assertThat(reponse.headers().firstValue("retry-after")).hasValueSatisfying(
                valeur -> assertThat(Integer.parseInt(valeur)).isBetween(min, max));
        assertThat(lireCorps(reponse).get("reessayerDansSecondes").asInt()).isBetween(min, max);
    }

    private static JsonNode sansInstance(HttpResponse<String> reponse) {
        ObjectNode corps = (ObjectNode) lireCorps(reponse);
        corps.remove("instance");
        return corps;
    }

    private static Map<String, List<String>> entetesSansDate(HttpResponse<?> reponse) {
        Map<String, List<String>> entetes = new java.util.TreeMap<>(reponse.headers().map());
        entetes.remove("date");
        return entetes;
    }
}
