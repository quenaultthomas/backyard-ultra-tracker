package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests de l'incrément 4.11 : règles de Course de l'abandon manuel (CA1 ; RG1, RG4, RG5).
 *
 * Signatures supposées :
 * - {@code void autoriserAbandonManuel()} : EN_PREPARATION -> CourseNonDemarreeException ; TERMINEE ->
 *   {@code CourseTermineePourAbandonException} (nouvelle exception du domaine, constructeur à un UUID de Course,
 *   comme CourseNonDemarreeException) ; EN_COURS (avec ou sans demarreeLe) -> accepté ;
 * - {@code Course terminerSiPlusAucunCoureurEnCourse(int nombreInscriptionsEnCourse)} : nombre d'Inscriptions
 *   EN_COURSE restantes après l'abandon ; 0 -> Course TERMINEE (demarreeLe conservé), 1 ou plus -> Course renvoyée
 *   inchangée (EN_COURS). La Course d'origine n'est jamais modifiée.
 */
class CourseAbandonManuelTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");

    private static Course course(StatutCourse statut, Instant demarreeLe) {
        return Course.reconstituer(ID, "Backyard", LocalDate.of(2026, 10, 12), statut,
                new ParametresBoucle(400, 1, 5), 10, 5, Set.of(), demarreeLe);
    }

    @Test
    @DisplayName("CA1 - RG1 : une Course EN_PREPARATION refuse l'abandon manuel (CourseNonDemarreeException)")
    void doit_refuser_l_abandon_manuel_d_une_course_en_preparation() {
        Course enPreparation = course(StatutCourse.EN_PREPARATION, null);

        assertThatThrownBy(enPreparation::autoriserAbandonManuel).isInstanceOf(CourseNonDemarreeException.class);
    }

    @Test
    @DisplayName("CA1 - RG1 : une Course TERMINEE refuse l'abandon manuel (CourseTermineePourAbandonException)")
    void doit_refuser_l_abandon_manuel_d_une_course_terminee() {
        Course terminee = course(StatutCourse.TERMINEE, T0);

        assertThatThrownBy(terminee::autoriserAbandonManuel).isInstanceOf(CourseTermineePourAbandonException.class);
    }

    @Test
    @DisplayName("CA1 - RG1 : une Course EN_COURS accepte l'abandon manuel")
    void doit_accepter_l_abandon_manuel_d_une_course_en_cours() {
        assertThatCode(() -> course(StatutCourse.EN_COURS, T0).autoriserAbandonManuel()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - RG1 : une Course EN_COURS sans demarreeLe reste éligible (l'horloge n'intervient pas)")
    void doit_accepter_l_abandon_manuel_d_une_course_en_cours_sans_heure_de_depart() {
        assertThatCode(() -> course(StatutCourse.EN_COURS, null).autoriserAbandonManuel())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - RG5 : aucune Inscription EN_COURSE restante : la Course est TERMINEE, demarreeLe conservé")
    void doit_terminer_la_course_quand_plus_personne_n_est_en_course() {
        Course enCours = course(StatutCourse.EN_COURS, T0);

        Course apres = enCours.terminerSiPlusAucunCoureurEnCourse(0);

        assertThat(apres.statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(apres.demarreeLe()).contains(T0);
        assertThat(apres.id()).isEqualTo(ID);
        assertThat(apres.parametresBoucle()).isEqualTo(enCours.parametresBoucle());
    }

    @ParameterizedTest(name = "CA1 - RG5 : {0} Inscription(s) EN_COURSE restante(s) : la Course reste EN_COURS")
    @ValueSource(ints = {1, 2, 10})
    @DisplayName("CA1 - RG5 : tant qu'un coureur est en course, la Course n'est pas terminée")
    void doit_laisser_la_course_en_cours_tant_qu_un_coureur_est_en_course(int restantes) {
        Course apres = course(StatutCourse.EN_COURS, T0).terminerSiPlusAucunCoureurEnCourse(restantes);

        assertThat(apres.statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(apres.demarreeLe()).contains(T0);
    }

    @Test
    @DisplayName("CA1 - RG5 : la Course d'origine n'est pas modifiée par la règle de fin")
    void doit_laisser_la_course_d_origine_inchangee() {
        Course enCours = course(StatutCourse.EN_COURS, T0);

        enCours.terminerSiPlusAucunCoureurEnCourse(0);

        assertThat(enCours.statut()).isEqualTo(StatutCourse.EN_COURS);
    }
}
