package fr.backyard.tracker.courses.domaine;

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
 * Tests de l'incrément 4.12 : éligibilité de la Course à la réintégration (CA2 ; RG1, RG6).
 *
 * Signature supposée : {@code void autoriserReintegration(Instant maintenant)} : EN_PREPARATION ->
 * CourseNonDemarreeException ; TERMINEE -> {@code CourseTermineePourReintegrationException} (nouvelle exception du
 * domaine, constructeur à un UUID de Course, comme CourseTermineePourAbandonException) ; EN_COURS sans demarreeLe ->
 * HeureDeDepartInconnueException ; EN_COURS dont le temps de la dernière Boucle est écoulé
 * ({@code maintenant >= demarreeLe + nombreMaxBoucles x durée}) -> TempsDeCourseEcouleException ; sinon accepté.
 * Course X : Boucles de 1 min, M = 5, t0 = 14:00:00Z, fin de la Boucle 5 = 14:05:00.000Z.
 */
class CourseReintegrationTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");

    private static Course course(StatutCourse statut, Instant demarreeLe) {
        return Course.reconstituer(ID, "Backyard", LocalDate.of(2026, 10, 12), statut,
                new ParametresBoucle(400, 1, 5), 10, 5, Set.of(), demarreeLe);
    }

    private static Course enCours() {
        return course(StatutCourse.EN_COURS, T0);
    }

    @Test
    @DisplayName("CA2 - RG1 : une Course EN_PREPARATION refuse la réintégration (CourseNonDemarreeException)")
    void doit_refuser_la_reintegration_d_une_course_en_preparation() {
        Course enPreparation = course(StatutCourse.EN_PREPARATION, null);

        assertThatThrownBy(() -> enPreparation.autoriserReintegration(T0))
                .isInstanceOf(CourseNonDemarreeException.class);
    }

    @Test
    @DisplayName("CA2 - RG1 : une Course TERMINEE n'est jamais rouverte, même dans le temps de course")
    void doit_refuser_la_reintegration_d_une_course_terminee() {
        Course terminee = course(StatutCourse.TERMINEE, T0);

        assertThatThrownBy(() -> terminee.autoriserReintegration(Instant.parse("2026-10-12T14:01:30Z")))
                .isInstanceOf(CourseTermineePourReintegrationException.class);
    }

    @Test
    @DisplayName("CA2 - RG1 : une Course TERMINEE sans heure de départ est aussi refusée comme terminée")
    void doit_refuser_comme_terminee_une_course_terminee_sans_heure_de_depart() {
        Course terminee = course(StatutCourse.TERMINEE, null);

        assertThatThrownBy(() -> terminee.autoriserReintegration(T0))
                .isInstanceOf(CourseTermineePourReintegrationException.class);
    }

    @Test
    @DisplayName("CA2 - RG1 : une Course EN_COURS sans demarreeLe lève HeureDeDepartInconnueException")
    void doit_refuser_la_reintegration_sans_heure_de_depart() {
        Course sansDepart = course(StatutCourse.EN_COURS, null);

        assertThatThrownBy(() -> sansDepart.autoriserReintegration(T0))
                .isInstanceOf(HeureDeDepartInconnueException.class);
    }

    @ParameterizedTest(name = "CA2 - RG1/RG6 : à {0} la réintégration est acceptée")
    @ValueSource(strings = {"2026-10-12T14:00:00Z", "2026-10-12T14:00:59.999Z", "2026-10-12T14:01:00Z",
            "2026-10-12T14:04:00Z", "2026-10-12T14:04:59.999Z"})
    @DisplayName("CA2 - RG1/RG6 : acceptée du départ jusqu'à 1 ms avant la fin de la dernière Boucle")
    void doit_accepter_la_reintegration_pendant_le_temps_de_course(String instant) {
        assertThatCode(() -> enCours().autoriserReintegration(Instant.parse(instant))).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "CA2 - RG1/RG6 : à {0} le temps de course est écoulé")
    @ValueSource(strings = {"2026-10-12T14:05:00Z", "2026-10-12T14:05:00.001Z", "2026-10-12T14:09:00Z"})
    @DisplayName("CA2 - RG1/RG6 : refusée à t0 + M x durée exactement et après (TempsDeCourseEcouleException)")
    void doit_refuser_la_reintegration_quand_le_temps_de_course_est_ecoule(String instant) {
        assertThatThrownBy(() -> enCours().autoriserReintegration(Instant.parse(instant)))
                .isInstanceOf(TempsDeCourseEcouleException.class);
    }

    @Test
    @DisplayName("CA2 - RG1 : le temps de course dépend du nombre max de Boucles de la Course (M = 2 -> fin à 14:02:00)")
    void doit_utiliser_le_nombre_max_de_boucles_de_la_course() {
        Course w = Course.reconstituer(ID, "Backyard W", LocalDate.of(2026, 10, 12), StatutCourse.EN_COURS,
                new ParametresBoucle(400, 1, 5), 10, 2, Set.of(), T0);

        assertThatCode(() -> w.autoriserReintegration(Instant.parse("2026-10-12T14:01:59.999Z")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> w.autoriserReintegration(Instant.parse("2026-10-12T14:02:00Z")))
                .isInstanceOf(TempsDeCourseEcouleException.class);
    }
}
