package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Incrément 8.4, CA10 : lecture et validation du plafond d'adresse, sans Spring (RG12). */
class ConnexionConfigurationPlafondTest {

    private static final String PROPRIETE_MAX = "backyard.connexion.echecs-par-ip-max";
    private static final String PROPRIETE_FENETRE = "backyard.connexion.echecs-par-ip-fenetre-secondes";

    private final ConnexionConfiguration configuration = new ConnexionConfiguration();

    @Test
    @DisplayName("CA10 - (\"20\", \"60\") donne le plafond (20, 60)")
    void doit_construire_le_plafond_par_defaut_depuis_les_textes() {
        assertThat(configuration.plafondEchecsParAdresse("20", "60")).isEqualTo(new PlafondEchecsParAdresse(20, 60));
    }

    @Test
    @DisplayName("CA10 - les espaces autour des entiers sont ignorés")
    void doit_ignorer_les_espaces_autour_des_entiers() {
        assertThat(configuration.plafondEchecsParAdresse(" 100 ", " 30 "))
                .isEqualTo(new PlafondEchecsParAdresse(100, 30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "20 par minute", "", "  ", "0", "-1", "1001", "99999999999"})
    @DisplayName("CA10 - un nombre d'échecs max invalide empêche le démarrage, le message nomme la propriété, la cause est conservée")
    void doit_refuser_un_nombre_d_echecs_max_invalide(String valeur) {
        assertThatThrownBy(() -> configuration.plafondEchecsParAdresse(valeur, "60"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPRIETE_MAX)
                .hasMessageContaining("1000")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "0", "3601"})
    @DisplayName("CA10 - une fenêtre invalide empêche le démarrage, le message nomme la propriété, la cause est conservée")
    void doit_refuser_une_fenetre_invalide(String valeur) {
        assertThatThrownBy(() -> configuration.plafondEchecsParAdresse("20", valeur))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPRIETE_FENETRE)
                .hasMessageContaining("3600")
                .hasCauseInstanceOf(RuntimeException.class);
    }
}
