package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Correctif audit jalon 10 : CA14 (procédure d'urgence de retrait d'une photo), CA17 (JSON du suivi inchangé quand
 * l'adresse est construite dans l'exposition) et CA18 (la route publiée par le suivi est servie par le contrôleur de
 * lecture et comptée par la limite de débit des photos, plafond réglé à 2 par minute).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "backyard.limite-debit-public.par-minute=1200", "backyard.limite-debit-photos.par-minute=2"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuiviPhotoAdresseIntegrationTest {

    static final Pattern URL_PHOTO = Pattern.compile("/api/photos/[0-9a-f]{32}");
    static final Path REPERTOIRE = repertoireTemporaire();
    static final Instant MINUTE_10H00 = Instant.parse("2026-10-21T10:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add("backyard.photos.repertoire", REPERTOIRE::toString);
    }

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

    DecorAbandon decor;
    ClientPhotos photos;
    UUID course;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
        photos = new ClientPhotos(port);
        course = decor.insererCourse("X", "EN_COURS");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), course);
        decor.insererInscription(course, decor.idAlice, 1, "EN_COURSE");
        decor.insererInscription(course, decor.idBruno, 2, "EN_COURSE");
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 1)).statusCode()).isEqualTo(200);
        // minute propre à chaque test : les compteurs de photos d'une minute précédente ne comptent plus
        horloge.fixer(MINUTE_10H00.plusSeconds(60L * Math.floorMod(System.nanoTime(), 100_000L)).plusSeconds(10));
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-adresse").resolve("photos");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    private HttpResponse<String> suivi() throws Exception {
        horloge.fixer(horloge.instant().plusSeconds(2));
        return decor.api.requete("GET", "/api/courses/" + course + "/suivi", Map.of(), null, null);
    }

    private JsonNode ligne(String pseudo) throws Exception {
        HttpResponse<String> reponse = suivi();
        assertThat(reponse.statusCode()).isEqualTo(200);
        for (JsonNode ligne : decor.json.readTree(reponse.body()).get("coureursEnCourse")) {
            if (pseudo.equals(ligne.get("pseudo").asString())) {
                return ligne;
            }
        }
        throw new AssertionError("Aucune ligne pour " + pseudo);
    }

    @Test
    @DisplayName("CA17 : sans session, suivi de X EN_COURS : 200 no-store, aucun Set-Cookie, 22 champs de premier niveau, lignes à 3 champs ; Karim a photoUrl /api/photos/<32 hex minuscules>, Alice null ; aucune fuite de jetonPhoto, jeton QR ou identifiant de Compte")
    void ca17_json_du_suivi_inchange() throws Exception {
        HttpResponse<String> reponse = suivi();

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).hasSize(22)
                .containsExactlyInAnyOrderElementsOf(SuiviPublicCoureursIntegrationTest.CHAMPS);
        JsonNode enCourse = corps.get("coureursEnCourse");
        assertThat(enCourse).hasSize(2);
        for (JsonNode ligne : enCourse) {
            assertThat(ligne.propertyNames()).containsExactlyInAnyOrder("dossard", "pseudo", "photoUrl");
        }
        assertThat(enCourse.get(0).get("pseudo").asString()).isEqualTo("Alice");
        assertThat(enCourse.get(0).get("photoUrl").isNull()).isTrue();
        assertThat(enCourse.get(1).get("pseudo").asString()).isEqualTo("Bruno");
        assertThat(enCourse.get(1).get("photoUrl").asString()).matches(URL_PHOTO);
        assertThat(reponse.body()).doesNotContain("jetonPhoto", "photoJeton", "jetonQr", decor.idBruno.toString(),
                decor.idAlice.toString(), decor.jeton(course.toString(), decor.idBruno));
    }

    @Test
    @DisplayName("CA18 : la photoUrl de Karim lue dans le suivi est servie en 200 image/jpeg par le contrôleur de lecture, comptée par la limite des photos (plafond 2 : le 3e appel répond 429) ; GET /api/comptes/moi de Karim donne la même photoUrl")
    void ca18_la_route_publiee_est_servie_et_comptee() throws Exception {
        String url = ligne("Bruno").get("photoUrl").asString();

        HttpResponse<byte[]> premier = photos.lire("GET", url, Map.of());

        assertThat(premier.statusCode()).isEqualTo(200);
        assertThat(premier.headers().firstValue("content-type")).hasValueSatisfying(
                t -> assertThat(t).startsWith("image/jpeg"));
        assertThat(photos.lire("GET", url, Map.of()).statusCode()).isEqualTo(200);
        assertThat(photos.lire("GET", url, Map.of()).statusCode()).isEqualTo(429);

        HttpResponse<byte[]> moi = photos.lire("GET", "/api/comptes/moi", decor.bruno.enteteLecture());
        assertThat(moi.statusCode()).isEqualTo(200);
        assertThat(decor.json.readTree(new String(moi.body(), java.nio.charset.StandardCharsets.UTF_8))
                .get("photoUrl").asString()).isEqualTo(url);
    }

    @Test
    @DisplayName("CA14 : l'update de la procédure d'urgence (les deux colonnes à null) respecte ck_compte_photo_coherente : l'ancienne adresse répond 404 sans autre action et le suivi donne photoUrl null au même pseudo et au même dossard ; mettre une seule colonne à null est refusé par la base")
    void ca14_procedure_d_urgence_de_retrait() throws Exception {
        JsonNode avant = ligne("Bruno");
        String url = avant.get("photoUrl").asString();
        assertThat(photos.lire("GET", url, Map.of()).statusCode()).isEqualTo(200);

        assertThatThrownBy(() -> decor.jdbc.update("update compte set photo_jeton = null where pseudo = 'Bruno'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> decor.jdbc.update("update compte set photo_format = null where pseudo = 'Bruno'"))
                .isInstanceOf(DataIntegrityViolationException.class);

        int modifiees = decor.jdbc.update(
                "update compte set photo_jeton = null, photo_format = null where pseudo = 'Bruno'");

        assertThat(modifiees).isEqualTo(1);
        assertThat(photos.lire("GET", url, Map.of()).statusCode()).isEqualTo(404);
        JsonNode apres = ligne("Bruno");
        assertThat(apres.get("photoUrl").isNull()).isTrue();
        assertThat(apres.get("dossard").asInt()).isEqualTo(avant.get("dossard").asInt()).isEqualTo(2);
    }
}
