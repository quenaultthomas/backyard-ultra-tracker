package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.domaine.PlafondCreationsCompteParAdresse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Incrément 8.5, CA6 et CA7 : lecture, validation et journal du plafond de créations, sans Spring (RG8, RG9). */
class CreationCompteConfigurationTest {

    private static final String PROPRIETE_MAX = "backyard.creation-compte.par-ip-max";
    private static final String PROPRIETE_FENETRE = "backyard.creation-compte.par-ip-fenetre-secondes";

    private final CreationCompteConfiguration configuration = new CreationCompteConfiguration();

    @Test
    @DisplayName("CA6 - (\"10\", \"60\") donne le plafond (10, 60)")
    void doit_construire_le_plafond_par_defaut_depuis_les_textes() {
        assertThat(configuration.plafondCreationsCompteParAdresse("10", "60"))
                .isEqualTo(new PlafondCreationsCompteParAdresse(10, 60));
    }

    @Test
    @DisplayName("CA6 - les espaces autour des entiers sont ignorés : (\" 5 \", \"120\") donne (5, 120)")
    void doit_ignorer_les_espaces_autour_des_entiers() {
        assertThat(configuration.plafondCreationsCompteParAdresse(" 5 ", "120"))
                .isEqualTo(new PlafondCreationsCompteParAdresse(5, 120));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "10 par minute", "", "  ", "0", "-1", "1001", "99999999999"})
    @DisplayName("CA6 - un nombre de créations max invalide empêche le démarrage, le message nomme la propriété et la borne, la cause est conservée")
    void doit_refuser_un_nombre_de_creations_max_invalide(String valeur) {
        assertThatThrownBy(() -> configuration.plafondCreationsCompteParAdresse(valeur, "60"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPRIETE_MAX)
                .hasMessageContaining("1000")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "60 s", "", "  ", "0", "-1", "3601", "99999999999"})
    @DisplayName("CA6 - une fenêtre invalide empêche le démarrage, le message nomme la propriété et la borne, la cause est conservée")
    void doit_refuser_une_fenetre_invalide(String valeur) {
        assertThatThrownBy(() -> configuration.plafondCreationsCompteParAdresse("10", valeur))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPRIETE_FENETRE)
                .hasMessageContaining("3600")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("CA7 - le démarrage journalise en INFO « Plafond de créations de compte par adresse : 3 créations par 60 s »")
    void doit_journaliser_le_plafond_au_demarrage() {
        try (JournalApplicatif journal = JournalApplicatif.demarrerEnDebug()) {
            configuration.plafondCreationsCompteParAdresse("3", "60");

            assertThat(journal.evenementsApplicatifs())
                    .filteredOn(e -> e.getLevel() == Level.INFO)
                    .extracting(e -> e.getFormattedMessage())
                    .contains("Plafond de créations de compte par adresse : 3 créations par 60 s");
        }
    }
}
