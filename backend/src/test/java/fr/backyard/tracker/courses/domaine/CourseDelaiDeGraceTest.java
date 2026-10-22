package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 7.1 : délai de grâce avant de trancher une Boucle (CA1, CA2, CA3, CA8, CA9 ; RG1, RG2, RG5, RG7).
 *
 * Signatures supposées : {@code Duration Course.delaiDeGrace()} ; {@code int Course.bouclesTranchees(Instant)} ;
 * {@code OptionalInt Course.boucleEnAttenteDeDecision(Instant)} ; {@code Course.DELAI_DE_GRACE} (Duration, 3 min).
 * Course de référence : Boucle de 60 min, départ 10:00:00Z, 5 Boucles max.
 */
class CourseDelaiDeGraceTest {

    private static final Instant DEPART = Instant.parse("2026-10-21T10:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 21);

    private static Course course(StatutCourse statut, int dureeBoucleMinutes, int nombreMaxBoucles, Instant demarreeLe) {
        return Course.reconstituer(UUID.randomUUID(), "Backyard", JOUR, statut,
                new ParametresBoucle(400, dureeBoucleMinutes, 5), 10, nombreMaxBoucles, Set.of(), demarreeLe);
    }

    private static Course courseEnCours(int nombreMaxBoucles) {
        return course(StatutCourse.EN_COURS, 60, nombreMaxBoucles, DEPART);
    }

    private static Instant a(String heure) {
        return Instant.parse("2026-10-21T" + heure + "Z");
    }

    @ParameterizedTest(name = "CA1 - Boucle de {0} min : délai de grâce {1} s")
    @CsvSource({"60, 180", "12, 180", "8, 120", "1, 15"})
    @DisplayName("CA1 - le délai de grâce est le minimum entre 3 minutes et le quart de la durée de Boucle")
    void doit_plafonner_le_delai_de_grace_au_quart_de_la_duree_de_boucle(int dureeMinutes, long attenduSecondes) {
        Course course = course(StatutCourse.EN_COURS, dureeMinutes, 5, DEPART);

        assertThat(course.delaiDeGrace()).isEqualTo(Duration.ofSeconds(attenduSecondes));
    }

    @Test
    @DisplayName("CA1 - la constante du domaine vaut 3 minutes")
    void doit_fixer_la_constante_de_grace_a_trois_minutes() {
        assertThat(Course.DELAI_DE_GRACE).isEqualTo(Duration.ofMinutes(3));
    }

    @ParameterizedTest(name = "CA2 - à {0} : {1} Boucle(s) tranchée(s)")
    @CsvSource({"10:59:59.999, 0", "11:00:00, 0", "11:02:59.999, 0", "11:03:00, 1"})
    @DisplayName("CA2 - la Boucle 1 est tranchée à l'instant exact départ(2) + grâce, borne incluse")
    void doit_trancher_la_boucle_a_la_milliseconde_apres_la_grace(String heure, int attendu) {
        assertThat(courseEnCours(5).bouclesTranchees(a(heure))).isEqualTo(attendu);
    }

    @Test
    @DisplayName("CA2 - la Boucle 2 est tranchée à 12:03:00 et pas à 12:02:59.999")
    void doit_trancher_la_deuxieme_boucle_a_douze_heures_trois() {
        Course course = courseEnCours(5);

        assertThat(course.bouclesTranchees(a("12:02:59.999"))).isEqualTo(1);
        assertThat(course.bouclesTranchees(a("12:03:00"))).isEqualTo(2);
    }

    @ParameterizedTest(name = "CA3 - 3 Boucles max à {0} : {1}")
    @CsvSource({"13:03:00, 3", "14:00:00, 3", "12:59:59.999, 2"})
    @DisplayName("CA3 - le nombre de Boucles tranchées est plafonné à nombreMaxBoucles")
    void doit_plafonner_les_boucles_tranchees_au_nombre_max(String heure, int attendu) {
        assertThat(courseEnCours(3).bouclesTranchees(a(heure))).isEqualTo(attendu);
    }

    @ParameterizedTest(name = "CA3 - Course {0} : aucune Boucle tranchée")
    @EnumSource(value = StatutCourse.class, names = {"EN_PREPARATION", "TERMINEE"})
    @DisplayName("CA3 - une Course non EN_COURS n'a aucune Boucle tranchée")
    void doit_renvoyer_zero_pour_une_course_non_en_cours(StatutCourse statut) {
        Course course = course(statut, 60, 5, DEPART);

        assertThat(course.bouclesTranchees(a("20:00:00"))).isZero();
    }

    @Test
    @DisplayName("CA3 - une Course EN_COURS sans heure de départ n'a aucune Boucle tranchée")
    void doit_renvoyer_zero_sans_heure_de_depart() {
        Course course = course(StatutCourse.EN_COURS, 60, 5, null);

        assertThat(course.bouclesTranchees(a("20:00:00"))).isZero();
    }

    @Test
    @DisplayName("CA3 - un instant antérieur au départ donne zéro Boucle tranchée")
    void doit_renvoyer_zero_avant_le_depart() {
        assertThat(courseEnCours(5).bouclesTranchees(a("08:00:00"))).isZero();
    }

    @Test
    @DisplayName("CA2 - la grâce est propre à chaque Course : Boucle de 1 min, tranchée 15 s après le départ suivant")
    void doit_calculer_la_grace_avec_la_duree_de_la_course() {
        Course courte = course(StatutCourse.EN_COURS, 1, 5, DEPART);

        assertThat(courte.bouclesTranchees(a("10:01:14.999"))).isZero();
        assertThat(courte.bouclesTranchees(a("10:01:15"))).isEqualTo(1);
    }

    @Test
    @DisplayName("CA8 - M = 2 : un scan d'instant réel 11:59:59.999 reçu pendant la grâce est attribué à la Boucle 2")
    void doit_attribuer_a_la_derniere_boucle_un_scan_fait_juste_avant_sa_fin() {
        assertThat(courseEnCours(2).boucleDePassage(a("11:59:59.999"))).isEqualTo(2);
    }

    @Test
    @DisplayName("CA8 - M = 2 : un scan d'instant réel 12:00:00 lève TempsDeCourseEcouleException")
    void doit_refuser_un_scan_fait_a_la_fin_exacte_de_la_derniere_boucle() {
        Course course = courseEnCours(2);

        assertThatThrownBy(() -> course.boucleDePassage(a("12:00:00")))
                .isInstanceOf(TempsDeCourseEcouleException.class);
    }

    @Test
    @DisplayName("CA8 - M = 2 : une réintégration à 12:01:00, pendant la grâce, lève TempsDeCourseEcouleException")
    void doit_refuser_la_reintegration_pendant_la_grace_de_la_derniere_boucle() {
        Course course = courseEnCours(2);

        assertThatThrownBy(() -> course.autoriserReintegration(a("12:01:00")))
                .isInstanceOf(TempsDeCourseEcouleException.class);
    }

    @ParameterizedTest(name = "CA9 - 5 Boucles max à {0} : Boucle en attente {1}")
    @CsvSource({
            "10:59:59.999, ",
            "11:00:00, 1",
            "11:02:59.999, 1",
            "11:03:00, ",
            "15:00:00, 5",
            "15:02:59.999, 5",
            "15:03:00, "
    })
    @DisplayName("CA9 - la Boucle en attente de décision est la Boucle terminée pas encore tranchée")
    void doit_indiquer_la_boucle_en_attente_de_decision(String heure, Integer attendue) {
        var enAttente = courseEnCours(5).boucleEnAttenteDeDecision(a(heure));

        if (attendue == null) {
            assertThat(enAttente).isEmpty();
        } else {
            assertThat(enAttente).hasValue(attendue);
        }
    }

    @ParameterizedTest(name = "CA9 - Course {0} : aucune Boucle en attente")
    @EnumSource(value = StatutCourse.class, names = {"EN_PREPARATION", "TERMINEE"})
    @DisplayName("CA9 - une Course non EN_COURS n'a aucune Boucle en attente de décision")
    void doit_ne_rien_attendre_pour_une_course_non_en_cours(StatutCourse statut) {
        Course course = course(statut, 60, 5, statut == StatutCourse.EN_PREPARATION ? null : DEPART);

        assertThat(course.boucleEnAttenteDeDecision(a("11:00:30"))).isEmpty();
    }

    @Test
    @DisplayName("CA9 - une Course EN_COURS sans heure de départ n'a aucune Boucle en attente")
    void doit_ne_rien_attendre_sans_heure_de_depart() {
        assertThat(course(StatutCourse.EN_COURS, 60, 5, null).boucleEnAttenteDeDecision(a("11:00:30"))).isEmpty();
    }
}
