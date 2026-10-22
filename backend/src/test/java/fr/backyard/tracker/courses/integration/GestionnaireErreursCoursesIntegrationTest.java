package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.courses.application.ListerCoursesPubliques;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.integration.ClientHttp.Session;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CA12 (contrat 7.3 RG8) : toute exception métier de {@code courses} levée par un contrôleur de
 * {@code courses.exposition} devient son ProblemDetail, y compris pour les contrôleurs publics et un contrôleur ajouté
 * plus tard (ici un contrôleur de test), jamais un 500 ERREUR_INTERNE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@ActiveProfiles("test-gestionnaire")
@Testcontainers
class GestionnaireErreursCoursesIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    InitialiserAdminMaster initialiserAdminMaster;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    @MockitoBean
    ListerCoursesPubliques listerCoursesPubliques;

    final JsonMapper json = JsonMapper.builder().build();
    ClientHttp api;
    Session patron;

    @BeforeEach
    void preparer() throws Exception {
        new JdbcTemplate(dataSource).update("delete from compte");
        registre.vider();
        initialiserAdminMaster.executer("Patron", "mot-de-passe-patron-1");
        api = new ClientHttp(port);
        patron = api.ouvrir("Patron", "mot-de-passe-patron-1");
    }

    @Test
    @DisplayName("CA12 : un contrôleur de courses.exposition ajouté hors liste lève CourseIntrouvableException : 404 COURSE_INTROUVABLE, pas 500")
    void ca12_controleur_ajoute_couvert_par_le_gestionnaire() throws Exception {
        HttpResponse<String> reponse = api.requete("GET", "/api/test-gestionnaire/introuvable", patron.enteteLecture(),
                null, null);

        verifierCourseIntrouvable(reponse);
    }

    @Test
    @DisplayName("CA12 : la même exception levée via le contrôleur public de la liste des courses donne le même 404 COURSE_INTROUVABLE")
    void ca12_controleur_public_couvert_par_le_gestionnaire() throws Exception {
        when(listerCoursesPubliques.executer()).thenThrow(new CourseIntrouvableException());

        HttpResponse<String> reponse = api.requete("GET", "/api/courses", Map.of(), null, null);

        verifierCourseIntrouvable(reponse);
    }

    @Test
    @DisplayName("CA12 : suivi d'une course inconnue : 404 COURSE_INTROUVABLE ; logo d'une course inconnue : 404 (gestionnaire du logo inchangé)")
    void ca12_suivi_et_logo_d_une_course_inconnue() throws Exception {
        UUID inconnue = UUID.randomUUID();

        verifierCourseIntrouvable(api.requete("GET", "/api/courses/" + inconnue + "/suivi", Map.of(), null, null));

        HttpResponse<String> logo = api.requete("GET", "/api/courses/" + inconnue + "/logo", Map.of(), null, null);
        assertThat(logo.statusCode()).isEqualTo(404);
        assertThat(logo.body()).doesNotContain("ERREUR_INTERNE");
    }

    private void verifierCourseIntrouvable(HttpResponse<String> reponse) {
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(404);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).contains("application/problem+json"));
        JsonNode corps = json.readTree(reponse.body());
        assertThat(corps.get("code").asString()).isEqualTo("COURSE_INTROUVABLE");
        assertThat(corps.get("status").asInt()).isEqualTo(404);
    }
}
