package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tests de l'incrément 2.3 : contrôle de statut du logo, partagé avec Course.modifier (CA4). */
class CourseControleLogoTest {

    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 10, 3);
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Test
    @DisplayName("CA4 - une Course EN_PREPARATION autorise la modification du logo")
    void doit_autoriser_la_modification_du_logo_d_une_course_en_preparation() {
        assertThatCode(() -> course(StatutCourse.EN_PREPARATION).autoriserModification()).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA4 - {0} : modification du logo refusée")
    @EnumSource(value = StatutCourse.class, names = {"EN_COURS", "TERMINEE"})
    void doit_refuser_la_modification_du_logo_d_une_course_demarree_ou_terminee(StatutCourse statut) {
        assertThatThrownBy(() -> course(statut).autoriserModification())
                .isInstanceOf(CourseNonModifiableException.class);
    }

    @ParameterizedTest(name = "CA4 - {0} : modifier et le contrôle de logo lèvent la même exception")
    @EnumSource(value = StatutCourse.class, names = {"EN_COURS", "TERMINEE"})
    void doit_lever_la_meme_exception_que_modifier_pour_le_meme_statut(StatutCourse statut) {
        Course course = course(statut);

        Throwable pourLogo = captureControleLogo(course);
        Throwable pourModification = captureModification(course);

        org.assertj.core.api.Assertions.assertThat(pourLogo).isInstanceOf(CourseNonModifiableException.class);
        org.assertj.core.api.Assertions.assertThat(pourModification).isInstanceOf(pourLogo.getClass());
        org.assertj.core.api.Assertions.assertThat(pourModification.getMessage()).isEqualTo(pourLogo.getMessage());
    }

    @Test
    @DisplayName("CA4 - le contrôle de logo et Course.modifier s'accordent aussi pour EN_PREPARATION (aucune exception)")
    void doit_accepter_dans_les_deux_cas_une_course_en_preparation() {
        Course course = course(StatutCourse.EN_PREPARATION);

        assertThatCode(course::autoriserModification).doesNotThrowAnyException();
        assertThatCode(() -> modifier(course)).doesNotThrowAnyException();
    }

    private static Throwable captureControleLogo(Course course) {
        return org.assertj.core.api.Assertions.catchThrowable(course::autoriserModification);
    }

    private static Throwable captureModification(Course course) {
        return org.assertj.core.api.Assertions.catchThrowable(() -> modifier(course));
    }

    private static Course modifier(Course course) {
        return course.modifier("Backyard des Alpes", LocalDate.of(2026, 12, 5), 8000, 45, 0, 80, 12, AUJOURDHUI);
    }

    private static Course course(StatutCourse statut) {
        return Course.reconstituer(ID, "Backyard des Crêtes", LocalDate.of(2026, 11, 14), statut,
                new ParametresBoucle(6706, 60, 120), 50, 24);
    }
}
