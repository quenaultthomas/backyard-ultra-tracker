package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.infrastructure.ConnexionConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** CA20 (incrément 8.4) : démarrage refusé sur valeurs invalides du plafond d'échecs par adresse, journal de démarrage. */
class ConnexionPlafondAdresseConfigurationIntegrationTest {

    static final String MAX = "backyard.connexion.echecs-par-ip-max";
    static final String FENETRE = "backyard.connexion.echecs-par-ip-fenetre-secondes";

    static Stream<String[]> valeursInvalides() {
        return Stream.of(
                new String[] {MAX, "abc", "1000"}, new String[] {MAX, "0", "1000"},
                new String[] {MAX, "1001", "1000"}, new String[] {MAX, "", "1000"},
                new String[] {MAX, "1.5", "1000"}, new String[] {MAX, "  ", "1000"},
                new String[] {MAX, "20 par minute", "1000"},
                new String[] {FENETRE, "abc", "3600"}, new String[] {FENETRE, "0", "3600"},
                new String[] {FENETRE, "3601", "3600"}, new String[] {FENETRE, "", "3600"});
    }

    @ParameterizedTest(name = "CA20 : {0}=[{1}] fait échouer le démarrage en citant la propriété")
    @MethodSource("valeursInvalides")
    @DisplayName("CA20 : valeur invalide, le démarrage échoue et le message cite la propriété fautive et sa borne")
    void ca20_valeur_invalide_refusee(String propriete, String valeur, String borne) {
        new ApplicationContextRunner().withUserConfiguration(ConnexionConfiguration.class)
                .withPropertyValues("backyard.connexion.echecs-max=5", "backyard.connexion.blocage-secondes=900",
                        propriete + "=" + valeur)
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
    @DisplayName("CA20 : avec 5 et 30, le bean vaut (5, 30) et le journal de démarrage porte « 5 échecs par 30 s »")
    void ca20_valeurs_valides_et_journal_de_demarrage() {
        try (JournalApplicatif journal = JournalApplicatif.demarrer().niveau(Logger.ROOT_LOGGER_NAME, Level.DEBUG)) {
            new ApplicationContextRunner().withUserConfiguration(ConnexionConfiguration.class)
                    .withPropertyValues("backyard.connexion.echecs-max=5", "backyard.connexion.blocage-secondes=900",
                            MAX + "=5", FENETRE + "=30")
                    .run(contexte -> assertThat(contexte.getBean(PlafondEchecsParAdresse.class))
                            .isEqualTo(new PlafondEchecsParAdresse(5, 30)));

            List<ILoggingEvent> lignes = journal.evenements().stream()
                    .filter(e -> e.getFormattedMessage().contains("Plafond d'échecs")).toList();
            assertThat(lignes).hasSize(1);
            assertThat(lignes.get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(lignes.get(0).getFormattedMessage())
                    .isEqualTo("Plafond d'échecs de connexion par adresse : 5 échecs par 30 s");
        }
    }

    @Test
    @DisplayName("CA20 : espaces autour des entiers ignorés, bornes minimales et maximales acceptées")
    void ca20_espaces_et_bornes_acceptes() {
        new ApplicationContextRunner().withUserConfiguration(ConnexionConfiguration.class)
                .withPropertyValues("backyard.connexion.echecs-max=5", "backyard.connexion.blocage-secondes=900",
                        MAX + "= 1000 ", FENETRE + "= 1 ")
                .run(contexte -> assertThat(contexte.getBean(PlafondEchecsParAdresse.class))
                        .isEqualTo(new PlafondEchecsParAdresse(1000, 1)));
    }
}
