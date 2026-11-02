package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException.CourseEnCours;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 9.3, CA4 / RG12 : l'exception métier ne divulgue ni pseudo ni identifiant de Compte.
 * Signature attendue : {@code new BenevoleAffecteCourseEnCoursException(List<CourseEnCours>)}, accesseur
 * {@code courses()}, et {@code record BenevoleAffecteCourseEnCoursException.CourseEnCours(UUID id, String nom)}.
 * L'exception ne reçoit jamais le Compte : elle ne peut donc contenir que des données de Courses.
 */
class BenevoleAffecteCourseEnCoursExceptionTest {

    private static final UUID ID_COURSE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final BenevoleAffecteCourseEnCoursException exception =
            new BenevoleAffecteCourseEnCoursException(List.of(new CourseEnCours(ID_COURSE, "Backyard du jour")));

    @Test
    @DisplayName("CA4 - porte la liste des Courses EN_COURS bloquantes, identifiant et nom")
    void doit_porter_les_courses_bloquantes() {
        assertThat(exception.courses()).containsExactly(new CourseEnCours(ID_COURSE, "Backyard du jour"));
    }

    @Test
    @DisplayName("CA4 - le message nomme la Course et ne contient ni pseudo ni identifiant")
    void doit_nommer_la_course_sans_pseudo_ni_identifiant_dans_le_message() {
        assertThat(exception.getMessage())
                .contains("Backyard du jour")
                .doesNotContain("Léo")
                .doesNotContain(ID_COURSE.toString());
    }

    @Test
    @DisplayName("CA4 - le message nomme toutes les Courses quand il y en a plusieurs")
    void doit_nommer_toutes_les_courses_bloquantes() {
        var deux = new BenevoleAffecteCourseEnCoursException(List.of(
                new CourseEnCours(ID_COURSE, "alpha"),
                new CourseEnCours(UUID.fromString("00000000-0000-0000-0000-0000000000c2"), "Zèbre")));

        assertThat(deux.getMessage()).contains("alpha").contains("Zèbre");
    }

    @Test
    @DisplayName("CA4 - toString ne contient aucun pseudo")
    void doit_masquer_tout_pseudo_dans_to_string() {
        assertThat(exception.toString()).doesNotContain("Léo");
    }
}
