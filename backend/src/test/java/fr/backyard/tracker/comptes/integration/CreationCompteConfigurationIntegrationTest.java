package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.domaine.PlafondCreationsCompteParAdresse;
import fr.backyard.tracker.comptes.infrastructure.CreationCompteConfiguration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** CA15 (incrément 8.5) : démarrage refusé sur valeurs invalides du plafond de créations, défauts et journal de démarrage. */
class CreationCompteConfigurationIntegrationTest {

    static final String MAX = "backyard.creation-compte.par-ip-max";
    static final String FENETRE = "backyard.creation-compte.par-ip-fenetre-secondes";

    static Stream<String[]> valeursInvalides() {
        return Stream.of(
                new String[] {MAX, "abc", "1000"}, new String[] {MAX, "0", "1000"}, new String[] {MAX, "-1", "1000"},
                new String[] {MAX, "1001", "1000"}, new String[] {MAX, "", "1000"}, new String[] {MAX, "1.5", "1000"},
                new String[] {MAX, "  ", "1000"}, new String[] {MAX, "10 par minute", "1000"},
                new String[] {MAX, "99999999999", "1000"},
                new String[] {FENETRE, "abc", "3600"}, new String[] {FENETRE, "0", "3600"},
                new String[] {FENETRE, "-1", "3600"}, new String[] {FENETRE, "3601", "3600"},
                new String[] {FENETRE, "", "3600"}, new String[] {FENETRE, "1.5", "3600"});
    }

    @ParameterizedTest(name = "CA15 : {0}=[{1}] fait échouer le démarrage en citant la propriété")
    @MethodSource("valeursInvalides")
    @DisplayName("CA15 : valeur invalide, le démarrage échoue et le message cite la propriété fautive et sa borne")
    void ca15_valeur_invalide_refusee(String propriete, String valeur, String borne) {
        new ApplicationContextRunner().withUserConfiguration(CreationCompteConfiguration.class)
                .withPropertyValues(propriete + "=" + valeur)
                .run(contexte -> {
                    assertThat(contexte).hasFailed();
                    List<String> messages = new ArrayList<>();
                    for (Throwable t = contexte.getStartupFailure(); t != null; t = t.getCause()) {
                        messages.add(String.valueOf(t.getMessage()));
                    }
                    assertThat(String.join(" | ", messages)).contains(propriete).contains(borne);
                });
    }

    @Test
    @DisplayName("CA15 : avec 5 et 30, le bean vaut (5, 30) et le journal de démarrage porte « 5 créations par 30 s »")
    void ca15_valeurs_valides_et_journal_de_demarrage() {
        try (JournalApplicatif journal = JournalApplicatif.demarrer().niveau(Logger.ROOT_LOGGER_NAME, Level.DEBUG)) {
            new ApplicationContextRunner().withUserConfiguration(CreationCompteConfiguration.class)
                    .withPropertyValues(MAX + "=5", FENETRE + "=30")
                    .run(contexte -> assertThat(contexte.getBean(PlafondCreationsCompteParAdresse.class))
                            .isEqualTo(new PlafondCreationsCompteParAdresse(5, 30)));

            List<ILoggingEvent> lignes = journal.evenements().stream()
                    .filter(e -> e.getFormattedMessage().contains("Plafond de créations de compte")).toList();
            assertThat(lignes).hasSize(1);
            assertThat(lignes.get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(lignes.get(0).getFormattedMessage())
                    .isEqualTo("Plafond de créations de compte par adresse : 5 créations par 30 s");
        }
    }

    @Test
    @DisplayName("CA15 : espaces autour des entiers ignorés, bornes minimales et maximales acceptées")
    void ca15_espaces_et_bornes_acceptes() {
        new ApplicationContextRunner().withUserConfiguration(CreationCompteConfiguration.class)
                .withPropertyValues(MAX + "= 1000 ", FENETRE + "= 1 ")
                .run(contexte -> assertThat(contexte.getBean(PlafondCreationsCompteParAdresse.class))
                        .isEqualTo(new PlafondCreationsCompteParAdresse(1000, 1)));
        new ApplicationContextRunner().withUserConfiguration(CreationCompteConfiguration.class)
                .withPropertyValues(MAX + "=1", FENETRE + "=3600")
                .run(contexte -> assertThat(contexte.getBean(PlafondCreationsCompteParAdresse.class))
                        .isEqualTo(new PlafondCreationsCompteParAdresse(1, 3600)));
    }

    @Test
    @DisplayName("CA15 : sans propriétés posées, les défauts d'application.yml lus seuls donnent (10, 60) et la ligne INFO de démarrage l'indique")
    void ca15_defauts_de_application_yml() {
        try (JournalApplicatif journal = JournalApplicatif.demarrer().niveau(Logger.ROOT_LOGGER_NAME, Level.DEBUG)) {
            new ApplicationContextRunner().withUserConfiguration(CreationCompteConfiguration.class)
                    .withInitializer(CreationCompteConfigurationIntegrationTest::ajouterApplicationYml)
                    .run(contexte -> assertThat(contexte.getBean(PlafondCreationsCompteParAdresse.class))
                            .isEqualTo(new PlafondCreationsCompteParAdresse(10, 60)));

            assertThat(journal.evenements().stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage))
                    .contains("Plafond de créations de compte par adresse : 10 créations par 60 s");
        }
    }

    /** Charge uniquement l'application.yml de production (et non le socle de test) comme source de propriétés. */
    private static void ajouterApplicationYml(ConfigurableApplicationContext contexte) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("application-yml", new ClassPathResource("application.yml"));
            sources.forEach(contexte.getEnvironment().getPropertySources()::addLast);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
