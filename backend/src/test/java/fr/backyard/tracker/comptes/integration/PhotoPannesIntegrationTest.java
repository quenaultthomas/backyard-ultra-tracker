package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.infrastructure.CompteJpaAdapter;
import fr.backyard.tracker.comptes.infrastructure.PhotosFichiersAdapter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Contrat de l'incrément 10.1a : limites de taille sur serveur réel (CA12), fichier manquant ou effacement en échec
 * (CA18), pannes à l'écriture et à la mise à jour, fichier temporaire jamais servi (CA20).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1"})
@Testcontainers
class PhotoPannesIntegrationTest extends PhotoIntegrationBase {

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

    @MockitoSpyBean
    CompteJpaAdapter depotComptes;

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
            return Files.createTempDirectory("photos-it-pannes").resolve("photos");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    // ---------------------------------------------------------------- CA12

    @Test
    @DisplayName("CA12 : serveur réel, PNG de 2 097 152 octets accepté ; 2 097 153 octets : 413 PHOTO_TROP_VOLUMINEUSE (ProblemDetail), ancienne photo conservée")
    void ca12_limite_exacte() throws Exception {
        byte[] maximale = OutilsPhoto.png(OutilsPhoto.TAILLE_MAXIMALE);

        HttpResponse<byte[]> accepte = api.envoyerPhoto(coureur1, maximale);

        assertThat(accepte.statusCode()).isEqualTo(200);
        String jeton = OutilsPhoto.jetonDe(corps(accepte).get("photoUrl").asString());
        assertServie(jeton, maximale);

        HttpResponse<byte[]> trop = api.envoyerPhoto(coureur1, OutilsPhoto.png(OutilsPhoto.TAILLE_MAXIMALE + 1));

        assertProbleme(trop, 413, "PHOTO_TROP_VOLUMINEUSE");
        JsonNode probleme = corps(trop);
        assertThat(probleme.get("title").asString()).isEqualTo("Fichier trop volumineux");
        assertThat(probleme.get("detail").asString()).isEqualTo("La photo ne doit pas dépasser 2 Mo.");
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", jeton);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
        assertServie(jeton, maximale);
    }

    @Test
    @DisplayName("CA12 : PUT d'un logo de 2 097 153 octets sur /api/administration/courses/{id}/logo : toujours 413 LOGO_TROP_VOLUMINEUX (le code dépend du chemin)")
    void ca12_le_logo_garde_son_code() throws Exception {
        UUID course = insererCourse("Course logo", "EN_PREPARATION");
        OutilsPhoto.Multipart corps = new OutilsPhoto.Multipart().partie("fichier", "logo.png", "image/png",
                OutilsPhoto.png(OutilsPhoto.TAILLE_MAXIMALE + 1));

        HttpResponse<byte[]> reponse = api.requete("PUT", "/api/administration/courses/" + course + "/logo",
                patron.entetes(), corps.contentType(), corps.octets());

        assertProbleme(reponse, 413, "LOGO_TROP_VOLUMINEUX");
    }

    // ---------------------------------------------------------------- CA18

    @Test
    @DisplayName("CA18 : effacement impossible du fichier à la suppression du Compte : 204, URL 404, une ligne WARN sans jeton ni chemin")
    void ca18_echec_d_effacement() throws Exception {
        journal = JournalApplicatif.demarrer();
        journal.niveau("fr.backyard.tracker", Level.INFO);
        String jeton = poserPhoto(coureur1, OutilsPhoto.png(300));
        Path fichier = REPERTOIRE.resolve(jeton + ".png");
        Files.delete(fichier);
        Files.createDirectory(fichier);
        Files.writeString(fichier.resolve("bloque"), "ce répertoire empêche l'effacement du fichier");
        int marque = journal.marque();

        HttpResponse<byte[]> reponse = api.requete("POST", "/api/comptes/moi/suppression", coureur1.entetes(),
                "application/json", OutilsPhoto.texte("{\"motDePasseActuel\":\"" + MOT_DE_PASSE + "\"}"));

        assertThat(reponse.statusCode()).isEqualTo(204);
        assertIntrouvable(jeton);
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", null);
        List<ILoggingEvent> avertissements = journal.applicatifsDepuis(marque).stream()
                .filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(avertissements).hasSize(1);
        assertThat(avertissements.get(0).getFormattedMessage())
                .isEqualTo("Effacement de la photo impossible (compte " + idCoureur1 + ")");
        for (String interdit : List.of(jeton, REPERTOIRE.toString(), "Coureur1")) {
            assertThat(journal.messagesEtExceptions()).as(interdit)
                    .noneSatisfy(message -> assertThat(message).contains(interdit));
        }
    }

