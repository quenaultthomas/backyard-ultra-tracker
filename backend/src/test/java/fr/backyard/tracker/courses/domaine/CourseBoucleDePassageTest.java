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

/**
 * Tests de l'incrément 4.4a : Boucle d'un Passage (CA1 ; RG4 à RG6, RG19).
 * Signatures attendues : {@code int Course.boucleDePassage(Instant instantDuScan)} ;
 * exceptions {@code HeureDeDepartInconnueException}, {@code ScanAnterieurAuDepartException},
 * {@code TempsDeCourseEcouleException}, {@code CourseNonDemarreeException} (existante) et, pour une Course TERMINEE,
 * {@code CourseTermineePourPassageException} (dédiée, voir le retour du testeur).
 */
class CourseBoucleDePassageTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:03:27Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);

    @Test
    @DisplayName("CA1 - au départ exact (e = 0) : Boucle 1")
    void doit_placer_un_scan_au_depart_exact_sur_la_boucle_1() {
        assertThat(courseX().boucleDePassage(T0)).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 - une milliseconde avant la fin de la Boucle 1 (e = 59 999 ms) : Boucle 1")
    void doit_placer_un_scan_une_milliseconde_avant_la_frontiere_sur_la_boucle_1() {
        assertThat(courseX().boucleDePassage(T0.plusMillis(59_999))).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 - à la frontière exacte (e = 60 000 ms) : Boucle 2")
    void doit_placer_un_scan_a_la_frontiere_exacte_sur_la_boucle_suivante() {
        assertThat(courseX().boucleDePassage(T0.plusMillis(60_000))).isEqualTo(2);
    }

    @Test
    @DisplayName("CA1 - au départ de la dernière Boucle (e = 240 000 ms) : Boucle 5")
    void doit_placer_un_scan_au_depart_de_la_derniere_boucle_sur_la_boucle_5() {
        assertThat(courseX().boucleDePassage(T0.plusMillis(240_000))).isEqualTo(5);
    }

    @Test
    @DisplayName("CA1 - une milliseconde avant la limite (e = 299 999 ms) : Boucle 5")
    void doit_accepter_un_scan_une_milliseconde_avant_la_fin_de_la_derniere_boucle() {
        assertThat(courseX().boucleDePassage(T0.plusMillis(299_999))).isEqualTo(5);
    }

    @Test
    @DisplayName("CA1 - à la limite (e = M x D = 300 000 ms) : TempsDeCourseEcouleException, jamais une Boucle 6")
    void doit_refuser_un_scan_a_la_fin_du_temps_de_la_derniere_boucle() {
        assertThatThrownBy(() -> courseX().boucleDePassage(T0.plusMillis(300_000)))
                .isInstanceOf(TempsDeCourseEcouleException.class);
    }

    @Test
    @DisplayName("CA1 - bien après la limite : TempsDeCourseEcouleException")
    void doit_refuser_un_scan_longtemps_apres_la_fin_du_temps() {
        assertThatThrownBy(() -> courseX().boucleDePassage(T0.plus(Duration.ofHours(5))))
                .isInstanceOf(TempsDeCourseEcouleException.class);
    }

    @Test
    @DisplayName("CA1 - une milliseconde avant le départ : ScanAnterieurAuDepartException (pas la Boucle 1)")
    void doit_refuser_un_scan_anterieur_au_depart() {
        assertThatThrownBy(() -> courseX().boucleDePassage(T0.minusMillis(1)))
                .isInstanceOf(ScanAnterieurAuDepartException.class);
    }

    @Test
    @DisplayName("CA1 - Course de 90 min : à t0 + 90 min, Boucle 2")
    void doit_calculer_la_boucle_avec_la_duree_de_la_course() {
        Course course = course(StatutCourse.EN_COURS, 90, 24, T0);

        assertThat(course.boucleDePassage(T0.plus(Duration.ofMinutes(90)))).isEqualTo(2);
        assertThat(course.boucleDePassage(T0.plus(Duration.ofMinutes(90)).minusMillis(1))).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 - EN_PREPARATION : CourseNonDemarreeException")
    void doit_refuser_un_scan_sur_une_course_en_preparation() {
        Course course = course(StatutCourse.EN_PREPARATION, 1, 5, null);

        assertThatThrownBy(() -> course.boucleDePassage(T0)).isInstanceOf(CourseNonDemarreeException.class);
    }

    @Test
    @DisplayName("CA1 - TERMINEE : exception de Course terminée")
    void doit_refuser_un_scan_sur_une_course_terminee() {
        Course course = course(StatutCourse.TERMINEE, 1, 5, T0);

        assertThatThrownBy(() -> course.boucleDePassage(T0.plusSeconds(10)))
                .isInstanceOf(CourseTermineePourPassageException.class);
    }

    @Test
    @DisplayName("CA1 - EN_COURS sans heure de départ : HeureDeDepartInconnueException")
    void doit_refuser_un_scan_sur_une_course_en_cours_sans_heure_de_depart() {
        Course course = course(StatutCourse.EN_COURS, 1, 5, null);

        assertThatThrownBy(() -> course.boucleDePassage(T0)).isInstanceOf(HeureDeDepartInconnueException.class);
    }

    @Test
    @DisplayName("CA1 - RG19 : deux Courses de durées et de départs différents ont des Boucles indépendantes au même instant")
    void doit_calculer_des_boucles_independantes_pour_deux_courses() {
        Course uneMinute = course(StatutCourse.EN_COURS, 1, 5, T0);
        Course deuxMinutes = course(StatutCourse.EN_COURS, 2, 5, T0.plusSeconds(30));
        Instant instant = T0.plusSeconds(90);

        assertThat(uneMinute.boucleDePassage(instant)).isEqualTo(2);
        assertThat(deuxMinutes.boucleDePassage(instant)).isEqualTo(1);
    }

    private static Course courseX() {
        return course(StatutCourse.EN_COURS, 1, 5, T0);
    }

    private static Course course(StatutCourse statut, int dureeMinutes, int nombreMaxBoucles, Instant demarreeLe) {
        return Course.reconstituer(UUID.randomUUID(), "Backyard des Crêtes", JOUR, statut,
                new ParametresBoucle(400, dureeMinutes, 5), 10, nombreMaxBoucles, Set.of(), demarreeLe);
    }
}
