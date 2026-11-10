package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.infrastructure.PhotosFichiersAdapter;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Correctif audit jalon 10, RG3 (CA5 et CA6) : l'ETag est évalué avant la lecture du fichier. Espion sur le stockage de
 * fichiers pour compter les lectures, statistiques Hibernate pour compter les instructions SQL (par différence).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
class PhotoLectureEtagIntegrationTest extends PhotoIntegrationBase {

    static final Path REPERTOIRE = repertoireTemporaire();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add(PROPRIETE_REPERTOIRE, REPERTOIRE::toString);
    }

    @MockitoSpyBean
    PhotosFichiersAdapter stockage;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    JournalApplicatif journal;

    @Override
    Path repertoire() {
        return REPERTOIRE;
    }

    @AfterEach
    void arreterJournal() {
        if (journal != null) {
            journal.fermer();
            journal = null;
        }
    }

    private static Path repertoireTemporaire() {
        try {
            return Files.createTempDirectory("photos-it-etag").resolve("photos");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    private Statistics statistiques() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** Photo JPEG de Karim (ici Coureur1) envoyée par l'API ; retourne le jeton. */
    private String photoDeKarim() throws Exception {
        assertThat(api.envoyerPhoto(coureur1, OutilsPhoto.jpeg(2000), "photo.jpg", "image/jpeg").statusCode())
                .isEqualTo(200);
        return OutilsPhoto.jetonDe(photoUrlMoi(coureur1));
    }

    /** Exécute la requête et rend la réponse, le nombre d'instructions SQL et le nombre de lectures de fichier. */
    private Mesure mesurer(String methode, String chemin, Map<String, String> entetes) throws Exception {
        clearInvocations(stockage);
        long avant = statistiques().getPrepareStatementCount();
        HttpResponse<byte[]> reponse = api.requete(methode, chemin, entetes, null, null);
        long sql = statistiques().getPrepareStatementCount() - avant;
        int lectures = (int) org.mockito.Mockito.mockingDetails(stockage).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("lire")).count();
        return new Mesure(reponse, sql, lectures);
    }

    private record Mesure(HttpResponse<byte[]> reponse, long sql, int lectures) {
    }

    // ---------------------------------------------------------------- CA5

    @Test
    @DisplayName("CA5 : GET sans If-None-Match : 200, 1 instruction SQL, 1 lecture de fichier")
    void ca5_get_sans_etag_lit_le_fichier_une_fois() throws Exception {
        String jeton = photoDeKarim();

        Mesure mesure = mesurer("GET", CHEMIN_PHOTOS + jeton, Map.of());

        assertThat(mesure.reponse().statusCode()).isEqualTo(200);
        assertThat(mesure.reponse().body()).hasSize(2000);
        assertThat(mesure.sql()).isEqualTo(1);
        assertThat(mesure.lectures()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 : If-None-Match \"jeton\", W/\"jeton\", * puis liste \"autre\", \"jeton\" : 304 sans corps, ETag et Cache-Control, 1 instruction SQL et 0 lecture de fichier")
    void ca5_etag_correspondant_donne_304_sans_lecture() throws Exception {
        String jeton = photoDeKarim();
        String etag = "\"" + jeton + "\"";

        List<String> ecarts = new java.util.ArrayList<>();
        for (String valeur : List.of(etag, "W/" + etag, "*", "\"" + "0".repeat(32) + "\", " + etag)) {
            Mesure mesure = mesurer("GET", CHEMIN_PHOTOS + jeton, Map.of("If-None-Match", valeur));

            if (mesure.reponse().statusCode() != 304 || mesure.reponse().body().length != 0
                    || !mesure.reponse().headers().firstValue("etag").equals(java.util.Optional.of(etag))
                    || !mesure.reponse().headers().firstValue("cache-control")
                            .equals(java.util.Optional.of("public, max-age=300"))
                    || mesure.sql() != 1 || mesure.lectures() != 0) {
                ecarts.add("If-None-Match: " + valeur + " -> " + mesure.reponse().statusCode() + ", SQL "
                        + mesure.sql() + ", lectures " + mesure.lectures());
            }
        }
        assertThat(ecarts).as("valeurs qui ne donnent pas un 304 sans lecture").isEmpty();
        verify(stockage, never()).lire(any(), any());
    }

    @Test
    @DisplayName("CA5 : HEAD avec ETag correspondant : 304, 0 lecture de fichier ; If-None-Match non correspondant : 200 avec lecture")
    void ca5_head_avec_etag_correspondant_sans_lecture() throws Exception {
        String jeton = photoDeKarim();

        Mesure mesure = mesurer("HEAD", CHEMIN_PHOTOS + jeton, Map.of("If-None-Match", "\"" + jeton + "\""));

        assertThat(mesure.reponse().statusCode()).isEqualTo(304);
        assertThat(mesure.reponse().body()).isEmpty();
        assertThat(mesure.lectures()).isZero();
        assertThat(mesure.sql()).isEqualTo(1);

        Mesure autre = mesurer("GET", CHEMIN_PHOTOS + jeton, Map.of("If-None-Match", "\"" + "0".repeat(32) + "\""));
        assertThat(autre.reponse().statusCode()).isEqualTo(200);
        assertThat(autre.lectures()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 : jeton mal formé : 404, 0 SQL, 0 lecture ; jeton inconnu de 32 hexadécimaux : 404, 1 SQL, 0 lecture")
    void ca5_404_sans_lecture_de_fichier() throws Exception {
        photoDeKarim();

        Mesure malForme = mesurer("GET", CHEMIN_PHOTOS + "pas-un-jeton", Map.of());
        assertProbleme(malForme.reponse(), 404, "PHOTO_INTROUVABLE");
        assertThat(malForme.sql()).isZero();
        assertThat(malForme.lectures()).isZero();

        Mesure inconnu = mesurer("GET", CHEMIN_PHOTOS + "a".repeat(32), Map.of());
        assertProbleme(inconnu.reponse(), 404, "PHOTO_INTROUVABLE");
        assertThat(inconnu.sql()).isEqualTo(1);
        assertThat(inconnu.lectures()).isZero();
    }

    // ---------------------------------------------------------------- CA6

    @Test
    @DisplayName("CA6 : photo retirée puis GET avec If-None-Match correspondant : 404 PHOTO_INTROUVABLE no-store, jamais 304 ; photo remplacée : l'ancien jeton avec l'ancien ETag donne 404")
    void ca6_photo_retiree_ou_remplacee_donne_404_meme_avec_etag() throws Exception {
        String jeton = photoDeKarim();
        String etag = "\"" + jeton + "\"";
        assertThat(api.retirerPhoto(coureur1).statusCode()).isEqualTo(204);

        HttpResponse<byte[]> retiree = api.requete("GET", CHEMIN_PHOTOS + jeton, Map.of("If-None-Match", etag), null,
                null);

        assertProbleme(retiree, 404, "PHOTO_INTROUVABLE");
        assertThat(retiree.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));

        String ancien = photoDeKarim();
        String nouveau = poserPhotoRemplacante();
        assertThat(nouveau).isNotEqualTo(ancien);
        HttpResponse<byte[]> remplacee = api.requete("GET", CHEMIN_PHOTOS + ancien,
                Map.of("If-None-Match", "\"" + ancien + "\""), null, null);
        assertProbleme(remplacee, 404, "PHOTO_INTROUVABLE");
        assertThat(api.requete("GET", CHEMIN_PHOTOS + nouveau, Map.of("If-None-Match", "\"" + nouveau + "\""), null,
                null).statusCode()).isEqualTo(304);
    }

    @Test
    @DisplayName("CA6 : fichier supprimé du volume sans toucher la base : GET sans ETag = 404 + ligne ERROR sans jeton (2 instructions SQL, relecture propre à ce chemin) ; GET avec ETag correspondant = 304 (1 instruction SQL) ; jamais de 500")
    void ca6_fichier_manquant() throws Exception {
        String jeton = photoDeKarim();
        Files.delete(REPERTOIRE.resolve(jeton + ".jpg"));
        journal = JournalApplicatif.demarrer();
        int marque = journal.marque();

        Mesure sansEtag = mesurer("GET", CHEMIN_PHOTOS + jeton, Map.of());

        assertProbleme(sansEtag.reponse(), 404, "PHOTO_INTROUVABLE");
        assertThat(sansEtag.reponse().headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        assertThat(sansEtag.sql()).as("résolution + relecture du Compte pour le journal").isEqualTo(2);
        List<ILoggingEvent> erreurs = journal.applicatifsDepuis(marque).stream()
                .filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(erreurs).hasSize(1);
        assertThat(erreurs.get(0).getFormattedMessage()).startsWith("Fichier de photo introuvable")
                .doesNotContain(jeton);
        assertThat(journal.messagesEtExceptions()).noneSatisfy(m -> assertThat(m).contains(jeton));

        Mesure avecEtag = mesurer("GET", CHEMIN_PHOTOS + jeton, Map.of("If-None-Match", "\"" + jeton + "\""));

        assertThat(avecEtag.reponse().statusCode()).isEqualTo(304);
        assertThat(avecEtag.sql()).isEqualTo(1);
        assertThat(avecEtag.lectures()).isZero();
    }

    @Test
    @DisplayName("CA6 : en-têtes des 200, 304 et 404 inchangés (type, nosniff, inline, Cache-Control, ETag, aucun Set-Cookie)")
    void ca6_en_tetes_inchanges() throws Exception {
        String jeton = photoDeKarim();
        String etag = "\"" + jeton + "\"";

        HttpResponse<byte[]> ok = api.get(CHEMIN_PHOTOS + jeton);
        HttpResponse<byte[]> inchange = api.requete("GET", CHEMIN_PHOTOS + jeton, Map.of("If-None-Match", etag), null,
                null);
        HttpResponse<byte[]> absent = api.get(CHEMIN_PHOTOS + "b".repeat(32));

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("content-type")).hasValue("image/jpeg");
        assertThat(ok.headers().firstValue("x-content-type-options")).hasValue("nosniff");
        assertThat(ok.headers().firstValue("content-disposition")).hasValueSatisfying(
                d -> assertThat(d).startsWith("inline"));
        assertThat(ok.headers().firstValue("cache-control")).hasValue("public, max-age=300");
        assertThat(ok.headers().firstValue("etag")).hasValue(etag);
        assertThat(inchange.statusCode()).isEqualTo(304);
        assertThat(inchange.headers().firstValue("cache-control")).hasValue("public, max-age=300");
        assertThat(inchange.headers().firstValue("etag")).hasValue(etag);
        assertThat(absent.statusCode()).isEqualTo(404);
        assertThat(absent.headers().firstValue("cache-control")).hasValueSatisfying(
                c -> assertThat(c).contains("no-store"));
        for (HttpResponse<byte[]> reponse : List.of(ok, inchange, absent)) {
            assertThat(reponse.headers().allValues("set-cookie")).isEmpty();
        }
        verify(stockage, times(1)).lire(any(), any());
    }

    private String poserPhotoRemplacante() throws Exception {
        assertThat(api.envoyerPhoto(coureur1, OutilsPhoto.jpeg(2500), "photo.jpg", "image/jpeg").statusCode())
                .isEqualTo(200);
        return OutilsPhoto.jetonDe(photoUrlMoi(coureur1));
    }
}
