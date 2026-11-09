package fr.backyard.tracker.courses.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 10.2 (tableau de polaroïds, volet back) : CA7 à CA10 et CA12, serveur réel, requêtes sans
 * session. GET /api/courses/{id}/suivi : lignes de coureur en course et de vainqueur à 3 champs (dossard, pseudo,
 * photoUrl ou null), abandons inchangés ; GET /api/photos/{jeton} mis en cache 5 minutes. Le nombre de champs de
 * premier niveau est celui de la liste fermée en vigueur (22 depuis 7.1 ; la spec 10.2 en cite 14, valeur de 6.2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
@Import(CoursesIntegrationTest.HorlogeDeTestConfiguration.class)
class SuiviPublicPhotosIntegrationTest {

    static final Pattern URL_PHOTO = Pattern.compile("/api/photos/[0-9a-f]{32}");
    static final List<String> CHAMPS_COUREUR = List.of("dossard", "pseudo", "photoUrl");
    static final List<String> CHAMPS_ABANDON = List.of("dossard", "pseudo", "boucleAbandon", "motifAbandon");
    static final Path REPERTOIRE = repertoireTemporaire();

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

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    Environment environnement;

    DecorAbandon decor;
    ClientPhotos photos;
    String empreinte;
    JournalApplicatif journal;

    @BeforeEach
    void preparer() throws Exception {
        decor = new DecorAbandon(dataSource, encodeur, horloge, port);
        decor.preparer(initialiserAdminMaster, registre);
        photos = new ClientPhotos(port);
        empreinte = encodeur.encode(DecorAbandon.MOT_DE_PASSE);
        horloge.fixer(DecorAbandon.T0.plusSeconds(10));
    }

    @AfterEach
    void nettoyer() {
        if (journal != null) {
            journal.fermer();
            journal = null;
        }
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-suivi").resolve("photos");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    // ---------------------------------------------------------------- CA7

    @Test
    @DisplayName("CA7 : sans session, X EN_COURS (Bruno avec photo envoyée par l'API, Alice sans) : 200 no-store, aucun Set-Cookie, liste fermée de champs, lignes à 3 champs, photoUrl /api/photos/<32 hex> servie en 200 image/jpeg, null pour Alice, abandons à 4 champs, aucune fuite")
    void ca7_suivi_avec_photos() throws Exception {
        UUID x = courseEnCours("X");
        decor.insererInscription(x, decor.idAlice, 1, "EN_COURSE");
        decor.insererInscription(x, decor.idBruno, 2, "EN_COURSE");
        decor.insererInscription(x, decor.idTom, 3, "ABANDON");
        decor.jdbc.update("update inscription set motif_abandon = 'VOLONTAIRE', boucle_abandon = 1 where compte_id = ?",
                decor.idTom);
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 1)).statusCode()).isEqualTo(200);
        assertThat(photos.envoyer(decor.tom, ClientPhotos.jpeg(2000, 2)).statusCode()).isEqualTo(200);

        HttpResponse<String> reponse = suivi(x);

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
        JsonNode corps = decor.json.readTree(reponse.body());
        assertThat(corps.propertyNames()).containsExactlyInAnyOrderElementsOf(SuiviPublicCoureursIntegrationTest.CHAMPS);
        JsonNode enCourse = corps.get("coureursEnCourse");
        assertThat(enCourse).hasSize(2);
        for (JsonNode ligne : enCourse) {
            assertThat(ligne.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COUREUR);
        }
        assertThat(enCourse.get(0).get("dossard").asInt()).isEqualTo(1);
        assertThat(enCourse.get(0).get("pseudo").asString()).isEqualTo("Alice");
        assertThat(enCourse.get(0).get("photoUrl").isNull()).isTrue();
        assertThat(enCourse.get(1).get("pseudo").asString()).isEqualTo("Bruno");
        String url = enCourse.get(1).get("photoUrl").asString();
        assertThat(url).matches(URL_PHOTO);
        HttpResponse<byte[]> photo = photos.lire("GET", url, Map.of());
        assertThat(photo.statusCode()).isEqualTo(200);
        assertThat(photo.headers().firstValue("content-type")).hasValueSatisfying(
                t -> assertThat(t).startsWith("image/jpeg"));
        JsonNode abandon = corps.get("abandons").get(0);
        assertThat(abandon.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_ABANDON);
        assertThat(reponse.body()).doesNotContain("jetonQr", "compteId", "inscriptionId", "photoJeton", "photo_jeton",
                REPERTOIRE.toString(), decor.idBruno.toString(), decor.jeton(x.toString(), decor.idBruno),
                decor.jeton(x.toString(), decor.idAlice));
        assertThat(reponse.body()).doesNotContain(".jpg");
    }

    // ---------------------------------------------------------------- CA8

    @Test
    @DisplayName("CA8 : retrait de la photo : photoUrl null et ancienne URL 404 ; remplacement : nouvelle photoUrl différente ; suppression du Compte : « Coureur anonyme », photoUrl null, ancienne URL 404")
    void ca8_retrait_remplacement_et_suppression() throws Exception {
        UUID x = courseEnCours("X");
        decor.insererInscription(x, decor.idBruno, 2, "EN_COURSE");
        decor.insererInscription(x, decor.idChloe, 3, "EN_COURSE");
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 1)).statusCode()).isEqualTo(200);
        assertThat(photos.envoyer(decor.chloe, ClientPhotos.jpeg(2000, 2)).statusCode()).isEqualTo(200);
        String urlBruno = ligne(x, 2).get("photoUrl").asString();
        String urlChloe = ligne(x, 3).get("photoUrl").asString();
        assertThat(photos.lire("GET", urlBruno, Map.of()).statusCode()).isEqualTo(200);

        // retrait
        assertThat(photos.retirer(decor.bruno).statusCode()).isIn(200, 204);
        assertThat(ligne(x, 2).get("photoUrl").isNull()).isTrue();
        assertThat(photos.lire("GET", urlBruno, Map.of()).statusCode()).isEqualTo(404);

        // remplacement
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2500, 3)).statusCode()).isEqualTo(200);
        String nouvelle = ligne(x, 2).get("photoUrl").asString();
        assertThat(nouvelle).matches(URL_PHOTO).isNotEqualTo(urlBruno);
        assertThat(photos.lire("GET", nouvelle, Map.of()).statusCode()).isEqualTo(200);

        // suppression du Compte (3.6) : anonymisation
        assertThat(photos.supprimerCompte(decor.chloe, DecorAbandon.MOT_DE_PASSE).statusCode()).isEqualTo(204);
        JsonNode anonyme = ligne(x, 3);
        assertThat(anonyme.get("pseudo").asString()).isEqualTo("Coureur anonyme");
        assertThat(anonyme.get("photoUrl").isNull()).isTrue();
        assertThat(photos.lire("GET", urlChloe, Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("CA8 : Course TERMINEE : la ligne de vainqueurs porte la photoUrl du vainqueur (servie en 200), 3 champs")
    void ca8_vainqueur_avec_photo() throws Exception {
        UUID x = decor.insererCourse("Terminée", "TERMINEE");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), x);
        decor.insererInscription(x, decor.idBruno, 4, "VAINQUEUR");
        decor.insererInscription(x, decor.idAlice, 1, "VAINQUEUR");
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 4)).statusCode()).isEqualTo(200);

        JsonNode vainqueurs = corps(x).get("vainqueurs");

        assertThat(vainqueurs).hasSize(2);
        for (JsonNode ligne : vainqueurs) {
            assertThat(ligne.propertyNames()).containsExactlyInAnyOrderElementsOf(CHAMPS_COUREUR);
        }
        assertThat(vainqueurs.get(0).get("pseudo").asString()).isEqualTo("Alice");
        assertThat(vainqueurs.get(0).get("photoUrl").isNull()).isTrue();
        assertThat(vainqueurs.get(1).get("pseudo").asString()).isEqualTo("Bruno");
        String url = vainqueurs.get(1).get("photoUrl").asString();
        assertThat(url).matches(URL_PHOTO);
        assertThat(photos.lire("GET", url, Map.of()).statusCode()).isEqualTo(200);
        assertThat(corps(x).get("coureursEnCourse")).isEmpty();
    }

    // ---------------------------------------------------------------- CA9

    @Test
    @DisplayName("CA9 : 3 puis 60 coureurs en course avec photo : même nombre d'instructions SQL ; exactement 1 de plus qu'une Course sans coureur en course ni vainqueur ; instruction absente en EN_PREPARATION")
    void ca9_une_seule_instruction_pour_les_photos() throws Exception {
        UUID petite = courseEnCours("Petite");
        UUID grande = courseEnCours("Grande");
        UUID sansCoureur = courseEnCours("Sans coureur");
        UUID preparation = decor.insererCourse("Préparation", "EN_PREPARATION");
        for (int i = 1; i <= 3; i++) {
            decor.insererInscription(petite, compteAvecPhoto("A" + i), i, "EN_COURSE");
            inscrireAbandon(sansCoureur, compteAvecPhoto("S" + i), i);
            decor.insererInscription(preparation, compteAvecPhoto("C" + i), i, "EN_COURSE");
        }
        for (int i = 1; i <= 60; i++) {
            decor.insererInscription(grande, compteAvecPhoto("B" + i), i, "EN_COURSE");
        }
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(suivi(grande).statusCode()).isEqualTo(200);

        long pour3 = requetes(statistiques, petite);
        long pour60 = requetes(statistiques, grande);
        long sansCoureurs = requetes(statistiques, sansCoureur);
        long enPreparation = requetes(statistiques, preparation);

        assertThat(pour60).as("aucune requête par ligne").isEqualTo(pour3);
        assertThat(pour3 - sansCoureurs).as("seule la lecture des photos s'ajoute").isEqualTo(1);
        assertThat(sansCoureurs).as("abandons seuls : aucune instruction photo").isGreaterThan(enPreparation);
        assertThat(corps(grande).get("coureursEnCourse")).hasSize(60);
        assertThat(corps(grande).get("coureursEnCourse").get(59).get("photoUrl").asString()).matches(URL_PHOTO);
        assertThat(corps(preparation).get("coureursEnCourse")).isEmpty();
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : GET /api/photos/<jeton> : 200 public, max-age=300, ETag, nosniff, inline ; HEAD identique sans corps ; If-None-Match : 304 sans corps avec le même Cache-Control")
    void ca10_photo_mise_en_cache() throws Exception {
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 1)).statusCode()).isEqualTo(200);
        String url = photoUrlDeBruno();
        String jeton = url.substring("/api/photos/".length());

        HttpResponse<byte[]> reponse = photos.lire("GET", url, Map.of());

        assertThat(reponse.statusCode()).isEqualTo(200);
        assertThat(reponse.headers().firstValue("cache-control")).hasValue("public, max-age=300");
        assertThat(reponse.headers().firstValue("etag")).hasValue("\"" + jeton + "\"");
        assertThat(reponse.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(reponse.headers().firstValue("content-disposition")).hasValueSatisfying(
                d -> assertThat(d).startsWith("inline"));
        assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
        assertThat(reponse.body()).isNotEmpty();

        HttpResponse<byte[]> tete = photos.lire("HEAD", url, Map.of());
        assertThat(tete.statusCode()).isEqualTo(200);
        assertThat(tete.body()).isEmpty();
        assertThat(tete.headers().firstValue("cache-control")).hasValue("public, max-age=300");

        HttpResponse<byte[]> inchange = photos.lire("GET", url, Map.of("If-None-Match", "\"" + jeton + "\""));
        assertThat(inchange.statusCode()).isEqualTo(304);
        assertThat(inchange.body()).isEmpty();
        assertThat(inchange.headers().firstValue("cache-control")).hasValue("public, max-age=300");
        assertThat(inchange.headers().firstValue("etag")).hasValue("\"" + jeton + "\"");
    }

    @Test
    @DisplayName("CA10 : jeton inconnu, mal formé ou retiré : 404 PHOTO_INTROUVABLE ProblemDetail avec Cache-Control no-store (GET et HEAD)")
    void ca10_photo_introuvable_jamais_mise_en_cache() throws Exception {
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 1)).statusCode()).isEqualTo(200);
        String retiree = photoUrlDeBruno();
        assertThat(photos.retirer(decor.bruno).statusCode()).isIn(200, 204);

        for (String chemin : List.of("/api/photos/" + "0".repeat(32), "/api/photos/pas-un-jeton", retiree)) {
            HttpResponse<byte[]> reponse = photos.lire("GET", chemin, Map.of());
            assertThat(reponse.statusCode()).as(chemin).isEqualTo(404);
            assertThat(reponse.headers().firstValue("cache-control")).as(chemin).hasValueSatisfying(
                    c -> assertThat(c).contains("no-store"));
            assertThat(reponse.headers().firstValue("content-type")).hasValueSatisfying(
                    t -> assertThat(t).startsWith("application/problem+json"));
            JsonNode probleme = decor.json.readTree(new String(reponse.body(), StandardCharsets.UTF_8));
            assertThat(probleme.get("code").asString()).isEqualTo("PHOTO_INTROUVABLE");
            assertThat(probleme.get("status").asInt()).isEqualTo(404);
        }
        HttpResponse<byte[]> tete = photos.lire("HEAD", retiree, Map.of());
        assertThat(tete.statusCode()).isEqualTo(404);
        assertThat(tete.headers().firstValue("cache-control")).hasValueSatisfying(c -> assertThat(c).contains("no-store"));
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : après les appels des CA7 à CA10, le journal ne contient aucune ligne INFO ou supérieure avec un pseudo ou un jeton de photo ; le plafond des photos vaut 3000 par défaut")
    void ca12_journal_sans_pseudo_ni_jeton_et_defaut() throws Exception {
        assertThat(environnement.getProperty("backyard.limite-debit-photos.par-minute")).isEqualTo("3000");
        journal = JournalApplicatif.demarrer();
        UUID x = courseEnCours("X");
        decor.insererInscription(x, decor.idAlice, 1, "EN_COURSE");
        decor.insererInscription(x, decor.idBruno, 2, "EN_COURSE");
        assertThat(photos.envoyer(decor.bruno, ClientPhotos.jpeg(2000, 1)).statusCode()).isEqualTo(200);
        String url = ligne(x, 2).get("photoUrl").asString();
        String jeton = url.substring("/api/photos/".length());
        photos.lire("GET", url, Map.of());
        photos.lire("HEAD", url, Map.of());
        photos.lire("GET", url, Map.of("If-None-Match", "\"" + jeton + "\""));
        assertThat(photos.retirer(decor.bruno).statusCode()).isIn(200, 204);
        photos.lire("GET", url, Map.of());
        photos.lire("GET", "/api/photos/" + "1".repeat(32), Map.of());
        corps(x);

        List<String> fautives = new ArrayList<>();
        for (ILoggingEvent evenement : journal.evenementsApplicatifs()) {
            if (evenement.getLevel().isGreaterOrEqual(Level.INFO)) {
                String ligne = evenement.getFormattedMessage();
                if (ligne.contains("Alice") || ligne.contains("Bruno") || ligne.contains(jeton)
                        || ligne.contains("1".repeat(32))) {
                    fautives.add(ligne);
                }
            }
        }
        assertThat(fautives).isEmpty();
    }

    // ---------------------------------------------------------------- outils

    private String photoUrlDeBruno() throws Exception {
        UUID x = courseEnCours("Bruno");
        decor.insererInscription(x, decor.idBruno, 1, "EN_COURSE");
        return ligne(x, 1).get("photoUrl").asString();
    }

    private long requetes(Statistics statistiques, UUID course) throws Exception {
        avancer();
        long avant = statistiques.getPrepareStatementCount();
        assertThat(suivi(course).statusCode()).isEqualTo(200);
        return statistiques.getPrepareStatementCount() - avant;
    }

    /** Dépasse la durée du micro-cache du suivi (1 s) entre deux lectures. */
    private void avancer() {
        horloge.fixer(horloge.instant().plusSeconds(2));
    }

    private HttpResponse<String> suivi(UUID course) throws Exception {
        avancer();
        return decor.api.requete("GET", "/api/courses/" + course + "/suivi", Map.of(), null, null);
    }

    private JsonNode corps(UUID course) throws Exception {
        HttpResponse<String> reponse = suivi(course);
        assertThat(reponse.statusCode()).isEqualTo(200);
        return decor.json.readTree(reponse.body());
    }

    private JsonNode ligne(UUID course, int dossard) throws Exception {
        for (JsonNode ligne : corps(course).get("coureursEnCourse")) {
            if (ligne.get("dossard").asInt() == dossard) {
                return ligne;
            }
        }
        throw new AssertionError("Aucune ligne pour le dossard " + dossard);
    }

    private UUID courseEnCours(String nom) {
        UUID id = decor.insererCourse(nom, "EN_COURS");
        decor.jdbc.update("update course set demarree_le = ? where id = ?", Timestamp.from(DecorAbandon.T0), id);
        return id;
    }

    private void inscrireAbandon(UUID course, UUID compte, int dossard) {
        UUID id = decor.insererInscription(course, compte, dossard, "ABANDON");
        decor.jdbc.update("update inscription set motif_abandon = 'HORS_DELAI', boucle_abandon = 1 where id = ?", id);
    }

    /** Compte coureur avec un jeton de photo posé directement en base (le suivi ne lit pas le fichier). */
    private UUID compteAvecPhoto(String pseudo) {
        UUID id = UUID.randomUUID();
        decor.jdbc.update(DecorAbandon.INSERTION_COMPTE, id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte,
                "COUREUR", Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        decor.jdbc.update("update compte set photo_jeton = ?, photo_format = 'JPEG' where id = ?",
                UUID.randomUUID().toString().replace("-", ""), id);
        return id;
    }
}
