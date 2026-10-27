package fr.backyard.tracker.comptes.integration;

import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.MOT_DE_PASSE;
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
import fr.backyard.tracker.comptes.domaine.PlafondCreationsCompteParAdresse;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.RegistreCreationsCompteParAdresse;
import fr.backyard.tracker.comptes.domaine.RegistreEchecsParAdresse;
import fr.backyard.tracker.comptes.infrastructure.EncodeurMotDePasseArgon2;
import fr.backyard.tracker.comptes.infrastructure.RegistreCreationsCompteParAdresseEnMemoire;
import fr.backyard.tracker.comptes.infrastructure.RegistreEchecsParAdresseEnMemoire;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
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
import java.util.Set;
import java.util.TreeSet;
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

/**
 * Contrat de l'incrément 8.5 (CA9 à CA14, CA17) : plafond de créations de Comptes coureur par adresse cliente. Plafond
 * (3, 60), proxy de confiance = pair de test (l'adresse est celle de {@code X-Forwarded-For}), horloge mutable,
 * hachage relevé (jamais de 503), limite de débit publique de 7.2 à 5 par minute.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.creation-compte.par-ip-max=3",
        "backyard.creation-compte.par-ip-fenetre-secondes=60",
        "backyard.limite-debit-public.proxy-de-confiance=localhost",
        "backyard.limite-debit-public.par-minute=5",
        "backyard.hachage.concurrence-max=32",
        "backyard.hachage.concurrence-max-par-ip=32",
        "backyard.hachage.attente-max-millisecondes=120000",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
class CreationComptePlafondAdresseIntegrationTest {

    static final Instant T = Instant.parse("2026-10-26T10:00:00Z");
    static final String A1 = "203.0.113.1";
    static final String A2 = "203.0.113.2";
    static final String A5 = "203.0.113.5";
    static final String A7 = "203.0.113.7";
    static final String A9 = "203.0.113.9";
    static final String DETAIL = "Trop de créations de compte depuis cette adresse. Réessayez dans quelques instants.";

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
    RegistreCreationsCompteParAdresseEnMemoire registreCreations;

    @Autowired
    RegistreEchecsParAdresseEnMemoire registreEchecs;

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
    ClientCreationCompte client;
    JournalApplicatif journal;
    static String empreinteDeReference;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registreCreations.vider();
        registreEchecs.vider();
        registrePseudos.vider();
        horloge.fixer(T);
        api = new ApiHttp(port);
        client = new ClientCreationCompte(api);
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

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : 3 créations valides depuis une adresse (201) puis 429 TROP_DE_REQUETES au contrat exact, sans session, sans pseudo ni mot de passe ; le 4e pseudo n'existe pas ; une requête invalide suivante reçoit aussi 429")
    void ca9_trois_creations_puis_429_au_contrat_exact() throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> creation = client.creerAvecUnPseudoNeuf(A1);
            assertThat(creation.statusCode()).as("création " + (i + 1)).isEqualTo(201);
            assertThat(lireCorps(creation).get("role").asString()).isEqualTo("COUREUR");
        }
        String pseudoRefuse = ClientCreationCompte.pseudoUnique();

        HttpResponse<String> refus = client.creer(A1, pseudoRefuse);

        verifierContratDu429(refus);
        assertThat(refus.body()).doesNotContain(pseudoRefuse).doesNotContain(MOT_DE_PASSE).doesNotContain(A1);
        assertThat(jdbc.queryForObject("select count(*) from compte where pseudo = ?", Integer.class, pseudoRefuse))
                .as("le pseudo refusé n'existe pas").isZero();
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(3);

        HttpResponse<String> invalide = client.creer(A1, "", "court");
        assertThat(invalide.statusCode()).as("le 429 précède la validation").isEqualTo(429);
        assertThat(lireCorps(invalide).get("code").asString()).isEqualTo("TROP_DE_REQUETES");
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : 403 CSRF absent, 400 CORPS_ILLISIBLE et 409 DEJA_CONNECTE ne sont pas comptés : 3 créations valides ensuite donnent 201, la 4e 429")
    void ca10_refus_en_amont_du_cas_d_usage_non_comptes() throws Exception {
        compte("Karim", "COUREUR");
        Map<String, String> sessionOuverte = ouvrirSession("Karim", A1);

        for (int i = 0; i < 5; i++) {
            HttpResponse<String> sansJeton = client.envoyer(A1, corps(ClientCreationCompte.pseudoUnique(), MOT_DE_PASSE),
                    null);
            assertThat(sansJeton.statusCode()).as("sans jeton " + i).isEqualTo(403);
            assertThat(lireCorps(sansJeton).get("code").asString()).isEqualTo("CSRF_INVALIDE");
        }
        HttpResponse<String> illisible = client.envoyer(A1, "{pas du json", client.jeton());
        assertThat(illisible.statusCode()).isEqualTo(400);
        assertThat(lireCorps(illisible).get("code").asString()).isEqualTo("CORPS_ILLISIBLE");
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> dejaConnecte = api.requete("POST", "/api/comptes", sessionOuverte,
                    "application/json", corps(ClientCreationCompte.pseudoUnique(), MOT_DE_PASSE));
            assertThat(dejaConnecte.statusCode()).as("déjà connecté " + i).isEqualTo(409);
            assertThat(lireCorps(dejaConnecte).get("code").asString()).isEqualTo("DEJA_CONNECTE");
        }

        for (int i = 0; i < 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).as("création " + (i + 1)).isEqualTo(201);
        }
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("CA10 : un pseudo déjà utilisé (409) et une saisie invalide (400) comptent chacun pour une tentative : avec une création valide, la 4e tentative reçoit 429")
    void ca10_echecs_de_creation_comptes() throws Exception {
        compte("Existant", "COUREUR");

        HttpResponse<String> pseudoPris = client.creer(A1, "Existant");
        assertThat(pseudoPris.statusCode()).isEqualTo(409);
        assertThat(lireCorps(pseudoPris).get("code").asString()).isEqualTo("PSEUDO_DEJA_UTILISE");
        HttpResponse<String> motDePasseCourt = client.creer(A1, ClientCreationCompte.pseudoUnique(), "court");
        assertThat(motDePasseCourt.statusCode()).isEqualTo(400);
        assertThat(lireCorps(motDePasseCourt).get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(201);

        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : une adresse plafonnée n'appelle pas l'encodeur Argon2id et n'émet aucune requête SQL, quelle que soit la saisie (valide, invalide, pseudo existant)")
    void ca11_refus_sans_hachage_ni_sql() throws Exception {
        compte("Existant", "COUREUR");
        for (int i = 0; i < 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(201);
        }
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        clearInvocations(argon2);
        long avant = statistiques.getPrepareStatementCount();

        List<HttpResponse<String>> refus = List.of(
                client.creerAvecUnPseudoNeuf(A1),
                client.creer(A1, "Existant"),
                client.creer(A1, "", "court"),
                client.creer(A1, "pseudo invalide !", MOT_DE_PASSE));

        assertThat(statistiques.getPrepareStatementCount() - avant).as("requêtes SQL pendant les refus").isZero();
        verify(argon2, never()).encode(any());
        verify(argon2, never()).matches(any(), anyString());
        for (HttpResponse<String> reponse : refus) {
            assertThat(reponse.statusCode()).isEqualTo(429);
            assertThat(reponse.body()).doesNotContain("Existant").doesNotContain("court");
        }
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : tentatives à T, T+10 s, T+20 s : 429 à T+20 s (Retry-After 40), 429 à T+59 s (1), 201 à T+60 s puis 429 (Retry-After 10) ; les refus ne décalent rien ; tout est libéré 60 s après la dernière tentative admise")
    void ca12_fenetre_glissante_aux_instants_exacts() throws Exception {
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(201);
        horloge.fixer(T.plusSeconds(10));
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(201);
        horloge.fixer(T.plusSeconds(20));
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(201);

        assertRetryAfter(client.creerAvecUnPseudoNeuf(A1), 40);
        horloge.fixer(T.plusSeconds(30));
        for (int i = 0; i < 20; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(429);
        }
        assertRetryAfter(client.creerAvecUnPseudoNeuf(A1), 30);
        horloge.fixer(T.plusSeconds(59));
        assertRetryAfter(client.creerAvecUnPseudoNeuf(A1), 1);

        horloge.fixer(T.plusSeconds(60));
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).as("T a expiré").isEqualTo(201);
        assertRetryAfter(client.creerAvecUnPseudoNeuf(A1), 10);

        horloge.fixer(T.plusSeconds(120));
        for (int i = 0; i < 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).as("adresse libérée " + i).isEqualTo(201);
        }
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("CA12 : pas de rafale 2N à la jonction de deux minutes : 3 créations à T+59 s, la 4e à T+60 s est refusée")
    void ca12_pas_de_rafale_a_la_jonction() throws Exception {
        horloge.fixer(T.plusSeconds(59));
        for (int i = 0; i < 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(201);
        }
        horloge.fixer(T.plusSeconds(60));
        assertRetryAfter(client.creerAvecUnPseudoNeuf(A1), 59);
    }

    // ---------------------------------------------------------------- CA13

    @Test
    @DisplayName("CA13 : compteur de création épuisé pour une adresse : la santé (/api/sante côté Caddy, /actuator/health côté api), /api/courses, la connexion et la création de BENEVOLE par un ADMIN (5 fois) aboutissent depuis cette adresse")
    void ca13_les_autres_fonctions_restent_disponibles() throws Exception {
        compte("Karim", "COUREUR");
        compte("Nadia", "ADMIN");
        epuiserLaCreation(A1);

        // /api/sante est une réécriture de Caddy vers l'actuator : côté api, l'endpoint de santé est /actuator/health.
        assertThat(api.requete("GET", "/actuator/health", Map.of("X-Forwarded-For", A1), null, null).statusCode())
                .isEqualTo(200);
        assertThat(api.requete("GET", "/api/courses", Map.of("X-Forwarded-For", A1), null, null).statusCode())
                .isEqualTo(200);
        assertThat(client.connexion().connecter(A1, "Karim", MOT_DE_PASSE).statusCode()).isEqualTo(200);

        Map<String, String> admin = ouvrirSession("Nadia", A1);
        for (int i = 1; i <= 5; i++) {
            HttpResponse<String> benevole = api.requete("POST", "/api/administration/benevoles", admin,
                    "application/json", corps("Benevole-" + i, MOT_DE_PASSE));
            assertThat(benevole.statusCode()).as("bénévole " + i).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("select count(*) from compte where role = 'BENEVOLE'", Integer.class))
                .isEqualTo(5);
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).as("toujours plafonnée").isEqualTo(429);
    }

    @Test
    @DisplayName("CA13 : 20 échecs de connexion d'une adresse ne consomment aucune création (3 admises, la 4e refusée) ; le compteur de création n'entame pas les échecs de connexion")
    void ca13_compteurs_de_creation_et_de_connexion_independants() throws Exception {
        client.connexion().echouer(A2, "Spray", 20);
        for (int i = 0; i < 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(A2).statusCode()).as("création " + (i + 1)).isEqualTo(201);
        }
        assertThat(client.creerAvecUnPseudoNeuf(A2).statusCode()).isEqualTo(429);

        epuiserLaCreation(A5);
        client.connexion().echouer(A5, "Autre", 20);
        HttpResponse<String> echec = client.connexion().connecter(A5, "Autre-21", ClientConnexionParAdresse.MOT_DE_PASSE_FAUX);
        assertThat(echec.statusCode()).as("pas de 429 de connexion : plafond de connexion à 1000").isEqualTo(401);
    }

    @Test
    @DisplayName("CA13 : la limite de débit publique de 7.2 épuisée pour une adresse (429 sur /api/courses) n'empêche pas POST /api/comptes d'aboutir")
    void ca13_limite_de_debit_publique_independante() throws Exception {
        Map<String, String> entetes = Map.of("X-Forwarded-For", A7);
        for (int i = 0; i < 5; i++) {
            assertThat(api.requete("GET", "/api/courses", entetes, null, null).statusCode()).isEqualTo(200);
        }
        HttpResponse<String> limite = api.requete("GET", "/api/courses", entetes, null, null);
        assertThat(limite.statusCode()).isEqualTo(429);
        assertThat(lireCorps(limite).get("code").asString()).isEqualTo("TROP_DE_REQUETES");

        assertThat(client.creerAvecUnPseudoNeuf(A7).statusCode()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- CA14

    @Test
    @DisplayName("CA14 : derrière le proxy de confiance, deux adresses ont chacune leur compteur (6 créations admises), la 4e de chaque adresse est refusée ; une troisième adresse n'est pas touchée")
    void ca14_adresses_distinctes_independantes() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).as("A1 " + i).isEqualTo(201);
        }
        for (int i = 0; i < 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(A2).statusCode()).as("A2 " + i).isEqualTo(201);
        }
        assertThat(client.creerAvecUnPseudoNeuf(A1).statusCode()).isEqualTo(429);
        assertThat(client.creerAvecUnPseudoNeuf(A2).statusCode()).isEqualTo(429);
        assertThat(client.creerAvecUnPseudoNeuf(A5).statusCode()).isEqualTo(201);
        assertThat(registreCreations.taille()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA14 : IPv6, les adresses d'un même /64 partagent le compteur (la 4e refusée), un autre /64 est indépendant")
    void ca14_ipv6_prefixe_64() throws Exception {
        assertThat(client.creerAvecUnPseudoNeuf("2001:db8:1:2::1").statusCode()).isEqualTo(201);
        assertThat(client.creerAvecUnPseudoNeuf("2001:db8:1:2::2").statusCode()).isEqualTo(201);
        assertThat(client.creerAvecUnPseudoNeuf("2001:db8:1:2:aaaa:bbbb:cccc:dddd").statusCode()).isEqualTo(201);

        assertThat(client.creerAvecUnPseudoNeuf("2001:db8:1:2::3").statusCode()).isEqualTo(429);
        assertThat(client.creerAvecUnPseudoNeuf("2001:db8:1:3::1").statusCode()).isEqualTo(201);
        assertThat(registreCreations.taille()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA14 : une IPv6 de forme IPv4 est ramenée à l'IPv4 et partage son compteur")
    void ca14_ipv6_de_forme_ipv4() throws Exception {
        epuiserLaCreation(A1);

        assertThat(client.creerAvecUnPseudoNeuf("::ffff:" + A1).statusCode()).isEqualTo(429);
    }

    // ---------------------------------------------------------------- CA17 (socle) et structure

    @Test
    @DisplayName("CA17 : un seul bean de plafond de création, un seul registre de création, et un seul plafond et registre d'échecs de connexion (compteurs séparés)")
    void ca17_un_seul_bean_de_chaque() {
        assertThat(contexte.getBeansOfType(PlafondCreationsCompteParAdresse.class)).hasSize(1);
        assertThat(contexte.getBean(PlafondCreationsCompteParAdresse.class))
                .isEqualTo(new PlafondCreationsCompteParAdresse(3, 60));
        assertThat(contexte.getBeansOfType(RegistreCreationsCompteParAdresse.class)).hasSize(1);
        assertThat(contexte.getBeansOfType(PlafondEchecsParAdresse.class)).hasSize(1);
        assertThat(contexte.getBeansOfType(RegistreEchecsParAdresse.class)).hasSize(1);
        assertThat(contexte.getBean(RegistreCreationsCompteParAdresse.class))
                .isNotSameAs(contexte.getBean(RegistreEchecsParAdresse.class));
    }

    // ---------------------------------------------------------------- journal (RG8)

    @Test
    @DisplayName("RG8 : 10 créations depuis une adresse (plafond 3) : une seule ligne WARN, 7 lignes DEBUG de refus, ni pseudo, ni mot de passe, ni adresse, ni cookie dans le journal")
    void rg8_journal_du_plafond() throws Exception {
        journal.niveau(Logger.ROOT_LOGGER_NAME, Level.DEBUG).niveau("org.hibernate", Level.INFO);

        for (int i = 0; i < 10; i++) {
            client.creer(A1, "Zorglub" + i);
        }

        List<ILoggingEvent> evenements = journal.evenements();
        assertThat(evenements.stream().filter(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("Création de compte : plafond par adresse atteint"))).hasSize(1);
        assertThat(evenements.stream().filter(e -> e.getLevel() == Level.DEBUG
                && e.getFormattedMessage().contains("Création de compte refusée : plafond par adresse atteint")))
                .hasSize(7);
        assertThat(evenements.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)
                && e.getFormattedMessage().contains("Création de compte refusée"))).isEmpty();
        List<String> lignes = new ArrayList<>();
        for (ILoggingEvent evenement : evenements) {
            lignes.add(evenement.getFormattedMessage());
            if (evenement.getThrowableProxy() != null) {
                lignes.add(ThrowableProxyUtil.asString(evenement.getThrowableProxy()));
            }
        }
        assertThat(lignes).noneMatch(l -> l.contains(MOT_DE_PASSE) || l.contains(A1)
                || l.contains(client.jeton().cookie()) || l.contains("JSESSIONID"));
        assertThat(evenements.stream().filter(e -> e.getLoggerName().startsWith(JournalApplicatif.PAQUET_APPLICATION)
                || e.getLevel().isGreaterOrEqual(Level.INFO)).map(ILoggingEvent::getFormattedMessage))
                .noneMatch(l -> l.contains("Zorglub"));
    }

    // ---------------------------------------------------------------- utilitaires

    private void compte(String pseudo, String role) {
        creerCompte(jdbc, pseudo, role, empreinteDeReference, true, T);
    }

    private void epuiserLaCreation(String adresse) throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(adresse).statusCode()).as("épuisement " + i).isEqualTo(201);
        }
    }

    private Map<String, String> ouvrirSession(String pseudo, String adresse) throws Exception {
        HttpResponse<String> ouverture = client.connexion().connecter(adresse, pseudo, MOT_DE_PASSE);
        assertThat(ouverture.statusCode()).as("connexion de " + pseudo).isEqualTo(200);
        Map<String, String> entetes = new HashMap<>();
        entetes.put("Cookie", "JSESSIONID=" + ApiHttp.valeurCookie(ouverture, "JSESSIONID")
                + "; XSRF-TOKEN=" + client.jeton().cookie());
        entetes.put("X-XSRF-TOKEN", client.jeton().entete());
        entetes.put("X-Forwarded-For", adresse);
        return entetes;
    }

    private static void assertRetryAfter(HttpResponse<String> reponse, int attendu) {
        assertThat(reponse.statusCode()).isEqualTo(429);
        assertThat(reponse.headers().firstValue("retry-after")).hasValue(Integer.toString(attendu));
        assertThat(lireCorps(reponse).get("reessayerDansSecondes").asInt()).isEqualTo(attendu);
    }

    private static void verifierContratDu429(HttpResponse<String> refus) {
        assertThat(refus.statusCode()).isEqualTo(429);
        String retryAfter = refus.headers().firstValue("retry-after").orElseThrow();
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, 60);
        assertThat(refus.headers().firstValue("cache-control")).hasValue("no-store");
        assertThat(refus.headers().firstValue("content-type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(refus.headers().allValues("set-cookie")).noneMatch(c -> c.startsWith("JSESSIONID"));

        JsonNode corps = lireCorps(refus);
        assertThat(new TreeSet<>(corps.propertyNames())).isEqualTo(
                new TreeSet<>(Set.of("type", "title", "status", "detail", "instance", "code", "reessayerDansSecondes")));
        assertThat(corps.get("type").asString()).isEqualTo("about:blank");
        assertThat(corps.get("title").asString()).isEqualTo("Trop de requêtes");
        assertThat(corps.get("status").asInt()).isEqualTo(429);
        assertThat(corps.get("detail").asString()).isEqualTo(DETAIL);
        assertThat(corps.get("instance").asString()).isEqualTo("/api/comptes");
        assertThat(corps.get("code").asString()).isEqualTo("TROP_DE_REQUETES");
        assertThat(corps.get("reessayerDansSecondes").asInt()).isEqualTo(Integer.parseInt(retryAfter));
    }
}
