package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import fr.backyard.tracker.comptes.infrastructure.RegistreCreationsCompteParAdresseEnMemoire;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CA11 et RG1 (incrément 8.5) : un 503 SERVICE_SATURE est compté, et un refus d'adresse ne prend aucune place du plafond
 * de hachage. Plafond de création (2, 60), un seul calcul d'empreinte à la fois et sans attente.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.creation-compte.par-ip-max=2",
        "backyard.creation-compte.par-ip-fenetre-secondes=60",
        "backyard.limite-debit-public.proxy-de-confiance=localhost",
        "backyard.hachage.concurrence-max=1",
        "backyard.hachage.attente-max-millisecondes=0"})
@Testcontainers
class CreationCompteSaturationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    RegistreCreationsCompteParAdresseEnMemoire registreCreations;

    @MockitoSpyBean
    PasswordEncoder argon2;

    JdbcTemplate jdbc;
    ClientCreationCompte client;

    @BeforeEach
    void preparer() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registreCreations.vider();
        client = new ClientCreationCompte(new ApiHttp(port));
    }

    @AfterEach
    void nettoyer() {
        reset(argon2);
    }

    @Test
    @DisplayName("RG1/CA11 : un 503 SERVICE_SATURE compte comme une tentative ; l'adresse plafonnée reçoit 429 (et non 503) alors que l'unique place de hachage est occupée")
    void le_503_est_compte_et_le_refus_precede_le_hachage() throws Exception {
        CountDownLatch enCours = new CountDownLatch(1);
        CountDownLatch liberer = new CountDownLatch(1);
        doAnswer(appel -> {
            enCours.countDown();
            liberer.await(20, TimeUnit.SECONDS);
            return appel.callRealMethod();
        }).when(argon2).encode(any());
        String occupant = ClientCreationCompte.pseudoUnique();
        CompletableFuture<HttpResponse<String>> creationEnCours = CompletableFuture.supplyAsync(() -> {
            try {
                return client.creer("203.0.113.1", occupant);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertThat(enCours.await(20, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> sature = client.creerAvecUnPseudoNeuf("203.0.113.1");
        HttpResponse<String> refusee = client.creerAvecUnPseudoNeuf("203.0.113.1");
        HttpResponse<String> autreAdresse = client.creerAvecUnPseudoNeuf("203.0.113.2");
        liberer.countDown();

        assertThat(sature.statusCode()).as("place de hachage occupée").isEqualTo(503);
        assertThat(refusee.statusCode()).as("deux tentatives admises : plafonnée avant le hachage").isEqualTo(429);
        assertThat(autreAdresse.statusCode()).as("une autre adresse atteint le hachage saturé").isEqualTo(503);
        assertThat(creationEnCours.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(1);
    }
}
