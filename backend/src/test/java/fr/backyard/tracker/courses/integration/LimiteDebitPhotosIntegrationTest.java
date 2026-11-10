package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 10.2, CA11 et CA12 (RG6) : plafond séparé de GET /api/photos/*, réglé à 5 par minute, celui de
 * /api/courses restant à 1200. Serveur réel, horloge de test pilotant la fenêtre d'une minute.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "backyard.limite-debit-public.par-minute=1200", "backyard.limite-debit-photos.par-minute=5",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class LimiteDebitPhotosIntegrationTest {

    static final Instant MINUTE_10H00 = Instant.parse("2026-10-21T10:00:00Z");
    static final Path REPERTOIRE = repertoireTemporaire();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add("backyard.photos.repertoire", REPERTOIRE::toString);
    }

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
    EntityManagerFactory entityManagerFactory;

    DecorAbandon decor;
    ClientPhotos photos;
    JournalApplicatif journal;
    UUID course;
    String urlPhoto;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
        photos = new ClientPhotos(port);
        course = decor.insererCourse("Backyard photos", "EN_COURS");
        decor.insererInscription(course, decor.idBruno, 1, "EN_COURSE");
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 1)).statusCode()).isEqualTo(200);
        // minute propre à chaque test : les compteurs d'une minute précédente ne comptent plus
        horloge.fixer(MINUTE_10H00.plusSeconds(60L * Math.floorMod(System.nanoTime(), 100_000L)));
        urlPhoto = photoUrl();
        horloge.fixer(horloge.instant().plusSeconds(60));
    }

    @AfterEach
    void nettoyer() {
        if (journal != null) {
            journal.fermer();
            journal = null;
        }
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-debit").resolve("photos");
        } catch (java.io.IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    private String photoUrl() throws Exception {
        HttpResponse<String> suivi = decor.api.requete("GET", "/api/courses/" + course + "/suivi", Map.of(), null, null);
        assertThat(suivi.statusCode()).isEqualTo(200);
        return decor.json.readTree(suivi.body()).get("coureursEnCourse").get(0).get("photoUrl").asString();
    }

    @Test
    @DisplayName("CA11 : plafond photos 5 : les 5 premiers GET répondent 200, le 6e 429 TROP_DE_REQUETES (ProblemDetail, Retry-After >= 1, no-store, aucune instruction SQL) ; le suivi de la même IP répond encore 200 ; à la minute suivante, la photo répond de nouveau 200")
    void ca11_sixieme_photo_refusee_sans_toucher_au_suivi() throws Exception {
        for (int i = 1; i <= 5; i++) {
            assertThat(photos.lire("GET", urlPhoto, Map.of()).statusCode()).as("photo " + i).isEqualTo(200);
        }
        journal = JournalApplicatif.demarrer();
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avant = statistiques.getPrepareStatementCount();

        HttpResponse<byte[]> refusee = photos.lire("GET", urlPhoto, Map.of());

        assertThat(refusee.statusCode()).isEqualTo(429);
        assertThat(statistiques.getPrepareStatementCount()).as("aucune lecture en base pour un refus").isEqualTo(avant);
        int retryAfter = Integer.parseInt(refusee.headers().firstValue("retry-after").orElseThrow());
        assertThat(retryAfter).isGreaterThanOrEqualTo(1);
        assertThat(refusee.headers().firstValue("cache-control")).hasValueSatisfying(c -> assertThat(c).contains("no-store"));
        assertThat(refusee.headers().firstValue("content-type")).hasValueSatisfying(
                t -> assertThat(t).startsWith("application/problem+json"));
        JsonNode corps = decor.json.readTree(new String(refusee.body(), StandardCharsets.UTF_8));
        assertThat(corps.get("status").asInt()).isEqualTo(429);
        assertThat(corps.get("code").asString()).isEqualTo("TROP_DE_REQUETES");
        assertThat(corps.get("reessayerDansSecondes").asInt()).isEqualTo(retryAfter);
        assertThat(photos.lire("GET", urlPhoto, Map.of()).statusCode()).as("toujours refusée").isEqualTo(429);

        // compteur séparé : le suivi de la même IP n'est pas affecté
        HttpResponse<String> suivi = decor.api.requete("GET", "/api/courses/" + course + "/suivi", Map.of(), null, null);
        assertThat(suivi.statusCode()).isEqualTo(200);

        // CA12 : une seule ligne WARN, sans jeton de photo
        List<ILoggingEvent> avertissements = journal.evenementsApplicatifs().stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("Débit public dépassé"))
                .toList();
        assertThat(avertissements).hasSize(1);
        assertThat(avertissements.get(0).getFormattedMessage())
                .doesNotContain(urlPhoto.substring("/api/photos/".length())).doesNotContain("Bruno");

        // fenêtre suivante
        Instant fin = horloge.instant();
        horloge.fixer(fin.plusSeconds(60 - (fin.getEpochSecond() % 60)));
        assertThat(photos.lire("GET", urlPhoto, Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA3 (correctif audit jalon 10) : plafond 5 : 3 GET + 2 HEAD sur la photo répondent 200, le 6e HEAD répond 429 TROP_DE_REQUETES (Retry-After >= 1, no-store, sans corps, aucune instruction SQL), le 7e GET aussi ; HEAD /api/courses/X/suivi x10 : 401 à chaque fois, jamais 429 ; minute suivante : 200")
    void ca3_head_compte_dans_le_plafond_des_photos() throws Exception {
        for (int i = 1; i <= 3; i++) {
            assertThat(photos.lire("GET", urlPhoto, Map.of()).statusCode()).as("GET " + i).isEqualTo(200);
        }
        for (int i = 1; i <= 2; i++) {
            assertThat(photos.lire("HEAD", urlPhoto, Map.of()).statusCode()).as("HEAD " + i).isEqualTo(200);
        }
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avant = statistiques.getPrepareStatementCount();

        HttpResponse<byte[]> refuse = photos.lire("HEAD", urlPhoto, Map.of());

        assertThat(refuse.statusCode()).isEqualTo(429);
        assertThat(statistiques.getPrepareStatementCount()).as("aucune requête SQL pour un refus").isEqualTo(avant);
        assertThat(Integer.parseInt(refuse.headers().firstValue("retry-after").orElseThrow()))
                .isGreaterThanOrEqualTo(1);
        assertThat(refuse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(refuse.body()).isEmpty();
        assertThat(photos.lire("GET", urlPhoto, Map.of()).statusCode()).as("7e appel (GET)").isEqualTo(429);

        for (int i = 1; i <= 10; i++) {
            assertThat(photos.lire("HEAD", "/api/courses/" + course + "/suivi", Map.of()).statusCode())
                    .as("HEAD suivi " + i).isEqualTo(401);
        }

        Instant fin = horloge.instant();
        horloge.fixer(fin.plusSeconds(60 - (fin.getEpochSecond() % 60)));
        assertThat(photos.lire("GET", urlPhoto, Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA11 : la saturation des photos ne consomme pas le plafond de /api/courses : après 6 photos, 1200 n'est pas atteint et GET /api/courses répond 200 ; HEAD sur /api/courses n'est pas compté")
    void ca11_compteurs_separes_et_head_non_compte() throws Exception {
        // Correctif audit jalon 10, RG2 : HEAD /api/photos/* est désormais compté (remplace 10.2 RG6 / CA5 pour les
        // photos) ; HEAD reste non compté sur /api/courses (réponse de la sécurité, jamais 429 de la limite).
        for (int i = 0; i < 20; i++) {
            assertThat(photos.lire("HEAD", "/api/courses/" + course + "/suivi", Map.of()).statusCode())
                    .isNotEqualTo(429);
        }
        for (int i = 1; i <= 5; i++) {
            assertThat(photos.lire("GET", urlPhoto, Map.of()).statusCode()).isEqualTo(200);
        }
        assertThat(photos.lire("GET", urlPhoto, Map.of()).statusCode()).isEqualTo(429);

        assertThat(photos.lire("GET", "/api/courses", Map.of()).statusCode()).isEqualTo(200);
        assertThat(photos.lire("GET", "/api/courses/" + course + "/suivi", Map.of()).statusCode()).isEqualTo(200);
    }
}
