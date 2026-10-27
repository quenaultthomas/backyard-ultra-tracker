package fr.backyard.tracker.comptes.integration;

import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.MOT_DE_PASSE;
import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.lireCorps;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.PlafondCreationsCompteParAdresse;
import fr.backyard.tracker.comptes.infrastructure.RegistreCreationsCompteParAdresseEnMemoire;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CA17 (incrément 8.5) : non-régression avec le socle de tests (seuil à 1000, valeurs de l'{@code application.properties}
 * de test, aucune surcharge) : la création de Comptes d'affilée depuis une même adresse n'est jamais limitée et les
 * contrats existants de {@code POST /api/comptes} sont intacts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CreationCompteSocleIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    RegistreCreationsCompteParAdresseEnMemoire registreCreations;

    @Autowired
    ApplicationContext contexte;

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
    @DisplayName("CA17 : le socle de test relève le seuil à 1000 par 60 s")
    void ca17_le_socle_releve_le_seuil() {
        assertThat(contexte.getBean(PlafondCreationsCompteParAdresse.class))
                .isEqualTo(new PlafondCreationsCompteParAdresse(1000, 60));
    }

    @Test
    @DisplayName("CA17 : 30 Comptes coureur créés d'affilée depuis la même adresse : 30 fois 201 ; 400, 403 et 409 inchangés")
    void ca17_creations_d_affilee_et_contrats_existants() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(client.creerAvecUnPseudoNeuf(null).statusCode()).as("création " + (i + 1)).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("select count(*) from compte", Integer.class)).isEqualTo(30);

        String pris = ClientCreationCompte.pseudoUnique();
        assertThat(client.creer(null, pris).statusCode()).isEqualTo(201);
        HttpResponse<String> conflit = client.creer(null, pris);
        assertThat(conflit.statusCode()).isEqualTo(409);
        assertThat(lireCorps(conflit).get("code").asString()).isEqualTo("PSEUDO_DEJA_UTILISE");

        HttpResponse<String> invalide = client.creer(null, "", "court");
        assertThat(invalide.statusCode()).isEqualTo(400);
        assertThat(lireCorps(invalide).get("code").asString()).isEqualTo("VALIDATION_ECHOUEE");

        HttpResponse<String> sansJeton = client.envoyer(null, "{\"pseudo\":\"Nouveau\",\"motDePasse\":\""
                + MOT_DE_PASSE + "\"}", null);
        assertThat(sansJeton.statusCode()).isEqualTo(403);
        assertThat(lireCorps(sansJeton).get("code").asString()).isEqualTo("CSRF_INVALIDE");
    }
}