    @Test
    @DisplayName("CA18 : fichier supprimé du volume alors que la base porte le jeton : GET 404 et une ligne ERROR sans jeton ni chemin ; PUT 200 rétablit une photo servie ; DELETE 204")
    void ca18_fichier_manquant() throws Exception {
        journal = JournalApplicatif.demarrer();
        journal.niveau("fr.backyard.tracker", Level.INFO);
        String jeton = poserPhoto(coureur1, OutilsPhoto.png(300));
        Files.delete(REPERTOIRE.resolve(jeton + ".png"));
        int marque = journal.marque();

        assertIntrouvable(jeton);

        List<ILoggingEvent> erreurs = journal.applicatifsDepuis(marque).stream()
                .filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(erreurs).hasSize(1);
        assertThat(erreurs.get(0).getFormattedMessage())
                .isEqualTo("Fichier de photo introuvable (compte " + idCoureur1 + ")");
        for (String interdit : List.of(jeton, REPERTOIRE.toString(), "Coureur1")) {
            assertThat(journal.messagesEtExceptions()).as(interdit)
                    .noneSatisfy(message -> assertThat(message).contains(interdit));
        }

        byte[] nouvelle = OutilsPhoto.jpeg(400);
        assertThat(api.envoyerPhoto(coureur1, nouvelle, "p.jpg", "image/jpeg").statusCode()).isEqualTo(200);
        assertServie(OutilsPhoto.jetonDe(photoUrlMoi(coureur1)), nouvelle);
        assertThat(api.retirerPhoto(coureur1).statusCode()).isEqualTo(204);
        assertThat(OutilsPhoto.noms(REPERTOIRE)).isEmpty();
        assertThat(api.retirerPhoto(coureur1).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("CA18 : fichier manquant puis DELETE direct : 204 et colonnes nulles")
    void ca18_retrait_avec_fichier_manquant() throws Exception {
        String jeton = poserPhoto(coureur1, OutilsPhoto.png(300));
        Files.delete(REPERTOIRE.resolve(jeton + ".png"));

        assertThat(api.retirerPhoto(coureur1).statusCode()).isEqualTo(204);

        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", null).containsEntry("photo_format", null);
        assertIntrouvable(jeton);
    }

    // ---------------------------------------------------------------- CA20

    @Test
    @DisplayName("CA20 : panne à l'écriture du fichier : 500 ERREUR_INTERNE sans détail technique, ancienne photo et fichier inchangés, aucun .tmp ni fichier neuf")
    void ca20_panne_a_l_ecriture() throws Exception {
        byte[] ancienne = OutilsPhoto.png(300);
        String jeton = poserPhoto(coureur1, ancienne);
        doThrow(new UncheckedIOException("disque plein /volume/secret", new IOException("disque plein")))
                .when(stockage).enregistrer(any(), any(), any());

        HttpResponse<byte[]> reponse = api.envoyerPhoto(coureur1, OutilsPhoto.jpeg(400), "p.jpg", "image/jpeg");

        assertProbleme(reponse, 500, "ERREUR_INTERNE");
        assertThat(new String(reponse.body())).doesNotContain("disque").doesNotContain("secret");
        verifierAncienneIntacte(jeton, ancienne);
    }

    @Test
    @DisplayName("CA20 : panne à la mise à jour du Compte après l'écriture : 500 ERREUR_INTERNE, fichier neuf effacé, ancienne photo et fichier inchangés")
    void ca20_panne_a_la_mise_a_jour() throws Exception {
        byte[] ancienne = OutilsPhoto.png(300);
        String jeton = poserPhoto(coureur1, ancienne);
        doThrow(new IllegalStateException("panne base /secret")).when(depotComptes).mettreAJour(any());

        HttpResponse<byte[]> reponse = api.envoyerPhoto(coureur1, OutilsPhoto.jpeg(400), "p.jpg", "image/jpeg");

        assertProbleme(reponse, 500, "ERREUR_INTERNE");
        assertThat(new String(reponse.body())).doesNotContain("panne").doesNotContain("secret");
        verifierAncienneIntacte(jeton, ancienne);
    }

    @Test
    @DisplayName("CA20 : un .tmp posé à la main dans le répertoire n'est jamais servi, quel que soit le nom demandé")
    void ca20_fichier_temporaire_jamais_servi() throws Exception {
        String inconnu = "0123456789abcdef0123456789abcdef";
        Files.createDirectories(REPERTOIRE);
        Files.write(REPERTOIRE.resolve(inconnu + ".png.tmp"), OutilsPhoto.png(300));
        Files.write(REPERTOIRE.resolve(inconnu + ".png"), OutilsPhoto.png(300));

        assertIntrouvable(inconnu);
        assertIntrouvable(inconnu + ".png.tmp");
        assertIntrouvable(inconnu + ".png");
    }

    private void verifierAncienneIntacte(String jeton, byte[] ancienne) throws Exception {
        assertThat(colonnesPhoto(idCoureur1)).containsEntry("photo_jeton", jeton).containsEntry("photo_format", "PNG");
        assertThat(OutilsPhoto.noms(REPERTOIRE)).containsExactly(jeton + ".png");
        assertServie(jeton, ancienne);
    }
}
