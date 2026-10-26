package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Incrément 8.4, CA3 : bornes du plafond d'échecs de connexion par adresse (RG1, RG12). */
class PlafondEchecsParAdresseTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1001})
    @DisplayName("CA3 - un nombre d'échecs max hors de 1 à 1000 est refusé, le message nomme le paramètre et la borne")
    void doit_refuser_un_nombre_d_echecs_max_hors_bornes(int echecsMax) {
        assertThatThrownBy(() -> new PlafondEchecsParAdresse(echecsMax, 60))
                .isInstanceOf(PlafondEchecsParAdresseInvalideException.class)
                .hasMessageContaining("echecsMax")
                .hasMessageContaining("1000");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3601})
    @DisplayName("CA3 - une fenêtre hors de 1 à 3600 secondes est refusée, le message nomme le paramètre et la borne")
    void doit_refuser_une_fenetre_hors_bornes(int fenetreSecondes) {
        assertThatThrownBy(() -> new PlafondEchecsParAdresse(20, fenetreSecondes))
                .isInstanceOf(PlafondEchecsParAdresseInvalideException.class)
                .hasMessageContaining("fenetreSecondes")
                .hasMessageContaining("3600");
    }

    @Test
    @DisplayName("CA3 - les bornes incluses (1, 1), (1000, 3600) et la valeur courante (20, 60) sont acceptées")
    void doit_accepter_les_bornes_incluses() {
        assertThatCode(() -> new PlafondEchecsParAdresse(1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> new PlafondEchecsParAdresse(1000, 3600)).doesNotThrowAnyException();
        assertThatCode(() -> new PlafondEchecsParAdresse(20, 60)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA3 - le plafond par défaut vaut 20 échecs par 60 secondes")
    void doit_valoir_20_echecs_par_60_secondes_par_defaut() {
        PlafondEchecsParAdresse parDefaut = PlafondEchecsParAdresse.parDefaut();

        assertThat(parDefaut).isEqualTo(new PlafondEchecsParAdresse(20, 60));
        assertThat(parDefaut.echecsMax()).isEqualTo(20);
        assertThat(parDefaut.fenetreSecondes()).isEqualTo(60);
    }

    @Test
    @DisplayName("CA3 - fenetre() exprime la fenêtre en Duration")
    void doit_exprimer_la_fenetre_en_duree() {
        assertThat(new PlafondEchecsParAdresse(20, 60).fenetre()).isEqualTo(Duration.ofSeconds(60));
        assertThat(new PlafondEchecsParAdresse(5, 30).fenetre()).isEqualTo(Duration.ofSeconds(30));
    }
}
