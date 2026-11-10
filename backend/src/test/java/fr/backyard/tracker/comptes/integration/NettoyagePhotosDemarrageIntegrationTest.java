package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.domaine.JetonsPhotosPortes;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Correctif audit jalon 10, CA12 (RG5, RG6) : le nettoyage des photos orphelines au démarrage ne bloque jamais le
 * démarrage de {@code api}. Contextes Spring réels démarrés avec un répertoire inexistant, avec un chemin qui est un
 * fichier ordinaire, et avec une base en échec au moment du balayage. Le déclenchement réel au démarrage est prouvé
 * par la suppression d'un orphelin ancien dans le dernier scénario de contrôle.
 */
@Testcontainers
class NettoyagePhotosDemarrageIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    JournalApplicatif journal;

    @AfterEach
    void arreterJournal() {
        if (journal != null) {
            journal.fermer();
            journal = null;
        }
    }

    @AfterAll
    static void restaurerPontJournaux() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
    }

    /** Base qui échoue lors du balayage des jetons portés (la requête du nettoyage seulement). */
    @TestConfiguration(proxyBeanMethods = false)
    static class BaseEnEchecConfiguration {
        @Bean
        @Primary
        JetonsPhotosPortes jetonsPhotosPortesEnEchec() {
            return new JetonsPhotosPortes() {
                @Override
                public Map<String, FormatImage> portesParmi(Collection<String> jetons) {
                    throw new DataAccessResourceFailureException("base indisponible (simulée)");
                }
            };
        }
    }

    @Test
    @DisplayName("CA12 : répertoire des photos inexistant : le démarrage réussit, le répertoire n'est pas créé par le nettoyage, bilan 0, 0, 0 sans aucun WARN, /actuator/health répond 200")
    void ca12_repertoire_inexistant() throws Exception {
        Path repertoire = Files.createTempDirectory("photos-it-demarrage-absent").resolve("photos");
        journal = JournalApplicatif.demarrer();

        try (ConfigurableApplicationContext contexte = demarrer(repertoire.toString())) {
            assertThat(servi(contexte).get("/actuator/health").statusCode()).isEqualTo(200);
        }

        assertThat(repertoire).doesNotExist();
        List<ILoggingEvent> nettoyage = lignesDeNettoyage();
        assertThat(nettoyage).extracting(ILoggingEvent::getLevel).doesNotContain(Level.WARN, Level.ERROR);
        assertThat(nettoyage).extracting(ILoggingEvent::getFormattedMessage).contains("Nettoyage des photos : 0 "
                + "fichier(s) orphelin(s) supprimé(s), 0 fichier(s) temporaire(s) supprimé(s), 0 fichier(s) ignoré(s)");
    }

    @Test
    @DisplayName("CA12 : le chemin des photos est un fichier ordinaire : le démarrage réussit, un WARN est journalisé, rien n'est supprimé, /actuator/health répond 200")
    void ca12_chemin_qui_est_un_fichier() throws Exception {
        Path repertoire = Files.createTempFile("photos-it-demarrage-fichier", ".bin");
        journal = JournalApplicatif.demarrer();

        try (ConfigurableApplicationContext contexte = demarrer(repertoire.toString())) {
            assertThat(servi(contexte).get("/actuator/health").statusCode()).isEqualTo(200);
        }

        assertThat(repertoire).isRegularFile();
        assertThat(lignesDeNettoyage()).anySatisfy(ligne -> {
            assertThat(ligne.getLevel()).isEqualTo(Level.WARN);
            assertThat(ligne.getFormattedMessage()).doesNotContain(repertoire.toString());
        });
        assertThat(journal.messagesEtExceptions()).noneSatisfy(m -> assertThat(m).contains(repertoire.toString()));
    }

    @Test
    @DisplayName("CA12 : la base échoue au moment du balayage : le démarrage réussit, un WARN est journalisé, aucun fichier n'est supprimé (même un orphelin ancien), /actuator/health répond 200")
    void ca12_base_en_echec_pendant_le_balayage() throws Exception {
        Path repertoire = Files.createTempDirectory("photos-it-demarrage-base").resolve("photos");
        Path ancien = deposer(repertoire, UUID.randomUUID().toString().replace("-", "") + ".jpg", Duration.ofHours(5));
        journal = JournalApplicatif.demarrer();

        try (ConfigurableApplicationContext contexte = demarrer(repertoire.toString(),
                BaseEnEchecConfiguration.class)) {
            assertThat(servi(contexte).get("/actuator/health").statusCode()).isEqualTo(200);
        }

        assertThat(ancien).exists();
        assertThat(lignesDeNettoyage()).anySatisfy(ligne -> assertThat(ligne.getLevel()).isEqualTo(Level.WARN));
        assertThat(lignesDeNettoyage()).extracting(ILoggingEvent::getFormattedMessage)
                .noneSatisfy(m -> assertThat(m).contains(ancien.getFileName().toString()));
    }

    @Test
    @DisplayName("CA12 : au démarrage réel du contexte, un orphelin ancien est supprimé et un fichier récent conservé (le déclencheur est bien appelé), bilan INFO sans WARN")
    void ca12_le_declencheur_s_execute_au_demarrage() throws Exception {
        Path repertoire = Files.createTempDirectory("photos-it-demarrage-balayage").resolve("photos");
        Path ancien = deposer(repertoire, UUID.randomUUID().toString().replace("-", "") + ".png", Duration.ofHours(3));
        Path recent = deposer(repertoire, UUID.randomUUID().toString().replace("-", "") + ".png",
                Duration.ofMinutes(5));
        journal = JournalApplicatif.demarrer();

        try (ConfigurableApplicationContext contexte = demarrer(repertoire.toString())) {
            assertThat(servi(contexte).get("/actuator/health").statusCode()).isEqualTo(200);
        }

        assertThat(ancien).doesNotExist();
        assertThat(recent).exists();
        assertThat(lignesDeNettoyage()).extracting(ILoggingEvent::getFormattedMessage).contains("Nettoyage des photos"
                + " : 1 fichier(s) orphelin(s) supprimé(s), 0 fichier(s) temporaire(s) supprimé(s), 0 fichier(s) "
                + "ignoré(s)");
        assertThat(lignesDeNettoyage()).extracting(ILoggingEvent::getLevel).doesNotContain(Level.WARN);
    }

    // ---------------------------------------------------------------- outils

    private List<ILoggingEvent> lignesDeNettoyage() {
        return journal.evenementsApplicatifs().stream()
                .filter(e -> e.getFormattedMessage().contains("Nettoyage des photos")).toList();
    }

    private static Path deposer(Path repertoire, String nom, Duration age) throws IOException {
        Files.createDirectories(repertoire);
        Path fichier = repertoire.resolve(nom);
        Files.write(fichier, new byte[] {1, 2, 3});
        Files.setLastModifiedTime(fichier, FileTime.from(Instant.now().minus(age)));
        return fichier;
    }

    private ConfigurableApplicationContext demarrer(String repertoire, Class<?>... complements) {
        Class<?>[] sources = new Class<?>[complements.length + 1];
        sources[0] = BackyardUltraTrackerApplication.class;
        System.arraycopy(complements, 0, sources, 1, complements.length);
        return new SpringApplicationBuilder(sources)
                // Après l'initialisation du système de journalisation par Spring Boot, qui réinitialise Logback.
                .listeners(evenement -> {
                    if (evenement instanceof ApplicationPreparedEvent) {
                        SLF4JBridgeHandler.removeHandlersForRootLogger();
                        SLF4JBridgeHandler.install();
                        journal.niveau(Logger.ROOT_LOGGER_NAME, Level.DEBUG).rattacher();
                    }
                })
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--server.port=0",
                        "--backyard.photos.repertoire=" + repertoire,
                        "--backyard.admin-master.pseudo=Patron",
                        "--backyard.admin-master.mot-de-passe=mot-de-passe-patron-1");
    }

    private static Client servi(ConfigurableApplicationContext contexte) {
        return new Client(new OutilsPhoto(Integer.parseInt(contexte.getEnvironment().getProperty("local.server.port"))));
    }

    private record Client(OutilsPhoto api) {
        HttpResponse<byte[]> get(String chemin) throws IOException, InterruptedException {
            return api.get(chemin);
        }
    }
}
