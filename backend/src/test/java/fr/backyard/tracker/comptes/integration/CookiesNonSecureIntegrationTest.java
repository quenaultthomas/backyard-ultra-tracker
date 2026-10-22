package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
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

/** CA8 (contrat 7.3 RG2) avec {@code COOKIES_SECURE=false} : X-Forwarded-Proto: https ne rend aucun cookie Secure. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.session.cookie.secure=false"})
@Testcontainers
class CookiesNonSecureIntegrationTest {

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
    @DisplayName("CA8 : COOKIES_SECURE=false avec X-Forwarded-Proto: https : ni XSRF-TOKEN, ni JSESSIONID, ni le cookie effacé ne sont Secure")
    void ca8_aucun_cookie_secure_malgre_x_forwarded_proto() throws Exception {
        Map<String, String> proxy = Map.of("X-Forwarded-Proto", "https");
        HttpResponse<String> csrf = api.requete("GET", "/api/csrf", proxy, null, null);
        String jeton = ApiHttp.valeurCookieXsrf(csrf);
        HttpResponse<String> connexion = api.requete("POST", "/api/connexion",
                Map.of("Cookie", "XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton, "X-Forwarded-Proto", "https"),
                "application/json", ApiHttp.CORPS_VALIDE.formatted("Alice"));
        assertThat(connexion.statusCode()).as(connexion.body()).isEqualTo(200);
        String idSession = ApiHttp.valeurCookie(connexion, "JSESSIONID");
        HttpResponse<String> deconnexion = api.requete("POST", "/api/deconnexion",
                Map.of("Cookie", "JSESSIONID=" + idSession + "; XSRF-TOKEN=" + jeton, "X-XSRF-TOKEN", jeton,
                        "X-Forwarded-Proto", "https"), null, null);
        assertThat(deconnexion.statusCode()).isEqualTo(204);

        List<String> cookies = new java.util.ArrayList<>(csrf.headers().allValues("set-cookie"));
        cookies.addAll(connexion.headers().allValues("set-cookie"));
        cookies.addAll(deconnexion.headers().allValues("set-cookie"));
        assertThat(cookies).anyMatch(c -> c.startsWith("XSRF-TOKEN=")).anyMatch(c -> c.startsWith("JSESSIONID="));
        assertThat(cookies).noneMatch(c -> c.toLowerCase().contains("secure"));
        assertThat(cookies).filteredOn(c -> c.startsWith("JSESSIONID=")).allSatisfy(
                c -> assertThat(c).containsIgnoringCase("SameSite=Lax").containsIgnoringCase("HttpOnly"));
        assertThat(cookies).filteredOn(c -> c.startsWith("JSESSIONID=")).anyMatch(c -> c.contains("Max-Age=0"));
    }
}
