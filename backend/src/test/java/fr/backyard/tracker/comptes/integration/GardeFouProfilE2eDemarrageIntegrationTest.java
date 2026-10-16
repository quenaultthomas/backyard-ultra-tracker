package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import fr.backyard.tracker.BackyardUltraTrackerApplication;
import fr.backyard.tracker.JournalApplicatif;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CA10 (4.3 RG8) : garde-fou du profil e2e au démarrage d'un vrai contexte sur PostgreSQL. Chaque cas démarre son
 * propre contexte, avec ou sans le profil e2e.
 */
@Testcontainers
class GardeFouProfilE2eDemarrageIntegrationTest {

    static final String MESSAGE_REFUS = "Le profil e2e est interdit en production (horloge pilotable).";
    static final String AVERTISSEMENT = "Profil e2e actif : horloge pilotable, ne jamais utiliser en production.";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    private ConfigurableApplicationContext demarrer(JournalApplicatif journal, String profil, String... proprietes) {
        List<String> toutes = new ArrayList<>(List.of(
                "server.port=0",
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.main.banner-mode=off"));
        toutes.addAll(List.of(proprietes));
        SpringApplicationBuilder constructeur = new SpringApplicationBuilder(BackyardUltraTrackerApplication.class)
                .web(WebApplicationType.SERVLET)
                // Logback est réinitialisé au démarrage : l'appender est rattaché une fois le journal configuré.
                .listeners((ApplicationListener<ApplicationPreparedEvent>) evenement -> journal.rattacher())
                .properties(toutes.toArray(String[]::new));
        if (profil != null) {
            constructeur.profiles(profil);
        }
        return constructeur.run();
    }

    private static List<String> avertissementsProfil(JournalApplicatif journal) {
        return journal.evenementsApplicatifs().stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(e -> e.getFormattedMessage())
                .filter(m -> m != null && m.startsWith("Profil e2e actif"))
                .toList();
    }

    private static boolean causeAvecMessage(Throwable erreur, String message) {
        for (Throwable courante = erreur; courante != null; courante = courante.getCause()) {
            if (message.equals(courante.getMessage())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("CA10 : profil e2e avec backyard.environnement=production : le contexte ne se crée pas, avec le message exact de RG8")
    void ca10_refus_en_production() {
        try (JournalApplicatif journal = JournalApplicatif.demarrer()) {
            assertThatThrownBy(() -> demarrer(journal, "e2e", "backyard.environnement=production"))
                    .satisfies(erreur -> assertThat(causeAvecMessage(erreur, MESSAGE_REFUS))
                            .as("cause portant le message %s dans %s", MESSAGE_REFUS, erreur).isTrue());
            assertThat(avertissementsProfil(journal)).isEmpty();
        }
    }

    @Test
    @DisplayName("CA10 : la valeur production est reconnue sans tenir compte de la casse ni des espaces")
    void ca10_refus_casse_et_espaces() {
        try (JournalApplicatif journal = JournalApplicatif.demarrer()) {
            assertThatThrownBy(() -> demarrer(journal, "e2e", "backyard.environnement= Production "))
                    .satisfies(erreur -> assertThat(causeAvecMessage(erreur, MESSAGE_REFUS)).isTrue());
        }
    }

    @Test
    @DisplayName("CA10 : profil e2e avec la propriété absente : le contexte démarre et une seule ligne WARN Profil e2e actif est émise")
    void ca10_demarrage_autorise_propriete_absente() {
        try (JournalApplicatif journal = JournalApplicatif.demarrer();
             ConfigurableApplicationContext contexte = demarrer(journal, "e2e")) {
            assertThat(contexte.isRunning()).isTrue();
            assertThat(avertissementsProfil(journal)).containsExactly(AVERTISSEMENT);
        }
    }

    @Test
    @DisplayName("CA10 : profil e2e avec backyard.environnement=dev : le contexte démarre et une seule ligne WARN est émise")
    void ca10_demarrage_autorise_environnement_dev() {
        try (JournalApplicatif journal = JournalApplicatif.demarrer();
             ConfigurableApplicationContext contexte = demarrer(journal, "e2e", "backyard.environnement=dev")) {
            assertThat(contexte.isRunning()).isTrue();
            assertThat(avertissementsProfil(journal)).containsExactly(AVERTISSEMENT);
        }
    }

    @Test
    @DisplayName("CA10 : sans profil e2e et avec backyard.environnement=production : le contexte démarre sans la ligne WARN ni aucune ligne sur l'horloge pilotable")
    void ca10_production_sans_profil_e2e() {
        try (JournalApplicatif journal = JournalApplicatif.demarrer();
             ConfigurableApplicationContext contexte = demarrer(journal, null, "backyard.environnement=production")) {
            assertThat(contexte.isRunning()).isTrue();
            assertThat(avertissementsProfil(journal)).isEmpty();
            assertThat(journal.messages()).noneSatisfy(m -> assertThat(m).containsIgnoringCase("horloge pilotable"));
        }
    }
}
