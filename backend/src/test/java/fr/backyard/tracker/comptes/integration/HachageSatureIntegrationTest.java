package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SEC-06 (audit 2026-10-16) : connexion et création de compte calculent l'empreinte hors transaction, et au-delà du
 * plafond de calculs simultanés la requête est refusée en 503 SERVICE_SATURE, sans effet. Plafond réduit à 1 calcul,
 * sans attente, pour rendre la saturation déterministe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.hachage.concurrence-max=1",
        "backyard.hachage.attente-max-millisecondes=0"})
@Testcontainers
class HachageSatureIntegrationTest {

    static final String CORPS = "{\"pseudo\":\"%s\",\"motDePasse\":\"un-mot-de-passe-12\"}";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    @MockitoSpyBean
    PasswordEncoder argon2;

    JdbcTemplate jdbc;
    ApiHttp api;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void preparer() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from compte");
        registre.vider();
        api = new ApiHttp(port);
    }

    @AfterEach
    void nettoyer() {
        reset(argon2);
    }

    @Test
    @DisplayName("SEC-06 : la création de compte et la connexion calculent l'empreinte hors de toute transaction")
    void doit_hacher_hors_transaction() throws Exception {
        AtomicReference<Boolean> transactionALaCreation = new AtomicReference<>();
        AtomicReference<Boolean> transactionALaConnexion = new AtomicReference<>();
        doAnswer(appel -> {
            transactionALaCreation.set(TransactionSynchronizationManager.isActualTransactionActive());
            return appel.callRealMethod();
        }).when(argon2).encode(any());
        doAnswer(appel -> {
            transactionALaConnexion.set(TransactionSynchronizationManager.isActualTransactionActive());
            return appel.callRealMethod();
        }).when(argon2).matches(any(), anyString());

        assertThat(api.postJson("/api/comptes", CORPS.formatted("Alice"), api.jetonValide()).statusCode())
                .isEqualTo(201);
        assertThat(api.postJson("/api/connexion", CORPS.formatted("Alice"), api.jetonValide()).statusCode())
                .isEqualTo(200);

        assertThat(transactionALaCreation.get()).as("transaction pendant le hachage de création").isFalse();
        assertThat(transactionALaConnexion.get()).as("transaction pendant la vérification de connexion").isFalse();
    }

    @Test
    @DisplayName("SEC-06 : au-delà du plafond, connexion et création répondent 503 SERVICE_SATURE sans effet")
    void doit_refuser_en_503_au_dela_du_plafond() throws Exception {
        assertThat(api.postJson("/api/comptes", CORPS.formatted("Alice"), api.jetonValide()).statusCode())
                .isEqualTo(201);
        CountDownLatch enCours = new CountDownLatch(1);
        CountDownLatch liberer = new CountDownLatch(1);
        doAnswer(appel -> {
            enCours.countDown();
            liberer.await(10, TimeUnit.SECONDS);
            return appel.callRealMethod();
        }).when(argon2).matches(any(), anyString());
        ApiHttp.Jeton jetonOccupant = api.jetonValide();
        CompletableFuture<HttpResponse<String>> occupant = CompletableFuture.supplyAsync(() -> connecter(jetonOccupant));
        assertThat(enCours.await(10, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> connexion = api.postJson("/api/connexion", CORPS.formatted("Alice"), api.jetonValide());
        HttpResponse<String> creation = api.postJson("/api/comptes", CORPS.formatted("Bruno"), api.jetonValide());
        liberer.countDown();

        verifierSaturation(connexion);
        verifierSaturation(creation);
        assertThat(jdbc.queryForObject("select count(*) from compte where pseudo = 'Bruno'", Integer.class)).isZero();
        assertThat(occupant.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(api.postJson("/api/comptes", CORPS.formatted("Bruno"), api.jetonValide()).statusCode())
                .as("la place est libérée après le calcul").isEqualTo(201);
    }

    private void verifierSaturation(HttpResponse<String> reponse) {
        assertThat(reponse.statusCode()).isEqualTo(503);
        assertThat(reponse.headers().firstValue("retry-after")).hasValue("2");
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo("SERVICE_SATURE");
        assertThat(corps.get("status").asInt()).isEqualTo(503);
        assertThat(corps.get("reessayerDansSecondes").asInt()).isEqualTo(2);
        assertThat(reponse.body()).doesNotContain("un-mot-de-passe-12");
    }

    private HttpResponse<String> connecter(ApiHttp.Jeton jeton) {
        try {
            return api.requete("POST", "/api/connexion", Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie(),
                    "X-XSRF-TOKEN", jeton.entete()), "application/json", CORPS.formatted("Alice"));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
