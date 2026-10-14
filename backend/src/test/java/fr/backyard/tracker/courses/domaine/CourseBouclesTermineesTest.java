package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.8 : Boucles terminées à un instant (CA1 ; RG1, RG6).
 *
 * Signature supposée : {@code int Course.bouclesTerminees(Instant maintenant)} (0 hors EN_COURS, sans heure de
 * départ ou avant le départ ; sinon min(floor((t - t0) / D), M)).
 * Course X : boucles de 1 min, M = 5, t0 = 14:00:00Z. Course Y : boucles de 90 min.
 */
class CourseBouclesTermineesTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);

    private static Course course(StatutCourse statut, int dureeBoucleMinutes, Instant demarreeLe) {
        return Course.reconstituer(UUID.randomUUID(), "Backyard", JOUR, statut,
                new ParametresBoucle(400, dureeBoucleMinutes, 5), 10, 5, Set.of(), demarreeLe);
    }

    private static Course courseXEnCours() {
        return course(StatutCourse.EN_COURS, 1, T0);
    }

    @ParameterizedTest(name = "CA1 - X à t0 + {0} ms : {1} boucle(s) terminée(s)")
    @CsvSource({
            "-1000, 0",
            "0, 0",
            "59999, 0",
            "60000, 1",
            "119999, 1",
            "120000, 2",
            "300000, 5",
            "360000, 5",
            "86400000, 5"
    })
    @DisplayName("CA1 - bornes exactes à la milliseconde et plafond à nombreMaxBoucles")
    void doit_compter_les_boucles_terminees_a_la_milliseconde(long decalageMillisecondes, int attendu) {
        Instant instant = T0.plusMillis(decalageMillisecondes);

        assertThat(courseXEnCours().bouclesTerminees(instant)).isEqualTo(attendu);
    }

    @ParameterizedTest(name = "CA1 - Course {0} : 0 boucle terminée")
    @EnumSource(value = StatutCourse.class, names = {"EN_PREPARATION", "TERMINEE"})
    @DisplayName("CA1 - une Course non EN_COURS n'a aucune boucle terminée, même longtemps après son départ")
    void doit_renvoyer_zero_pour_une_course_qui_n_est_pas_en_cours(StatutCourse statut) {
        Course course = course(statut, 1, T0);

        assertThat(course.bouclesTerminees(T0.plus(Duration.ofDays(1)))).isZero();
    }

    @Test
    @DisplayName("CA1 - Course EN_COURS sans heure de départ : 0 boucle terminée")
    void doit_renvoyer_zero_pour_une_course_en_cours_sans_heure_de_depart() {
        Course course = course(StatutCourse.EN_COURS, 1, null);

        assertThat(course.bouclesTerminees(T0.plus(Duration.ofDays(1)))).isZero();
    }

    @ParameterizedTest(name = "CA1 - Y (90 min) à t0 + {0} min : {1}")
    @CsvSource({"89, 0", "90, 1", "179, 1", "180, 2"})
    @DisplayName("CA1 - la durée de boucle est propre à la Course")
    void doit_utiliser_la_duree_de_boucle_de_la_course(long minutes, int attendu) {
        Course courseY = course(StatutCourse.EN_COURS, 90, T0);

        assertThat(courseY.bouclesTerminees(T0.plus(Duration.ofMinutes(minutes)))).isEqualTo(attendu);
    }

    @Test
    @DisplayName("CA1 - cohérence avec chronometrage : bouclesTerminees = boucleCourante - 1 avant la fin de temps")
    void doit_etre_coherent_avec_la_boucle_courante_du_chronometrage() {
        Course course = courseXEnCours();

        for (long ms : new long[] {0, 1, 59_999, 60_000, 60_001, 119_999, 120_000, 239_999, 240_000, 299_999}) {
            Instant instant = T0.plusMillis(ms);
            int boucleCourante = course.chronometrage(instant).orElseThrow().boucleCourante();
            assertThat(course.bouclesTerminees(instant)).as("à t0 + %d ms", ms).isEqualTo(boucleCourante - 1);
        }
    }

    @Test
    @DisplayName("CA1 - cohérence avec chronometrage : après boucleMaxTerminee, bouclesTerminees vaut nombreMaxBoucles")
    void doit_valoir_le_nombre_max_de_boucles_une_fois_la_derniere_boucle_terminee() {
        Course course = courseXEnCours();

        for (long ms : new long[] {300_000, 300_001, 600_000}) {
            Instant instant = T0.plusMillis(ms);
            assertThat(course.chronometrage(instant).orElseThrow().boucleMaxTerminee()).isTrue();
            assertThat(course.bouclesTerminees(instant)).as("à t0 + %d ms", ms).isEqualTo(course.nombreMaxBoucles());
        }
    }
}
