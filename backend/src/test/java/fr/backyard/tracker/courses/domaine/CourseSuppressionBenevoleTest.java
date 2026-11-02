package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 9.3, CA1 (partie Course) : règles de statut de la suppression d'un bénévole (RG3, RG4).
 * Signatures attendues : {@code boolean Course.bloqueLaSuppressionDUnBenevole()} (vrai seulement si EN_COURS, unique
 * définition de la règle) et {@code boolean Course.retirerBenevoleSupprime(UUID benevoleId)} (vrai si le bénévole
 * était affecté et a été retiré ; jamais de modification d'une Course EN_COURS).
 */
class CourseSuppressionBenevoleTest {

    private static final UUID LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID MARC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static Course course(StatutCourse statut, Set<UUID> benevoles) {
        return Course.reconstituer(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Backyard des Crêtes",
                LocalDate.of(2026, 11, 14), statut, new ParametresBoucle(6706, 60, 120), 50, 24, benevoles);
    }

    @Test
    @DisplayName("CA1 - une Course EN_COURS bloque la suppression d'un bénévole")
    void doit_bloquer_la_suppression_quand_la_course_est_en_cours() {
        assertThat(course(StatutCourse.EN_COURS, Set.of(LEO)).bloqueLaSuppressionDUnBenevole()).isTrue();
    }

    @Test
    @DisplayName("CA1 - une Course EN_PREPARATION ne bloque pas la suppression d'un bénévole")
    void doit_laisser_supprimer_quand_la_course_est_en_preparation() {
        assertThat(course(StatutCourse.EN_PREPARATION, Set.of(LEO)).bloqueLaSuppressionDUnBenevole()).isFalse();
    }

    @Test
    @DisplayName("CA1 - une Course TERMINEE ne bloque pas la suppression d'un bénévole")
    void doit_laisser_supprimer_quand_la_course_est_terminee() {
        assertThat(course(StatutCourse.TERMINEE, Set.of(LEO)).bloqueLaSuppressionDUnBenevole()).isFalse();
    }

    @Test
    @DisplayName("CA1 - le retrait d'un bénévole d'une Course EN_PREPARATION retourne vrai et l'ôte des affectés")
    void doit_retirer_le_benevole_d_une_course_en_preparation() {
        Course course = course(StatutCourse.EN_PREPARATION, Set.of(LEO, MARC));

        boolean retire = course.retirerBenevoleSupprime(LEO);

        assertThat(retire).isTrue();
        assertThat(course.benevolesAffectes()).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA1 - le retrait d'un bénévole d'une Course TERMINEE retourne vrai et l'ôte des affectés")
    void doit_retirer_le_benevole_d_une_course_terminee() {
        Course course = course(StatutCourse.TERMINEE, Set.of(LEO, MARC));

        boolean retire = course.retirerBenevoleSupprime(LEO);

        assertThat(retire).isTrue();
        assertThat(course.benevolesAffectes()).containsExactly(MARC);
        assertThat(course.statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA1 - le retrait d'un bénévole non affecté retourne faux sans rien modifier")
    void doit_retourner_faux_sans_modification_pour_un_benevole_non_affecte() {
        Course course = course(StatutCourse.EN_PREPARATION, Set.of(MARC));

        boolean retire = course.retirerBenevoleSupprime(LEO);

        assertThat(retire).isFalse();
        assertThat(course.benevolesAffectes()).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA1 - une Course EN_COURS n'est jamais modifiée par le retrait, même si le bénévole y est affecté")
    void doit_ne_jamais_modifier_une_course_en_cours() {
        Course course = course(StatutCourse.EN_COURS, Set.of(LEO, MARC));

        boolean retire = course.retirerBenevoleSupprime(LEO);

        assertThat(retire).isFalse();
        assertThat(course.benevolesAffectes()).containsExactlyInAnyOrder(LEO, MARC);
        assertThat(course.statut()).isEqualTo(StatutCourse.EN_COURS);
    }
}
