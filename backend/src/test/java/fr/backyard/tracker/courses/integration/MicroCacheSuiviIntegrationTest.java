package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * Contrat de l'incrément 7.2 (micro-cache du suivi public) : CA5 et CA6, serveur réel. Les lectures du suivi sont
 * conservées 1 s (RG2), le chronométrage et {@code heureServeur} sont recalculés à chaque requête (RG1), un seul
 * chargement par Course à la fois (RG3), le pilotage ne passe jamais par le cache. Course X : Boucles de 1 min,
 * départ t0 = 14:00:00Z. Planificateur désactivé par le socle des tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class MicroCacheSuiviIntegrationTest {

    static final Instant T0 = DecorAbandon.T0;

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

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
    }

    private HttpResponse<String> suivi(String course) throws Exception {
        return decor.api.requete("GET", "/api/courses/" + course + "/suivi", Map.of(), null, null);
    }

    private JsonNode corps(String course) throws Exception {
        HttpResponse<String> reponse = suivi(course);
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(200);
        return decor.json.readTree(reponse.body());
    }

    private void a(long millisecondesApresT0) {
        horloge.fixer(T0.plusMillis(millisecondesApresT0));
    }

    @Test
    @DisplayName("CA5 : 20 GET /suivi concurrents sur une entrée expirée donnent 20 réponses 200 et exactement les instructions SQL d'un seul chargement")
    void ca5_un_seul_chargement_pour_vingt_requetes_concurrentes() throws Exception {
        String reference = decor.courseXDemarree();
        String cible = decor.courseDemarreeAvecMaxBoucles("Backyard concurrente", 5);
        a(10_000);
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avantReference = statistiques.getPrepareStatementCount();
        assertThat(suivi(reference).statusCode()).isEqualTo(200);
        long instructionsPourUnChargement = statistiques.getPrepareStatementCount() - avantReference;
        assertThat(instructionsPourUnChargement).isPositive();

        long avant = statistiques.getPrepareStatementCount();
        ExecutorService executeur = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch depart = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> reponses = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Callable<HttpResponse<String>> appel = () -> {
                    depart.await();
                    return suivi(cible);
                };
                reponses.add(executeur.submit(appel));
            }
            depart.countDown();
            for (Future<HttpResponse<String>> reponse : reponses) {
                assertThat(reponse.get().statusCode()).isEqualTo(200);
            }
        } finally {
            executeur.shutdownNow();
        }

        assertThat(statistiques.getPrepareStatementCount() - avant)
                .as("un seul chargement partagé par les 20 requêtes").isEqualTo(instructionsPourUnChargement);
    }

    @Test
    @DisplayName("CA6 : dans la même seconde un scan n'apparaît pas au suivi (allure inchangée) mais heureServeur avance et le pilotage le voit tout de suite ; après 1 s le suivi contient le Passage")
    void ca6_lectures_en_cache_calculs_frais_et_pilotage_sans_cache() throws Exception {
        String x = decor.courseXDemarree();
        a(5_000);
        JsonNode avant = corps(x);
        assertThat(avant.get("coureursEnCourse")).hasSize(3);
        assertThat(avant.get("allureSecondesParKilometre").isNull()).isTrue();

        a(5_300);
        HttpResponse<String> scan = decor.scanner(decor.leo, x, decor.jeton(x, decor.idBruno), UUID.randomUUID(), 0);
        assertThat(scan.statusCode()).as(scan.body()).isEqualTo(201);
        a(5_600);

        JsonNode memeSeconde = corps(x);
        assertThat(Instant.parse(memeSeconde.get("heureServeur").asString()))
                .isAfter(Instant.parse(avant.get("heureServeur").asString()))
                .isEqualTo(T0.plusMillis(5_600));
        assertThat(memeSeconde.get("tempsAvantFinBoucleMillisecondes").asLong()).isEqualTo(54_400L);
        assertThat(memeSeconde.get("dureeEcouleeMillisecondes").asLong()).isEqualTo(5_600L);
        assertThat(memeSeconde.get("allureSecondesParKilometre").isNull()).as("Passage pas encore lu").isTrue();
        JsonNode pilotage = decor.json.readTree(decor.pilotage(decor.nadia, x).body());
        assertThat(pilotage.get("nombreAyantBoucle").asInt()).as("le pilotage ignore le cache").isEqualTo(1);

        a(6_000);
        JsonNode apresUneSeconde = corps(x);
        assertThat(apresUneSeconde.get("allureSecondesParKilometre").isNull()).as("Passage lu après 1 s").isFalse();
        assertThat(apresUneSeconde.get("allureSecondesParKilometre").asLong()).isPositive();
    }
}
