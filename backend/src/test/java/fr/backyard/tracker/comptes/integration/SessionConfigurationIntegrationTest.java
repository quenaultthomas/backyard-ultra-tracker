package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.domaine.PolitiqueSession;
import fr.backyard.tracker.comptes.infrastructure.SessionConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;

/**
 * Incrément 8.2 (SEC-16), CA8 : démarrage refusé sur valeurs invalides, défauts de application.yml, journal INFO
 * de la politique. Contexte construit avec {@link ApplicationContextRunner} (la configuration seule, avec les
 * propriétés réelles de l'application), sans base de données.
 */
class SessionConfigurationIntegrationTest {

    private static final String ADMIN = "backyard.session.inactivite-admin-secondes";
    private static final String AUTRES = "backyard.session.inactivite-coureur-benevole-secondes";

    JournalApplicatif journal;

    @AfterEach
    void nettoyer() {
        if (journal != null) {
            journal.fermer();
        }
    }

    @Test
    @DisplayName("CA8 : une valeur invalide pour backyard.session.inactivite-admin-secondes (abc, 0, -1, 604801, vide, blanc) fait échouer le démarrage en citant cette propriété")
    void ca8_valeur_admin_invalide_refusee() {
        for (String valeur : List.of("abc", "0", "-1", "604801", "", "  ", "1.5", "2h")) {
            verifierRefus(valeur, "3600", ADMIN);
        }
    }

    @Test
    @DisplayName("CA8 : une valeur invalide pour backyard.session.inactivite-coureur-benevole-secondes (abc, 0, vide, 604801) fait échouer le démarrage en citant cette propriété")
    void ca8_valeur_coureur_benevole_invalide_refusee() {
        for (String valeur : List.of("abc", "0", "", "604801")) {
            verifierRefus("60", valeur, AUTRES);
        }
    }

    @Test
    @DisplayName("CA8 : des administrateurs à 7200 s et les autres à 3600 s font échouer le démarrage en citant la propriété des administrateurs")
    void ca8_administrateurs_plus_longs_que_les_autres_refuses() {
        verifierRefus("7200", "3600", ADMIN);
    }

    @Test
    @DisplayName("CA8 : sans propriété posée, les défauts de application.yml donnent un bean PolitiqueSession de 7200 s / 43200 s")
    void ca8_defauts_de_application_yml() {
        executerAvecDefauts(contexte -> {
            assertThat(contexte).hasNotFailed();
            assertThat(contexte.getBean(PolitiqueSession.class))
                    .isEqualTo(new PolitiqueSession(Duration.ofSeconds(7200), Duration.ofSeconds(43200)))
                    .isEqualTo(PolitiqueSession.parDefaut());
        });
    }

    @Test
    @DisplayName("CA8 : les bornes 1 s / 1 s et 604800 s / 604800 s démarrent, et des espaces autour d'une valeur sont ignorés")
    void ca8_bornes_acceptees() {
        new ApplicationContextRunner().withUserConfiguration(SessionConfiguration.class)
                .withPropertyValues(ADMIN + "=1", AUTRES + "=1")
                .run(contexte -> assertThat(contexte.getBean(PolitiqueSession.class))
                        .isEqualTo(new PolitiqueSession(Duration.ofSeconds(1), Duration.ofSeconds(1))));
        new ApplicationContextRunner().withUserConfiguration(SessionConfiguration.class)
                .withPropertyValues(ADMIN + "=604800", AUTRES + "=604800")
                .run(contexte -> assertThat(contexte.getBean(PolitiqueSession.class))
                        .isEqualTo(new PolitiqueSession(Duration.ofSeconds(604800), Duration.ofSeconds(604800))));
        new ApplicationContextRunner().withUserConfiguration(SessionConfiguration.class)
                .withPropertyValues(ADMIN + "= 60 ", AUTRES + "=3600")
                .run(contexte -> assertThat(contexte.getBean(PolitiqueSession.class))
                        .isEqualTo(new PolitiqueSession(Duration.ofSeconds(60), Duration.ofSeconds(3600))));
    }

    @Test
    @DisplayName("CA8 : au démarrage valide, une ligne INFO annonce la politique (7200 s / 43200 s) sans cookie, pseudo ni mot de passe")
    void ca8_journal_info_de_la_politique() {
        journal = JournalApplicatif.demarrer().niveau("fr.backyard.tracker", Level.INFO);

        executerAvecDefauts(contexte -> assertThat(contexte).hasNotFailed());

        List<ILoggingEvent> lignes = journal.evenementsApplicatifs().stream()
                .filter(evenement -> evenement.getFormattedMessage().startsWith("Politique de session")).toList();
        assertThat(lignes).hasSize(1);
        assertThat(lignes.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(lignes.get(0).getFormattedMessage()).isEqualTo("Politique de session : 7200 s d'inactivité pour "
                + "les administrateurs, 43200 s pour les coureurs et bénévoles");
        for (ILoggingEvent evenement : journal.evenementsApplicatifs()) {
            assertThat(evenement.getFormattedMessage()).doesNotContainIgnoringCase("JSESSIONID")
                    .doesNotContainIgnoringCase("cookie").doesNotContainIgnoringCase("mot-de-passe")
                    .doesNotContainIgnoringCase("pseudo");
        }
    }

    private void executerAvecDefauts(ContextConsumer<AssertableApplicationContext> verification) {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(SessionConfiguration.class).run(verification);
    }

    private void verifierRefus(String admin, String autres, String proprieteCitee) {
        new ApplicationContextRunner().withUserConfiguration(SessionConfiguration.class)
                .withPropertyValues(ADMIN + "=" + admin, AUTRES + "=" + autres)
                .run(contexte -> {
                    assertThat(contexte).as("admin=[%s] autres=[%s]", admin, autres).hasFailed();
                    List<String> messages = new ArrayList<>();
                    for (Throwable t = contexte.getStartupFailure(); t != null; t = t.getCause()) {
                        messages.add(String.valueOf(t.getMessage()));
                    }
                    assertThat(String.join(" | ", messages)).as("admin=[%s] autres=[%s]", admin, autres)
                            .contains(proprieteCitee);
                });
    }
}
