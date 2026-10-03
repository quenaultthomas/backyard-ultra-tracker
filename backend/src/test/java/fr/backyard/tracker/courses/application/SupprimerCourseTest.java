package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonSupprimableException;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.5 : cas d'usage SupprimerCourse (CA2 ; RG1, RG5, RG6). */
class SupprimerCourseTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID ID_C = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID ID_D = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotCoursesAvecSuppressionDeTest depot = new DepotCoursesAvecSuppressionDeTest();
    private final SupprimerCourse supprimerCourse = new SupprimerCourse(depot);

    @Test
    @DisplayName("CA2 - A EN_PREPARATION : A n'existe plus et D est intacte")
    void doit_supprimer_une_course_en_preparation_sans_toucher_aux_autres() {
        garnirLesDepots();

        supprimerCourse.executer(ID_A);

        assertThat(depot.parId(ID_A)).isEmpty();
        assertThat(depot.parId(ID_D)).isPresent();
        assertThat(depot.parId(ID_D).orElseThrow().nom()).isEqualTo("D");
        assertThat(depot.toutes()).extracting(c -> c.id()).containsExactlyInAnyOrder(ID_B, ID_C, ID_D);
    }

    @Test
    @DisplayName("CA2 - B EN_COURS : CourseNonSupprimableException, B toujours présente")
    void doit_refuser_la_suppression_d_une_course_en_cours() {
        garnirLesDepots();

        assertThatThrownBy(() -> supprimerCourse.executer(ID_B)).isInstanceOf(CourseNonSupprimableException.class);

        assertThat(depot.parId(ID_B)).isPresent();
        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.toutes()).hasSize(4);
    }

    @Test
    @DisplayName("CA2 - C TERMINEE : CourseNonSupprimableException, C toujours présente")
    void doit_refuser_la_suppression_d_une_course_terminee() {
        garnirLesDepots();

        assertThatThrownBy(() -> supprimerCourse.executer(ID_C)).isInstanceOf(CourseNonSupprimableException.class);

        assertThat(depot.parId(ID_C)).isPresent();
        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.toutes()).hasSize(4);
    }

    @Test
    @DisplayName("CA2 - identifiant inconnu : CourseIntrouvableException, dépôt inchangé")
    void doit_refuser_la_suppression_d_une_course_inconnue() {
        garnirLesDepots();

        assertThatThrownBy(() -> supprimerCourse.executer(ID_INCONNU)).isInstanceOf(CourseIntrouvableException.class);

        assertThat(depot.toutes()).hasSize(4);
        assertThat(depot.suppressions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - la Course est chargée par parIdPourModification (verrou)")
    void doit_charger_la_course_sous_verrou_avant_de_la_supprimer() {
        garnirLesDepots();

        supprimerCourse.executer(ID_A);

        assertThat(depot.chargementsPourModification).containsExactly(ID_A);
        assertThat(depot.suppressions).containsExactly(ID_A);
    }

    @Test
    @DisplayName("CA2 - le refus de statut charge aussi la Course sous verrou (statut relu sous verrou)")
    void doit_relire_le_statut_sous_verrou_meme_en_cas_de_refus() {
        garnirLesDepots();

        assertThatThrownBy(() -> supprimerCourse.executer(ID_B)).isInstanceOf(CourseNonSupprimableException.class);

        assertThat(depot.chargementsPourModification).containsExactly(ID_B);
    }

    @Test
    @DisplayName("CA2 - seconde suppression de A : CourseIntrouvableException (non idempotent)")
    void doit_refuser_une_seconde_suppression_de_la_meme_course() {
        garnirLesDepots();
        supprimerCourse.executer(ID_A);

        assertThatThrownBy(() -> supprimerCourse.executer(ID_A)).isInstanceOf(CourseIntrouvableException.class);

        assertThat(depot.suppressions).containsExactly(ID_A);
        assertThat(depot.toutes()).hasSize(3);
    }

    private void garnirLesDepots() {
        LocalDate date = LocalDate.of(2026, 11, 14);
        depot.enregistrer(DepotCoursesDeTest.course(ID_A, "A", date, StatutCourse.EN_PREPARATION));
        depot.enregistrer(DepotCoursesDeTest.course(ID_B, "B", date, StatutCourse.EN_COURS));
        depot.enregistrer(DepotCoursesDeTest.course(ID_C, "C", date, StatutCourse.TERMINEE));
        depot.enregistrer(DepotCoursesAvecAffectationsDeTest.course(ID_D, "D", date, StatutCourse.EN_PREPARATION,
                Set.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))));
    }
}
