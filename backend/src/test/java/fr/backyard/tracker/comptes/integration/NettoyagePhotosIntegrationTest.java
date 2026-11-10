package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.infrastructure.NettoyagePhotosAuDemarrage;
import fr.backyard.tracker.comptes.infrastructure.RepertoirePhotosFichiersAdapter;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Correctif audit jalon 10, RG4 à RG6 : CA10 (journal du nettoyage) et CA11 (nettoyage sur la stack d'intégration,
 * PostgreSQL réel, répertoire temporaire). Le déclencheur de démarrage est exécuté explicitement ; son déclenchement
 * réel au démarrage du contexte est couvert par {@code NettoyagePhotosDemarrageIntegrationTest} (CA12).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Testcontainers
class NettoyagePhotosIntegrationTest extends PhotoIntegrationBase {

    static final Path REPERTOIRE = repertoireTemporaire();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void proprietes(DynamicPropertyRegistry registre) {
        registre.add(PROPRIETE_REPERTOIRE, REPERTOIRE::toString);
    }

    @Autowired
    NettoyagePhotosAuDemarrage declencheur;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @MockitoSpyBean
    RepertoirePhotosFichiersAdapter repertoireAdapter;

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
            return Files.createTempDirectory("photos-it-nettoyage").resolve("photos");
        } catch (IOException echec) {
            throw new IllegalStateException(echec);
        }
    }

    private static String jetonAleatoire() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Fichier de contenu quelconque, modifié il y a {@code age}. */
    private Path deposer(String nom, Duration age) throws IOException {
        Files.createDirectories(REPERTOIRE);
        Path fichier = REPERTOIRE.resolve(nom);
        Files.write(fichier, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(fichier, FileTime.from(Instant.now().minus(age)));
        return fichier;
    }

    private void declencher() {
        declencheur.run(new DefaultApplicationArguments());
    }

    // ---------------------------------------------------------------- CA10

    @Test
    @DisplayName("CA10 : nettoyage de 3 orphelins supprimés, 1 .tmp, 2 ignorés et 1 échec de suppression : une ligne INFO de bilan avec les seuls nombres 3, 1, 2, une ligne WARN avec le nombre 1, aucune ligne ne contient de jeton, de nom, de chemin, de pseudo ni d'identifiant de Compte, aucune ligne INFO par fichier")
    void ca10_journal_du_nettoyage() throws Exception {
        List<String> orphelins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String nom = jetonAleatoire() + ".jpg";
            orphelins.add(nom);
            deposer(nom, Duration.ofHours(5));
        }
        String refuse = orphelins.get(1);
        String temporaire = jetonAleatoire() + ".png.tmp";
        deposer(temporaire, Duration.ofHours(5));
        deposer("notes.txt", Duration.ofHours(5));
        deposer("ABCDEF" + jetonAleatoire().substring(6) + ".png", Duration.ofHours(5));
        doThrow(new UncheckedIOException("échec simulé", new IOException("échec simulé")))
                .when(repertoireAdapter).supprimer(eq(refuse));
        journal = JournalApplicatif.demarrer();
        int marque = journal.marque();

        declencher();

        List<ILoggingEvent> lignes = journal.applicatifsDepuis(marque).stream()
                .filter(e -> e.getFormattedMessage().contains("Nettoyage des photos")).toList();
        List<ILoggingEvent> infos = lignes.stream().filter(e -> e.getLevel() == Level.INFO).toList();
        List<ILoggingEvent> avertissements = lignes.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).getFormattedMessage()).isEqualTo("Nettoyage des photos : 3 fichier(s) orphelin(s) "
                + "supprimé(s), 1 fichier(s) temporaire(s) supprimé(s), 2 fichier(s) ignoré(s)");
        assertThat(avertissements).hasSize(1);
        assertThat(avertissements.get(0).getFormattedMessage()).isEqualTo("Nettoyage des photos : 1 erreur(s)");
        assertThat(journal.applicatifsDepuis(marque).stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO)).toList())
                .as("aucune autre ligne INFO ou supérieure").hasSize(2);
        List<String> interdits = new ArrayList<>(orphelins);
        interdits.add(temporaire);
        interdits.addAll(List.of("notes.txt", "ABCDEF", REPERTOIRE.toString(), "Coureur1", idCoureur1.toString(),
                idPatron.toString()));
        interdits.addAll(orphelins.stream().map(n -> n.substring(0, 32)).toList());
        for (String interdit : interdits) {
            assertThat(journal.messagesEtExceptions()).as(interdit)
                    .noneSatisfy(message -> assertThat(message).contains(interdit));
        }
        assertThat(OutilsPhoto.noms(REPERTOIRE)).contains(refuse).doesNotContain(temporaire)
                .contains("notes.txt").hasSize(3);
    }

    // ---------------------------------------------------------------- CA11

    @Test
    @DisplayName("CA11 : avec la photo de Karim envoyée par l'API, un orphelin de 2 h et un .tmp de 2 h sont supprimés ; un .png orphelin de 10 min, un .jpg.tmp de 10 min et notes.txt de 2 h sont conservés ; la photo de Karim répond 200 ; une seule instruction SQL pour le balayage")
    void ca11_nettoyage_au_demarrage() throws Exception {
        String jetonKarim = poserPhoto(coureur1, OutilsPhoto.png(1500));
        Path orphelinAncien = deposer(jetonAleatoire() + ".jpg", Duration.ofHours(2));
        Path temporaireAncien = deposer(jetonAleatoire() + ".png.tmp", Duration.ofHours(2));
        Path orphelinRecent = deposer(jetonAleatoire() + ".png", Duration.ofMinutes(10));
        Path temporaireRecent = deposer(jetonAleatoire() + ".jpg.tmp", Duration.ofMinutes(10));
        Path notes = deposer("notes.txt", Duration.ofHours(2));
        Statistics statistiques = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long avant = statistiques.getPrepareStatementCount();

        declencher();

        assertThat(statistiques.getPrepareStatementCount() - avant).as("instructions SQL du balayage").isEqualTo(1);
        assertThat(orphelinAncien).doesNotExist();
        assertThat(temporaireAncien).doesNotExist();
        assertThat(orphelinRecent).exists();
        assertThat(temporaireRecent).exists();
        assertThat(notes).exists();
        assertThat(REPERTOIRE.resolve(jetonKarim + ".png")).exists();
        assertThat(api.get(CHEMIN_PHOTOS + jetonKarim).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("CA11 : suppression du Compte de Karim puis fichier recréé (effacement empêché) et vieilli à 2 h : un nouveau nettoyage supprime ce fichier devenu orphelin")
    void ca11_fichier_orphelin_apres_suppression_du_compte() throws Exception {
        String jeton = poserPhoto(coureur1, OutilsPhoto.png(1500));
        Path fichier = REPERTOIRE.resolve(jeton + ".png");
        byte[] octets = Files.readAllBytes(fichier);

        var suppression = api.requete("POST", "/api/comptes/moi/suppression", coureur1.entetes(), "application/json",
                ("{\"motDePasseActuel\":\"" + MOT_DE_PASSE + "\"}").getBytes(StandardCharsets.UTF_8));
        assertThat(suppression.statusCode()).isEqualTo(204);
        assertThat(fichier).doesNotExist();
        Files.write(fichier, octets);
        Files.setLastModifiedTime(fichier, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        declencher();

        assertThat(fichier).doesNotExist();
        assertIntrouvable(jeton);
    }
}
