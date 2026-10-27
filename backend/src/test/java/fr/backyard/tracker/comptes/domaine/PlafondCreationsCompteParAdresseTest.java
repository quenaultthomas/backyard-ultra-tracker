package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Incrément 8.5, CA1 : bornes du plafond de créations de Comptes coureur par adresse (RG2, RG9). */
class PlafondCreationsCompteParAdresseTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    @DisplayName("CA1 - un nombre de créations max hors de 1 à 1000 est refusé, le message nomme le paramètre et la borne")
    void doit_refuser_un_nombre_de_creations_max_hors_bornes(int creationsMax) {
        assertThatThrownBy(() -> new PlafondCreationsCompteParAdresse(creationsMax, 60))
                .isInstanceOf(PlafondCreationsCompteInvalideException.class)
                .hasMessageContaining("creationsMax")
                .hasMessageContaining("1000");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3601})
    @DisplayName("CA1 - une fenêtre hors de 1 à 3600 secondes est refusée, le message nomme le paramètre et la borne")
    void doit_refuser_une_fenetre_hors_bornes(int fenetreSecondes) {
        assertThatThrownBy(() -> new PlafondCreationsCompteParAdresse(10, fenetreSecondes))
                .isInstanceOf(PlafondCreationsCompteInvalideException.class)
                .hasMessageContaining("fenetreSecondes")
                .hasMessageContaining("3600");
    }

    @Test
    @DisplayName("CA1 - les bornes incluses (1, 1), (1000, 3600) et la valeur courante (10, 60) sont acceptées")
    void doit_accepter_les_bornes_incluses() {
        assertThatCode(() -> new PlafondCreationsCompteParAdresse(1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> new PlafondCreationsCompteParAdresse(1000, 3600)).doesNotThrowAnyException();
        assertThatCode(() -> new PlafondCreationsCompteParAdresse(10, 60)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - le plafond par défaut vaut 10 créations par 60 secondes")
    void doit_valoir_dix_par_soixante_secondes_par_defaut() {
        assertThat(PlafondCreationsCompteParAdresse.parDefaut()).isEqualTo(new PlafondCreationsCompteParAdresse(10, 60));
    }

    @Test
    @DisplayName("CA1 - la fenêtre glissante est le plafond d'échecs de 8.4 de mêmes valeurs")
    void doit_se_convertir_en_plafond_d_echecs_de_memes_valeurs() {
        assertThat(new PlafondCreationsCompteParAdresse(7, 120).enFenetreGlissante())
                .isEqualTo(new PlafondEchecsParAdresse(7, 120));
    }
}
