package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import fr.backyard.tracker.comptes.integration.OutilsPhoto.Session;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Socle des tests d'intégration de la photo de profil (10.1a). Les classes concrètes portent {@code @SpringBootTest},
 * le conteneur PostgreSQL et la propriété {@code backyard.photos.repertoire} (répertoire temporaire propre à la classe).
 * Comptes de référence : Patron (ADMIN_MASTER), Nadia (ADMIN), Léo (BENEVOLE), Coureur1 à Coureur3 (COUREUR).
 */
abstract class PhotoIntegrationBase {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CHEMIN_PHOTO = "/api/comptes/moi/photo";
    static final String CHEMIN_PHOTOS = "/api/photos/";
    static final String PROPRIETE_REPERTOIRE = "backyard.photos.repertoire";
    static final String INSERTION_COMPTE = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";
    static final String INSERTION_COURSE = "insert into course (id, nom, date_course, statut, "
            + "distance_boucle_metres, duree_boucle_minutes, denivele_positif_boucle_metres, "
            + "nombre_max_participants, nombre_max_boucles, demarree_le) values (?, ?, ?, ?, 400, 60, 0, 10, 5, ?)";

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

    final JsonMapper json = JsonMapper.builder().build();
    JdbcTemplate jdbc;
    OutilsPhoto api;
    String empreinte;
    UUID idPatron;
    UUID idNadia;
    UUID idLeo;
    UUID idCoureur1;
    UUID idCoureur2;
    UUID idCoureur3;
    Session patron;
    Session nadia;
    Session leo;
    Session coureur1;
    Session coureur2;
    Session coureur3;

    /** Répertoire des photos de la classe concrète. */
    abstract Path repertoire();

    @BeforeEach
    void preparerSocle() throws Exception {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from course");
        jdbc.update("delete from compte");
        registre.vider();
        OutilsPhoto.vider(repertoire());
        api = new OutilsPhoto(port);
        assertThat(initialiserAdminMaster.executer("Patron", MOT_DE_PASSE_PATRON))
                .isEqualTo(InitialiserAdminMaster.Resultat.CREE);
        empreinte = encodeur.encode(MOT_DE_PASSE);
        idPatron = jdbc.queryForObject("select id from compte where pseudo = 'Patron'", UUID.class);
        idNadia = insererCompte("Nadia", "ADMIN");
        idLeo = insererCompte("Léo", "BENEVOLE");
        idCoureur1 = insererCompte("Coureur1", "COUREUR");
        idCoureur2 = insererCompte("Coureur2", "COUREUR");
        idCoureur3 = insererCompte("Coureur3", "COUREUR");
        patron = api.ouvrir("Patron", MOT_DE_PASSE_PATRON);
        nadia = api.ouvrir("Nadia", MOT_DE_PASSE);
        leo = api.ouvrir("Léo", MOT_DE_PASSE);
        coureur1 = api.ouvrir("Coureur1", MOT_DE_PASSE);
        coureur2 = api.ouvrir("Coureur2", MOT_DE_PASSE);
        coureur3 = api.ouvrir("Coureur3", MOT_DE_PASSE);
    }

    // ---------------------------------------------------------------- données

    UUID insererCompte(String pseudo, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte, role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    UUID insererCourse(String nom, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION_COURSE, id, nom, Date.valueOf("2026-10-10"), statut,
                "EN_PREPARATION".equals(statut) ? null : Timestamp.from(Instant.parse("2026-10-10T08:00:00Z")));
        return id;
    }

    UUID inscrire(UUID course, UUID compte, String statut) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inscription (id, course_id, compte_id, dossard, jeton_qr, statut) "
                + "values (?, ?, ?, 1, ?, ?)", id, course, compte, UUID.randomUUID().toString(), statut);
        return id;
    }

    void affecter(UUID course, UUID benevole) {
        jdbc.update("insert into affectation_benevole (course_id, benevole_id) values (?, ?)", course, benevole);
    }

    /** Photo posée sans passer par l'API (Compte non coureur) : colonnes et fichier. Retourne le jeton. */
    String poserPhotoEnBase(UUID compte, byte[] octets) throws Exception {
        String jeton = UUID.randomUUID().toString().replace("-", "");
        Files.createDirectories(repertoire());
        Files.write(repertoire().resolve(jeton + ".jpg"), octets);
        jdbc.update("update compte set photo_jeton = ?, photo_format = 'JPEG' where id = ?", jeton, compte);
        return jeton;
    }

    /** Envoie une photo PNG valide par l'API et retourne son jeton (200 exigé). */
    String poserPhoto(Session session, byte[] octets) throws Exception {
        HttpResponse<byte[]> reponse = api.envoyerPhoto(session, octets);
        assertThat(reponse.statusCode()).isEqualTo(200);
        return OutilsPhoto.jetonDe(corps(reponse).get("photoUrl").asString());
    }

    Map<String, Object> colonnesPhoto(UUID compte) {
        return jdbc.queryForMap("select photo_jeton, photo_format from compte where id = ?", compte);
    }

    boolean existe(UUID compte) {
        return jdbc.queryForObject("select count(*) from compte where id = ?", Integer.class, compte) > 0;
    }

    // ---------------------------------------------------------------- lectures et assertions

    JsonNode corps(HttpResponse<byte[]> reponse) {
        return json.readTree(new String(reponse.body(), java.nio.charset.StandardCharsets.UTF_8));
    }

    String photoUrlMoi(Session session) throws Exception {
        HttpResponse<byte[]> moi = api.get("/api/comptes/moi", session);
        assertThat(moi.statusCode()).isEqualTo(200);
        JsonNode url = corps(moi).get("photoUrl");
        return url == null || url.isNull() ? null : url.asString();
    }

    /** ProblemDetail attendu : statut, code, application/problem+json. */
    void assertProbleme(HttpResponse<byte[]> reponse, int statut, String code) {
        assertThat(reponse.statusCode()).as(new String(reponse.body())).isEqualTo(statut);
        assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                c -> assertThat(c).startsWith("application/problem+json"));
        JsonNode probleme = corps(reponse);
        assertThat(probleme.get("code").asString()).isEqualTo(code);
        assertThat(probleme.get("status").asInt()).isEqualTo(statut);
    }

    /** Photo servie publiquement : statut 200 et octets identiques. */
    void assertServie(String jeton, byte[] octets) throws Exception {
        HttpResponse<byte[]> reponse = api.get(CHEMIN_PHOTOS + jeton);
        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.body()).isEqualTo(octets);
    }

    void assertIntrouvable(String jeton) throws Exception {
        assertProbleme(api.get(CHEMIN_PHOTOS + jeton), 404, "PHOTO_INTROUVABLE");
    }
}
