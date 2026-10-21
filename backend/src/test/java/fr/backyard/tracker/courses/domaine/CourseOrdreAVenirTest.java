package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 6.4 : comparateur {@code Course.ORDRE_A_VENIR}, seule définition de l'ordre « à venir » (CA2, RG4). */
class CourseOrdreAVenirTest {

    @Test
    @DisplayName("CA2 - la date la plus proche passe d'abord, quel que soit l'ordre d'entrée")
    void doit_placer_la_date_la_plus_proche_en_premier() {
        Course loin = course(1, "Backyard Alpha", "2026-11-20");
        Course proche = course(2, "Backyard Zeta", "2026-11-05");

        assertThat(List.of(loin, proche).stream().sorted(Course.ORDRE_A_VENIR).toList())
                .containsExactly(proche, loin);
    }

    @Test
    @DisplayName("CA2 - à date égale, le nom est comparé sans tenir compte de la casse")
    void doit_comparer_les_noms_sans_tenir_compte_de_la_casse() {
        Course beta = course(1, "backyard beta", "2026-11-05");
        Course gamma = course(2, "Backyard Gamma", "2026-11-05");

        assertThat(List.of(gamma, beta).stream().sorted(Course.ORDRE_A_VENIR).toList())
                .containsExactly(beta, gamma);
    }

    @Test
    @DisplayName("CA2 - à date et nom égaux, l'identifiant départage")
    void doit_departager_par_identifiant() {
        Course premiere = course(1, "Backyard Gamma", "2026-11-05");
        Course seconde = course(2, "Backyard Gamma", "2026-11-05");

        assertThat(List.of(seconde, premiere).stream().sorted(Course.ORDRE_A_VENIR).toList())
                .containsExactly(premiere, seconde);
    }

    @Test
    @DisplayName("CA2 - ORDRE_A_VENIR est l'inverse de ORDRE_DE_LISTE sur la date seulement")
    void doit_differer_de_l_ordre_de_liste_par_le_sens_de_la_date() {
        Course proche = course(1, "Backyard Alpha", "2026-11-05");
        Course loin = course(2, "Backyard Alpha", "2026-11-20");

        assertThat(Course.ORDRE_A_VENIR.compare(proche, loin)).isNegative();
        assertThat(Course.ORDRE_DE_LISTE.compare(proche, loin)).isPositive();
    }

    private static Course course(int n, String nom, String date) {
        return Course.reconstituer(UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n)), nom,
                LocalDate.parse(date), StatutCourse.EN_PREPARATION, new ParametresBoucle(6706, 60, 120), 50, 24);
    }
}
