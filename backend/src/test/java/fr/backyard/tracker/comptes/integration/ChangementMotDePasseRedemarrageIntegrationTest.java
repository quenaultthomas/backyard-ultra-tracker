package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contrat de l'incrément 1.6b, CA17 : après changement du mot de passe de l'admin master, un redémarrage de
 * l'application avec l'ancienne valeur de ADMIN_MASTER_MOT_DE_PASSE laisse le nouveau mot de passe valide.
 */
@Testcontainers
class ChangementMotDePasseRedemarrageIntegrationTest {

    static final String ANCIEN = "mot-de-passe-patron-1";
    static final String NOUVEAU = "patron-nouveau-mdp-1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    /** L'arrêt d'une application retire le pont JUL : on le remet pour les autres tests du module. */
    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    @Test
    @DisplayName("CA17 : redémarrage avec l'ancienne ADMIN_MASTER_MOT_DE_PASSE, le nouveau mot de passe de Patron reste valide et l'ancien est refusé")
    void ca17_redemarrage_conserve_le_nouveau_mot_de_passe() throws Exception {
        try (ConfigurableApplicationContext premier = demarrer()) {
            ApiHttp api = new ApiHttp(port(premier));
            Jeton jeton = api.jetonValide();
            HttpResponse<String> connexion = api.postJson("/api/connexion", corps(ANCIEN), jeton);
            assertThat(connexion.statusCode()).isEqualTo(200);
            String session = ApiHttp.valeurCookie(connexion, "JSESSIONID");
            HttpResponse<String> changement = api.requete("PUT", "/api/comptes/moi/mot-de-passe",
                    Map.of("Cookie", "JSESSIONID=" + session + "; XSRF-TOKEN=" + jeton.cookie(),
                            "X-XSRF-TOKEN", jeton.entete()), "application/json",
                    "{\"motDePasseActuel\":\"" + ANCIEN + "\",\"nouveauMotDePasse\":\"" + NOUVEAU + "\"}");
            assertThat(changement.statusCode()).isEqualTo(204);
        }
        String empreinte = empreinte();

        try (ConfigurableApplicationContext second = demarrer()) {
            ApiHttp api = new ApiHttp(port(second));
            assertThat(empreinte()).isEqualTo(empreinte);
            assertThat(api.postJson("/api/connexion", corps(NOUVEAU), api.jetonValide()).statusCode()).isEqualTo(200);
            HttpResponse<String> ancien = api.postJson("/api/connexion", corps(ANCIEN), api.jetonValide());
            assertThat(ancien.statusCode()).isEqualTo(401);
        }
    }

    private String empreinte() {
        DriverManagerDataSource source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return new JdbcTemplate(source).queryForObject(
                "select empreinte_mot_de_passe from compte where role = 'ADMIN_MASTER'", String.class);
    }

    private static String corps(String motDePasse) {
        return "{\"pseudo\":\"Patron\",\"motDePasse\":\"" + motDePasse + "\"}";
    }

    private ConfigurableApplicationContext demarrer() {
        return new SpringApplicationBuilder(BackyardUltraTrackerApplication.class).run(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0",
                "--backyard.admin-master.pseudo=Patron",
                "--backyard.admin-master.mot-de-passe=" + ANCIEN).toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext contexte) {
        return Integer.parseInt(contexte.getEnvironment().getProperty("local.server.port"));
    }
}
