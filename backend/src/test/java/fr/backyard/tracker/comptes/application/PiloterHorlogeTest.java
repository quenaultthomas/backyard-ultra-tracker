package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.DecalageHorlogeExcessifException;
import fr.backyard.tracker.comptes.domaine.DureeAvanceInvalideException;
import fr.backyard.tracker.comptes.domaine.ReglageHorloge;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CA2 - cas d'usage PiloterHorloge (RG4, RG5, RG6) avec un port de réglage en mémoire. Test unitaire sans Spring. */
class PiloterHorlogeTest {

    /** Double de test du port : additionne sans aucune règle (les règles sont dans le cas d'usage). */
    private static final class ReglageEnMemoire implements ReglageHorloge {
        private Duration decalage = Duration.ZERO;

        @Override
        public Duration decalage() {
            return decalage;
        }

        @Override
        public Duration avancer(Duration duree) {
            decalage = decalage.plus(duree);
            return decalage;
        }

        @Override
        public void reinitialiser() {
            decalage = Duration.ZERO;
        }
    }

    private final ReglageEnMemoire reglage = new ReglageEnMemoire();
    private final PiloterHorloge piloterHorloge = new PiloterHorloge(reglage);

    @Test
    @DisplayName("CA2 : une avance minimale de PT1S est acceptée")
    void doit_accepter_une_avance_d_une_seconde() {
        Duration decalage = piloterHorloge.avancer(Duration.parse("PT1S"));

        assertThat(decalage).isEqualTo(Duration.parse("PT1S"));
        assertThat(reglage.decalage()).isEqualTo(Duration.parse("PT1S"));
    }

    @Test
    @DisplayName("CA2 : une avance maximale de P30D est acceptée")
    void doit_accepter_une_avance_de_trente_jours() {
        Duration decalage = piloterHorloge.avancer(Duration.parse("P30D"));

        assertThat(decalage).isEqualTo(Duration.ofDays(30));
        assertThat(reglage.decalage()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    @DisplayName("CA2 : une avance nulle (PT0S) est refusée, décalage inchangé")
    void doit_refuser_une_avance_nulle() {
        piloterHorloge.avancer(Duration.ofMinutes(10));

        assertThatThrownBy(() -> piloterHorloge.avancer(Duration.parse("PT0S")))
                .isInstanceOf(DureeAvanceInvalideException.class);
        assertThat(reglage.decalage()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("CA2 : une avance négative (PT-5M) est refusée, décalage inchangé")
    void doit_refuser_une_avance_negative() {
        piloterHorloge.avancer(Duration.ofMinutes(10));

        assertThatThrownBy(() -> piloterHorloge.avancer(Duration.parse("PT-5M")))
                .isInstanceOf(DureeAvanceInvalideException.class);
        assertThat(reglage.decalage()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("CA2 : une avance de P30DT1S (au-delà de 30 jours) est refusée, décalage inchangé")
    void doit_refuser_une_avance_depassant_trente_jours_d_une_seconde() {
        assertThatThrownBy(() -> piloterHorloge.avancer(Duration.parse("P30DT1S")))
                .isInstanceOf(DureeAvanceInvalideException.class);
        assertThat(reglage.decalage()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("CA2 : la 13e avance de P30D dépasse le cumul de 365 jours et est refusée (décalage P360D)")
    void doit_refuser_l_avance_qui_depasse_le_cumul_maximal() {
        for (int i = 0; i < 12; i++) {
            piloterHorloge.avancer(Duration.ofDays(30));
        }

        assertThatThrownBy(() -> piloterHorloge.avancer(Duration.ofDays(30)))
                .isInstanceOf(DecalageHorlogeExcessifException.class);
        assertThat(reglage.decalage()).isEqualTo(Duration.ofDays(360));
    }

    @Test
    @DisplayName("CA2 : après le refus du cumul, P5D atteint exactement P365D et PT1S est ensuite refusé")
    void doit_accepter_le_cumul_exact_de_365_jours_puis_refuser_toute_avance() {
        for (int i = 0; i < 12; i++) {
            piloterHorloge.avancer(Duration.ofDays(30));
        }

        Duration decalage = piloterHorloge.avancer(Duration.parse("P5D"));

        assertThat(decalage).isEqualTo(Duration.ofDays(365));
        assertThatThrownBy(() -> piloterHorloge.avancer(Duration.parse("PT1S")))
                .isInstanceOf(DecalageHorlogeExcessifException.class);
        assertThat(reglage.decalage()).isEqualTo(Duration.ofDays(365));
    }

    @Test
    @DisplayName("CA2 : lire ne modifie rien et renvoie le décalage courant")
    void doit_lire_sans_effet_de_bord() {
        piloterHorloge.avancer(Duration.ofHours(1));

        Duration premiere = piloterHorloge.lire();
        Duration seconde = piloterHorloge.lire();

        assertThat(premiere).isEqualTo(Duration.ofHours(1));
        assertThat(seconde).isEqualTo(Duration.ofHours(1));
        assertThat(reglage.decalage()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("CA2 : réinitialiser remet PT0S, et reste à PT0S quand on le répète")
    void doit_reinitialiser_de_facon_idempotente() {
        piloterHorloge.avancer(Duration.ofHours(2));

        Duration premiere = piloterHorloge.reinitialiser();
        Duration seconde = piloterHorloge.reinitialiser();

        assertThat(premiere).isEqualTo(Duration.ZERO);
        assertThat(seconde).isEqualTo(Duration.ZERO);
        assertThat(reglage.decalage()).isEqualTo(Duration.ZERO);
    }
}
