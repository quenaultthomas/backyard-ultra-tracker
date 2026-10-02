package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PolitiqueBlocageTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("CA10 - un nombre d'échecs maximal inférieur à 1 est refusé, le message nomme le paramètre")
    void doit_refuser_un_nombre_d_echecs_max_inferieur_a_1(int echecsMax) {
        assertThatThrownBy(() -> new PolitiqueBlocage(echecsMax, 900))
                .isInstanceOf(PolitiqueBlocageInvalideException.class)
                .hasMessageContaining("echecsMax");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -5})
    @DisplayName("CA10 - une durée de blocage inférieure à 1 s est refusée, le message nomme le paramètre")
    void doit_refuser_une_duree_de_blocage_inferieure_a_1_seconde(int blocageSecondes) {
        assertThatThrownBy(() -> new PolitiqueBlocage(5, blocageSecondes))
                .isInstanceOf(PolitiqueBlocageInvalideException.class)
                .hasMessageContaining("blocageSecondes");
    }

    @Test
    @DisplayName("CA10 - les bornes minimales 1 et 1 sont acceptées")
    void doit_accepter_les_bornes_minimales() {
        PolitiqueBlocage politique = new PolitiqueBlocage(1, 1);

        assertThat(politique.echecsMax()).isEqualTo(1);
        assertThat(politique.blocage()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("CA10 - la politique par défaut vaut 5 échecs et 900 s")
    void doit_proposer_une_politique_par_defaut_de_5_echecs_et_900_secondes() {
        PolitiqueBlocage politique = PolitiqueBlocage.parDefaut();

        assertThat(politique.echecsMax()).isEqualTo(5);
        assertThat(politique.blocage()).isEqualTo(Duration.ofSeconds(900));
    }
}
