package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.infrastructure.RegistreCreationsCompteParAdresseEnMemoire;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CA16 (incrément 8.5) : atomicité de l'admission. Plafond (10, 60), hachage sérialisé par adresse avec attente longue
 * (jamais de 503). Aucune assertion sur l'ordre d'arrivée, seulement sur les décomptes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.creation-compte.par-ip-max=10",
        "backyard.creation-compte.par-ip-fenetre-secondes=60",
        "backyard.limite-debit-public.proxy-de-confiance=localhost",
        "backyard.hachage.concurrence-max=32",
        "backyard.hachage.concurrence-max-par-ip=1",
        "backyard.hachage.attente-max-millisecondes=120000"})
@Testcontainers
class CreationCompteConcurrenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    RegistreCreationsCompteParAdresseEnMemoire registreCreations;

    JdbcTemplate jdbc;
    ClientCreationCompte client;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registreCreations.vider();
        client = new ClientCreationCompte(new ApiHttp(port));
    }

    @Test
    @DisplayName("CA16 : 30 créations valides simultanées d'une même adresse (seuil 10) : exactement 10 reçoivent 201 et 20 reçoivent 429, 10 Comptes en base, une seule adresse suivie")
    void ca16_exactement_le_seuil_est_admis() throws Exception {
        int nombre = 30;
        ExecutorService executeur = Executors.newFixedThreadPool(nombre);
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<Integer>> futurs = new ArrayList<>();
        try {
            for (int i = 0; i < nombre; i++) {
                String pseudo = ClientCreationCompte.pseudoUnique();
                futurs.add(executeur.submit(() -> {
                    depart.await();
                    HttpResponse<String> reponse = client.creer("203.0.113.1", pseudo);
                    return reponse.statusCode();
                }));
            }
            depart.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> futur : futurs) {
                codes.add(futur.get());
            }
            assertThat(codes.stream().filter(code -> code == 201).count()).as("201").isEqualTo(10);
            assertThat(codes.stream().filter(code -> code == 429).count()).as("429").isEqualTo(20);
            assertThat(codes).allMatch(code -> code == 201 || code == 429);
        } finally {
            executeur.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(10);
        assertThat(registreCreations.taille()).isEqualTo(1);
    }
}
