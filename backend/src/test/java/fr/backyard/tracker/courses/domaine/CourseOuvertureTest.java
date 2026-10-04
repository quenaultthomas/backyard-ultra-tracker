package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tests de l'incrément 3.1 : une Course est ouverte aux inscriptions tant qu'elle est EN_PREPARATION (CA1 ; RG1). */
class CourseOuvertureTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Test
    @DisplayName("CA1 - une Course EN_PREPARATION est ouverte")
    void doit_etre_ouverte_quand_la_course_est_en_preparation() {
        assertThat(course(StatutCourse.EN_PREPARATION, LocalDate.of(2026, 11, 14)).estOuverte()).isTrue();
    }

    @Test
    @DisplayName("CA1 - une Course EN_PREPARATION à la date passée reste ouverte")
    void doit_rester_ouverte_meme_si_la_date_est_passee() {
        assertThat(course(StatutCourse.EN_PREPARATION, LocalDate.of(2020, 1, 1)).estOuverte()).isTrue();
    }

    @ParameterizedTest(name = "CA1 - {0} : fermée aux inscriptions")
    @EnumSource(value = StatutCourse.class, names = {"EN_COURS", "TERMINEE"})
    void doit_etre_fermee_quand_la_course_est_demarree_ou_terminee(StatutCourse statut) {
        assertThat(course(statut, LocalDate.of(2026, 11, 14)).estOuverte()).isFalse();
    }

    private static Course course(StatutCourse statut, LocalDate date) {
        return Course.reconstituer(ID, "Backyard des Crêtes", date, statut, new ParametresBoucle(6706, 60, 120), 50,
                24);
    }
}
