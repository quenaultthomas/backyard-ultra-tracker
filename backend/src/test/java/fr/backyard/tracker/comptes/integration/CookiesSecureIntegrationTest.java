package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CA8 (contrat 7.3 RG2) avec {@code COOKIES_SECURE=true} : en HTTP pur et sans {@code X-Forwarded-Proto}, JSESSIONID,
 * XSRF-TOKEN et le cookie de session effacé à la déconnexion portent {@code Secure} ; une valeur non booléenne fait
 * échouer le démarrage.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.secure=true"})
@Testcontainers
class CookiesSecureIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    ApiHttp api;

    @BeforeEach
    void preparer() throws Exception {
        new JdbcTemplate(dataSource).update("delete from compte");
        registre.vider();
        api = new ApiHttp(port);
        assertThat(api.postJson("/api/comptes", ApiHttp.CORPS_VALIDE.formatted("Alice"), api.jetonValide())
                .statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("CA8 : COOKIES_SECURE=true en HTTP sans X-Forwarded-Proto : XSRF-TOKEN, JSESSIONID et le cookie effacé à la déconnexion sont Secure, HttpOnly (hors XSRF-TOKEN) et SameSite=Lax")
    void ca8_cookies_secure_sans_x_forwarded_proto() throws Exception {
        HttpResponse<String> csrf = api.get("/api/csrf");
        List<String> cookiesCsrf = csrf.headers().allValues("set-cookie");
        String xsrf = cookie(cookiesCsrf, "XSRF-TOKEN");
        assertThat(xsrf).containsIgnoringCase("; Secure").containsIgnoringCase("SameSite=Lax")
                .doesNotContainIgnoringCase("HttpOnly");
        String jeton = ApiHttp.valeurCookieXsrf(csrf);

        HttpResponse<String> connexion = api.requete("POST", "/api/connexion",
                Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton), "application/json",
                ApiHttp.CORPS_VALIDE.formatted("Alice"));
        assertThat(connexion.statusCode()).as(connexion.body()).isEqualTo(200);
        String session = cookie(connexion.headers().allValues("set-cookie"), "JSESSIONID");
        assertThat(session).containsIgnoringCase("; Secure").containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax");
        assertThat(connexion.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN="))
                .toList()).allSatisfy(c -> assertThat(c).containsIgnoringCase("; Secure"));

        String idSession = ApiHttp.valeurCookie(connexion, "JSESSIONID");
        HttpResponse<String> deconnexion = api.requete("POST", "/api/deconnexion",
                Map.of("Cookie", "JSESSIONID=" + idSession + "; XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton), null,
                null);
        assertThat(deconnexion.statusCode()).isEqualTo(204);
        String efface = cookie(deconnexion.headers().allValues("set-cookie"), "JSESSIONID");
        assertThat(efface).contains("Max-Age=0").containsIgnoringCase("; Secure").containsIgnoringCase("HttpOnly")
                .containsIgnoringCase("SameSite=Lax");
    }

    @Test
    @DisplayName("CA8 : COOKIES_SECURE=oui fait échouer le démarrage avec un message citant la propriété")
    void ca8_valeur_non_booleenne_refusee_au_demarrage() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(BackyardUltraTrackerApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "spring.main.banner-mode=off",
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword())
                .run("--server.servlet.session.cookie.secure=oui").close())
                .satisfies(erreur -> {
                    StringBuilder messages = new StringBuilder();
                    for (Throwable t = erreur; t != null; t = t.getCause()) {
                        messages.append(t.getMessage()).append(" | ");
                    }
                    assertThat(messages.toString()).contains("server.servlet.session.cookie.secure");
                });
    }

    private static String cookie(List<String> cookies, String nom) {
        return cookies.stream().filter(c -> c.startsWith(nom + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("Aucun cookie " + nom + " dans " + cookies));
    }
}
