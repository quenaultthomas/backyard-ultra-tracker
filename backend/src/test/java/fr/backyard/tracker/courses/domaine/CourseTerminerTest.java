package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.9 : passage de la Course à TERMINEE (CA2 ; RG5).
 *
 * Signature supposée (spec §4 « Port interne », par symétrie avec {@code demarrer}, Course immuable) :
 * {@code Course Course.terminer()} renvoie la Course TERMINEE sans affecter l'instance courante ; lève une exception
 * métier non typée par la spec (testée comme {@link RuntimeException}) si la Course n'est pas EN_COURS.
 */
class CourseTerminerTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LEO = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static Course course(StatutCourse statut, Instant demarreeLe) {
        return Course.reconstituer(ID, "Backyard du jour", LocalDate.of(2026, 10, 12), statut,
                new ParametresBoucle(400, 1, 5), 10, 5, Set.of(LEO), demarreeLe);
    }

    @Test
    @DisplayName("CA2 - RG5 : une Course EN_COURS passe TERMINEE, heure de départ conservée")
    void doit_passer_une_course_en_cours_a_terminee_en_conservant_l_heure_de_depart() {
        Course terminee = course(StatutCourse.EN_COURS, T0).terminer();

        assertThat(terminee.statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(terminee.demarreeLe()).contains(T0);
    }

    @Test
    @DisplayName("CA2 - RG5 : identifiant, nom, paramètres, limites et bénévoles affectés sont conservés")
    void doit_conserver_les_autres_donnees_de_la_course() {
        Course avant = course(StatutCourse.EN_COURS, T0);

        Course terminee = avant.terminer();

        assertThat(terminee.id()).isEqualTo(ID);
        assertThat(terminee.nom()).isEqualTo(avant.nom());
        assertThat(terminee.date()).isEqualTo(avant.date());
        assertThat(terminee.parametresBoucle()).isEqualTo(avant.parametresBoucle());
        assertThat(terminee.nombreMaxParticipants()).isEqualTo(10);
        assertThat(terminee.nombreMaxBoucles()).isEqualTo(5);
        assertThat(terminee.benevolesAffectes()).containsExactly(LEO);
    }

    @Test
    @DisplayName("CA2 : la Course d'origine n'est pas affectée (reste EN_COURS)")
    void doit_laisser_la_course_d_origine_inchangee() {
        Course avant = course(StatutCourse.EN_COURS, T0);

        avant.terminer();

        assertThat(avant.statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @ParameterizedTest(name = "CA2 : une Course {0} ne peut pas être terminée, rien ne change")
    @EnumSource(value = StatutCourse.class, names = {"EN_PREPARATION", "TERMINEE"})
    void doit_refuser_de_terminer_une_course_qui_n_est_pas_en_cours(StatutCourse statut) {
        Course course = course(statut, statut == StatutCourse.TERMINEE ? T0 : null);

        assertThatThrownBy(course::terminer).isInstanceOf(RuntimeException.class);
        assertThat(course.statut()).isEqualTo(statut);
        assertThat(course.demarreeLe()).isEqualTo(statut == StatutCourse.TERMINEE ? java.util.Optional.of(T0)
                : java.util.Optional.empty());
    }

    @Test
    @DisplayName("CA2 : une Course TERMINEE n'a plus de chronométrage et plus de boucles terminées (TERMINEE n'est jamais quittée)")
    void doit_ne_plus_chronometrer_une_course_terminee() {
        Course terminee = course(StatutCourse.EN_COURS, T0).terminer();

        assertThat(terminee.chronometrage(T0.plusSeconds(90))).isEmpty();
        assertThat(terminee.bouclesTerminees(T0.plusSeconds(90))).isZero();
    }
}
