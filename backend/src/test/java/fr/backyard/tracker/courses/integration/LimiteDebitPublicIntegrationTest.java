package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 7.2 (limite de débit des routes publiques) : CA10 et CA11, serveur réel. Limite fixée à 5
 * requêtes par IP et par minute, aucun proxy de confiance : un {@code X-Forwarded-For} envoyé directement à l'api est
 * ignoré. L'horloge de test pilote la fenêtre d'une minute.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "backyard.limite-debit-public.par-minute=5",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class LimiteDebitPublicIntegrationTest {

    static final Instant MINUTE_10H00 = Instant.parse("2026-10-21T10:00:00Z");

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
    InitialiserAdminMaster initialiserAdminMaster;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    @Autowired
    CoursesIntegrationTest.HorlogeMutable horloge;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    DecorAbandon decor;
    UUID course;
    JournalApplicatif journal;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
        course = decor.insererCourse("Backyard limitée", "EN_PREPARATION");
        // minute propre à chaque test : les compteurs d'une minute précédente ne comptent plus
        horloge.fixer(MINUTE_10H00.plusSeconds(60L * Math.floorMod(System.nanoTime(), 100_000L)));
    }

    @AfterEach
    void nettoyer() {
        if (journal != null) {
            journal.fermer();
        }
    }

    private HttpResponse<String> suivi(Map<String, String> entetes) throws Exception {
        return decor.api.requete("GET", "/api/courses/" + course + "/suivi", entetes, null, null);
    }

    @Test
    @DisplayName("CA10 : limite 5 : les 5 premiers GET /suivi donnent 200 même avec un X-Forwarded-For falsifié, le 6e 429 ; à la minute suivante, 200")
    void ca10_sixieme_requete_refusee_puis_fenetre_suivante() throws Exception {
        for (int i = 1; i <= 5; i++) {
            Map<String, String> entetes = i == 1 ? Map.of() : Map.of("X-Forwarded-For", "198.51.100." + i);
            assertThat(suivi(entetes).statusCode()).as("requête " + i).isEqualTo(200);
        }

        HttpResponse<String> refusee = suivi(Map.of("X-Forwarded-For", "198.51.100.99"));
        assertThat(refusee.statusCode()).as("un X-Forwarded-For falsifié ne contourne pas la limite").isEqualTo(429);

        Instant fin = horloge.instant();
        horloge.fixer(fin.plusSeconds(60 - (fin.getEpochSecond() % 60)));
        assertThat(suivi(Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA10 : les routes publiques GET /api/courses et GET /api/courses/{id}/logo partagent le même compteur que le suivi")
    void ca10_compteur_commun_aux_routes_publiques() throws Exception {
        assertThat(decor.api.requete("GET", "/api/courses", Map.of(), null, null).statusCode()).isEqualTo(200);
        assertThat(suivi(Map.of()).statusCode()).isEqualTo(200);
        assertThat(decor.api.requete("GET", "/api/courses/" + course + "/logo", Map.of(), null, null).statusCode())
                .isIn(200, 404);
        assertThat(suivi(Map.of()).statusCode()).isEqualTo(200);
        assertThat(decor.api.requete("GET", "/api/courses", Map.of(), null, null).statusCode()).isEqualTo(200);

        assertThat(decor.api.requete("GET", "/api/courses", Map.of(), null, null).statusCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("CA11 : le 429 porte Retry-After entier >= 1, un ProblemDetail TROP_DE_REQUETES, no-store, aucune donnée de Course, aucune instruction SQL ; /actuator/health, /api/csrf et connexion répondent normalement ; une seule ligne WARN")
    void ca11_contenu_du_refus() throws Exception {
        horloge.fixer(horloge.instant().plusMillis(30_400));
        for (int i = 1; i <= 5; i++) {
            assertThat(suivi(Map.of()).statusCode()).isEqualTo(200);
        }
        journal = JournalApplicatif.demarrer();
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avant = statistiques.getPrepareStatementCount();

        HttpResponse<String> refusee = suivi(Map.of());
        HttpResponse<String> encore = suivi(Map.of());

        assertThat(statistiques.getPrepareStatementCount()).as("aucune requête SQL pour un refus").isEqualTo(avant);
        assertThat(refusee.statusCode()).isEqualTo(429);
        int retryAfter = Integer.parseInt(refusee.headers().firstValue("retry-after").orElseThrow());
        assertThat(retryAfter).isEqualTo(30);
        assertThat(refusee.headers().firstValue("cache-control")).hasValueSatisfying(c -> assertThat(c).contains("no-store"));
        assertThat(refusee.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        JsonNode corps = decor.json.readTree(refusee.body());
        assertThat(corps.get("status").asInt()).isEqualTo(429);
        assertThat(corps.get("title").asString()).isEqualTo("Trop de requêtes");
        assertThat(corps.get("detail").asString()).isEqualTo("Trop de requêtes. Réessayez dans quelques secondes.");
        assertThat(corps.get("code").asString()).isEqualTo("TROP_DE_REQUETES");
        assertThat(corps.get("reessayerDansSecondes").asInt()).isEqualTo(retryAfter);
        assertThat(refusee.body()).doesNotContain("Backyard limitée").doesNotContain("EN_PREPARATION");
        assertThat(encore.statusCode()).isEqualTo(429);

        assertThat(decor.api.requete("GET", "/api/csrf", Map.of(), null, null).statusCode()).isEqualTo(204);
        assertThat(decor.api.requete("GET", "/actuator/health", Map.of(), null, null).statusCode()).isEqualTo(200);
        assertThat(decor.api.ouvrir("Alice", DecorAbandon.MOT_DE_PASSE).id()).isNotBlank();
        List<ILoggingEvent> avertissements = journal.evenementsApplicatifs().stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("Débit public dépassé"))
                .toList();
        assertThat(avertissements).as("au plus une ligne WARN par IP et par fenêtre").hasSize(1);
        assertThat(avertissements.get(0).getFormattedMessage()).doesNotContain("JSESSIONID").doesNotContain("Alice");
    }

    @Test
    @DisplayName("CA10 : une méthode autre que GET sur /api/courses/** n'est pas comptée (POST refusé par la sécurité, pas par la limite)")
    void ca10_les_methodes_non_get_ne_sont_pas_comptees() throws Exception {
        for (int i = 0; i < 8; i++) {
            assertThat(decor.api.requete("POST", "/api/courses/" + course + "/suivi", Map.of(), null, null)
                    .statusCode()).isNotEqualTo(429);
        }
        assertThat(suivi(Map.of()).statusCode()).isEqualTo(200);
    }
}
