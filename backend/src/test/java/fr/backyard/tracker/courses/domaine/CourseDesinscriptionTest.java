package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tests de l'incrément 3.4 : la désinscription n'est possible que tant que la Course est EN_PREPARATION (CA3 ; RG2). */
class CourseDesinscriptionTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Test
    @DisplayName("CA3 - une Course EN_PREPARATION autorise la désinscription")
    void doit_autoriser_la_desinscription_quand_la_course_est_en_preparation() {
        assertThatCode(() -> course(StatutCourse.EN_PREPARATION).autoriserDesinscription())
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA3 - {0} : désinscription impossible")
    @EnumSource(value = StatutCourse.class, names = {"EN_COURS", "TERMINEE"})
    void doit_refuser_la_desinscription_quand_la_course_est_demarree_ou_terminee(StatutCourse statut) {
        assertThatThrownBy(() -> course(statut).autoriserDesinscription())
                .isInstanceOf(DesinscriptionImpossibleException.class);
    }

    private static Course course(StatutCourse statut) {
        return Course.reconstituer(ID, "Backyard des Crêtes", LocalDate.of(2026, 11, 14), statut,
                new ParametresBoucle(6706, 60, 120), 3, 24);
    }
}
