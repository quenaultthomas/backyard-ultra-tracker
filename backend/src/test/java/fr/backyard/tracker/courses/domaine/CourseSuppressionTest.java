package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tests de l'incrément 2.5 : règle de statut de la suppression d'une Course (CA1 ; RG1). */
class CourseSuppressionTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    @DisplayName("CA1 - une Course EN_PREPARATION est suppressible")
    void doit_autoriser_la_suppression_d_une_course_en_preparation() {
        assertThatCode(() -> course(StatutCourse.EN_PREPARATION).verifierSuppressible()).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA1 - {0} : suppression refusée")
    @EnumSource(value = StatutCourse.class, names = {"EN_COURS", "TERMINEE"})
    void doit_refuser_la_suppression_d_une_course_demarree_ou_terminee(StatutCourse statut) {
        assertThatThrownBy(() -> course(statut).verifierSuppressible())
                .isInstanceOf(CourseNonSupprimableException.class);
    }

    private static Course course(StatutCourse statut) {
        return Course.reconstituer(ID, "Backyard des Crêtes", LocalDate.of(2026, 11, 14), statut,
                new ParametresBoucle(6706, 60, 120), 50, 24);
    }
}
