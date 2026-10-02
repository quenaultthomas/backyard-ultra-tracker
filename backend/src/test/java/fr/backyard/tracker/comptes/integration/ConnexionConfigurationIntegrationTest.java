package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.infrastructure.ConnexionConfiguration;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** CA17 (incrément 1.3) : paramétrage du blocage par backyard.connexion.* et refus des valeurs invalides. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.connexion.echecs-max=3", "backyard.connexion.blocage-secondes=60"})
@Testcontainers
class ConnexionConfigurationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    PolitiqueBlocage politique;

    @Autowired
    DataSource dataSource;

    @Autowired
    fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire registre;

    @BeforeEach
    void preparer() {
        registre.vider();
    }

    @Test
    @DisplayName("CA17 : avec echecs-max=3 et blocage-secondes=60, 3 échecs bloquent et le 4e appel donne 429 avec Retry-After entre 1 et 60")
    void ca17_parametres_pris_en_compte() throws Exception {
        assertThat(politique).isEqualTo(new PolitiqueBlocage(3, 60));
        ApiHttp api = new ApiHttp(port);

        for (int i = 0; i < 3; i++) {
            HttpResponse<String> reponse = api.postJson("/api/connexion",
                    ApiHttp.CORPS_VALIDE.formatted("Inconnu3"), api.jetonValide());
            assertThat(reponse.statusCode()).as("échec " + i).isEqualTo(401);
        }
        HttpResponse<String> bloquee = api.postJson("/api/connexion",
                ApiHttp.CORPS_VALIDE.formatted("Inconnu3"), api.jetonValide());

        assertThat(bloquee.statusCode()).isEqualTo(429);
        assertThat(bloquee.headers().firstValue("retry-after")).hasValueSatisfying(
                valeur -> assertThat(Integer.parseInt(valeur)).isBetween(1, 60));
    }

    @Test
    @DisplayName("CA17 : echecs-max=0 fait échouer le démarrage avec un message nommant le paramètre")
    void ca17_echecs_max_zero_refuse() {
        assertDemarrageEchoue("0", "60", "echecs-max", "echecsMax");
    }

    @Test
    @DisplayName("CA17 : blocage-secondes=0 fait échouer le démarrage avec un message nommant le paramètre")
    void ca17_blocage_secondes_zero_refuse() {
        assertDemarrageEchoue("5", "0", "blocage-secondes", "blocageSecondes");
    }

    @Test
    @DisplayName("CA17 : une valeur non entière (abc) fait échouer le démarrage avec un message nommant le paramètre")
    void ca17_valeur_non_entiere_refusee() {
        assertDemarrageEchoue("abc", "60", "echecs-max", "echecs-max");
        assertDemarrageEchoue("5", "abc", "blocage-secondes", "blocage-secondes");
    }

    @Test
    @DisplayName("CA17 : des valeurs valides (1 et 1) démarrent le contexte")
    void ca17_bornes_minimales_acceptees() {
        new ApplicationContextRunner().withUserConfiguration(ConnexionConfiguration.class)
                .withPropertyValues("backyard.connexion.echecs-max=1", "backyard.connexion.blocage-secondes=1")
                .run(contexte -> assertThat(contexte.getBean(PolitiqueBlocage.class))
                        .isEqualTo(new PolitiqueBlocage(1, 1)));
    }

    private void assertDemarrageEchoue(String echecsMax, String blocageSecondes, String propriete, String motCle) {
        new ApplicationContextRunner().withUserConfiguration(ConnexionConfiguration.class)
                .withPropertyValues("backyard.connexion.echecs-max=" + echecsMax,
                        "backyard.connexion.blocage-secondes=" + blocageSecondes)
                .run(contexte -> {
                    assertThat(contexte).hasFailed();
                    List<String> messages = new ArrayList<>();
                    for (Throwable t = contexte.getStartupFailure(); t != null; t = t.getCause()) {
                        messages.add(String.valueOf(t.getMessage()));
                    }
                    assertThat(String.join(" | ", messages)).contains(motCle).contains("1");
                    assertThat(propriete).isNotBlank();
                });
    }
}
