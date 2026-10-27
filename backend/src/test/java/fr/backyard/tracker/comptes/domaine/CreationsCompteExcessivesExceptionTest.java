package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 8.5, CA2 et RG3 : durée annoncée d'un refus de création, arrondie à la seconde supérieure. */
class CreationsCompteExcessivesExceptionTest {

    @Test
    @DisplayName("CA2 - un temps restant de 400 ms donne reessayerDansSecondes = 1")
    void doit_annoncer_au_moins_une_seconde() {
        assertThat(new CreationsCompteExcessivesException(Duration.ofMillis(400)).reessayerDansSecondes()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - un temps restant de 40 s est annoncé tel quel")
    void doit_annoncer_une_duree_entiere_telle_quelle() {
        assertThat(new CreationsCompteExcessivesException(Duration.ofSeconds(40)).reessayerDansSecondes())
                .isEqualTo(40);
    }

    @Test
    @DisplayName("CA2 - un temps restant de 1,2 s est arrondi à la seconde supérieure (2)")
    void doit_arrondir_a_la_seconde_superieure() {
        assertThat(new CreationsCompteExcessivesException(Duration.ofMillis(1200)).reessayerDansSecondes())
                .isEqualTo(2);
    }
}
