package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.4 : affectation des bénévoles dans l'agrégat Course (CA1, CA6 ; RG1, RG2, RG3, RG7). */
class CourseAffectationBenevolesTest {

    private static final UUID L1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID L2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID L3 = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 10, 3);

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - une Course nouvellement déclarée n'a aucun bénévole affecté")
    void doit_declarer_une_course_sans_benevole_affecte() {
        Course course = Course.declarer("Backyard des Crêtes", LocalDate.of(2026, 11, 14), 6706, 60, 120, 50, 24,
                AUJOURDHUI);

        assertThat(course.benevolesAffectes()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - une Course reconstituée sans affectation a un ensemble vide")
    void doit_reconstituer_une_course_sans_benevole_affecte() {
        Course course = courseAvecStatut(StatutCourse.EN_PREPARATION, Set.of());

        assertThat(course.benevolesAffectes()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - EN_PREPARATION sans bénévole : affecter {l1, l2} donne {l1, l2}")
    void doit_affecter_deux_benevoles_a_une_course_en_preparation() {
        Course course = courseAvecStatut(StatutCourse.EN_PREPARATION, Set.of());

        course.affecterBenevoles(List.of(L1, L2));

        assertThat(course.benevolesAffectes()).containsExactlyInAnyOrder(L1, L2);
    }

    @Test
    @DisplayName("CA1 - les doublons sont dédoublonnés : {l1, l1} donne {l1}")
    void doit_dedoublonner_les_benevoles_affectes() {
        Course course = courseAvecStatut(StatutCourse.EN_PREPARATION, Set.of());

        course.affecterBenevoles(List.of(L1, L1));

        assertThat(course.benevolesAffectes()).containsExactly(L1);
    }

    @Test
    @DisplayName("CA1 - affecter l'ensemble vide retire tous les bénévoles")
    void doit_retirer_tous_les_benevoles_avec_un_ensemble_vide() {
        Course course = courseAvecStatut(StatutCourse.EN_PREPARATION, Set.of(L1, L2));

        course.affecterBenevoles(List.of());

        assertThat(course.benevolesAffectes()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - l'affectation remplace l'ensemble existant : {l1, l2} puis {l3} donne {l3}")
    void doit_remplacer_l_ensemble_existant() {
        Course course = courseAvecStatut(StatutCourse.EN_PREPARATION, Set.of(L1, L2));

        course.affecterBenevoles(List.of(L3));

        assertThat(course.benevolesAffectes()).containsExactly(L3);
    }

    @Test
    @DisplayName("CA1 - EN_COURS : l'affectation reste possible et remplace l'ensemble (RG3, D2)")
    void doit_affecter_des_benevoles_a_une_course_en_cours() {
        Course course = courseAvecStatut(StatutCourse.EN_COURS, Set.of(L1));

        course.affecterBenevoles(List.of(L2, L3));

        assertThat(course.benevolesAffectes()).containsExactlyInAnyOrder(L2, L3);
    }

    @Test
    @DisplayName("CA1 - EN_COURS : doublons dédoublonnés et ensemble vide acceptés")
    void doit_dedoublonner_et_vider_une_course_en_cours() {
        Course course = courseAvecStatut(StatutCourse.EN_COURS, Set.of());

        course.affecterBenevoles(List.of(L1, L1));
        assertThat(course.benevolesAffectes()).containsExactly(L1);

        course.affecterBenevoles(List.of());
        assertThat(course.benevolesAffectes()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - TERMINEE : CourseTermineeException et ensemble inchangé")
    void doit_refuser_l_affectation_sur_une_course_terminee_sans_modifier_l_ensemble() {
        Course course = courseAvecStatut(StatutCourse.TERMINEE, Set.of(L1));

        assertThatThrownBy(() -> course.affecterBenevoles(List.of(L2, L3)))
                .isInstanceOf(CourseTermineeException.class);

        assertThat(course.benevolesAffectes()).containsExactly(L1);
    }

    @Test
    @DisplayName("CA1 - TERMINEE : même l'ensemble vide est refusé")
    void doit_refuser_meme_l_ensemble_vide_sur_une_course_terminee() {
        Course course = courseAvecStatut(StatutCourse.TERMINEE, Set.of(L1));

        assertThatThrownBy(() -> course.affecterBenevoles(List.of())).isInstanceOf(CourseTermineeException.class);

        assertThat(course.benevolesAffectes()).containsExactly(L1);
    }

    @Test
    @DisplayName("CA1 - l'ensemble exposé ne permet pas de modifier l'affectation de l'extérieur")
    void doit_exposer_un_ensemble_non_modifiable() {
        Course course = courseAvecStatut(StatutCourse.EN_PREPARATION, Set.of(L1));

        assertThatThrownBy(() -> course.benevolesAffectes().add(L2))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(course.benevolesAffectes()).containsExactly(L1);
    }

    // ---------- CA6 ----------

    @Test
    @DisplayName("CA6 - modifier une Course EN_PREPARATION laisse l'ensemble des bénévoles inchangé")
    void doit_conserver_les_benevoles_affectes_quand_on_modifie_la_course() {
        Course course = courseAvecStatut(StatutCourse.EN_PREPARATION, Set.of(L1, L2));

        Course modifiee = course.modifier("Backyard des Alpes", LocalDate.of(2026, 12, 5), 8000, 45, 0, 80, 12,
                AUJOURDHUI);

        assertThat(modifiee.nom()).isEqualTo("Backyard des Alpes");
        assertThat(modifiee.benevolesAffectes()).containsExactlyInAnyOrder(L1, L2);
    }

    private static Course courseAvecStatut(StatutCourse statut, Set<UUID> benevoles) {
        return Course.reconstituer(UUID.fromString("00000000-0000-0000-0000-0000000000c1"), "Backyard des Crêtes",
                LocalDate.of(2026, 11, 14), statut, new ParametresBoucle(6706, 60, 120), 50, 24, benevoles);
    }
}
