package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de l'incrément 4.2 : chronométrage d'une Course (CA1 à CA4 ; RG1 à RG7, RG19).
 * Signatures attendues : {@code Optional<Chronometrage> Course.chronometrage(Instant)},
 * {@code void Course.autoriserPilotage()} (CourseNonDemarreeException), et le record
 * {@code Chronometrage(int boucleCourante, boolean derniereBoucle, boolean boucleMaxTerminee,
 * Instant debutBoucleCourante, Instant finBoucleCourante, Duration tempsAvantFinBoucle, Duration dureeEcoulee)}.
 */
class CourseChronometrageTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 9);

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - au départ exact : Boucle 1, début t0, fin t0 + 1 min, reste 60 000 ms, écoulé 0")
    void doit_etre_en_boucle_1_au_depart_exact() {
        Chronometrage c = chronometrageX(Instant.parse("2026-10-09T14:03:27.000Z"));

        assertThat(c.boucleCourante()).isEqualTo(1);
        assertThat(c.debutBoucleCourante()).isEqualTo(Instant.parse("2026-10-09T14:03:27Z"));
        assertThat(c.finBoucleCourante()).isEqualTo(Instant.parse("2026-10-09T14:04:27Z"));
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(60_000));
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("CA1 - à 30,5 s du départ : Boucle 1, reste 29 500 ms, écoulé 30 500 ms")
    void doit_calculer_le_reste_et_l_ecoule_en_milieu_de_boucle() {
        Chronometrage c = chronometrageX(Instant.parse("2026-10-09T14:03:57.500Z"));

        assertThat(c.boucleCourante()).isEqualTo(1);
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(29_500));
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ofMillis(30_500));
    }

    @Test
    @DisplayName("CA1 - à 103 s du départ : Boucle 2 (14:04:27 à 14:05:27), reste 17 000 ms, écoulé 103 000 ms")
    void doit_etre_en_boucle_2_apres_une_minute_et_quarante_trois_secondes() {
        Chronometrage c = chronometrageX(Instant.parse("2026-10-09T14:05:10.000Z"));

        assertThat(c.boucleCourante()).isEqualTo(2);
        assertThat(c.debutBoucleCourante()).isEqualTo(Instant.parse("2026-10-09T14:04:27Z"));
        assertThat(c.finBoucleCourante()).isEqualTo(Instant.parse("2026-10-09T14:05:27Z"));
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(17_000));
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ofMillis(103_000));
    }

    @Test
    @DisplayName("CA1 - Boucles de 60 min, M = 24, à 3 h 25 : Boucle 4, écoulé 12 300 000 ms")
    void doit_etre_en_boucle_4_apres_trois_heures_vingt_cinq_de_boucles_de_soixante_minutes() {
        Course course = courseEnCours(60, 24, T0);

        Chronometrage c = course.chronometrage(T0.plus(Duration.ofHours(3).plusMinutes(25))).orElseThrow();

        assertThat(c.boucleCourante()).isEqualTo(4);
        assertThat(c.debutBoucleCourante()).isEqualTo(T0.plus(Duration.ofHours(3)));
        assertThat(c.finBoucleCourante()).isEqualTo(T0.plus(Duration.ofHours(4)));
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ofMillis(12_300_000));
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMinutes(35));
    }

    @Test
    @DisplayName("CA1 - Boucles de 90 min : Boucle 2 exactement à t0 + 90 min, Boucle 1 une milliseconde avant")
    void doit_basculer_en_boucle_2_a_exactement_la_duree_d_une_boucle_de_quatre_vingt_dix_minutes() {
        Course course = courseEnCours(90, 24, T0);
        Instant frontiere = T0.plus(Duration.ofMinutes(90));

        assertThat(course.chronometrage(frontiere).orElseThrow().boucleCourante()).isEqualTo(2);
        assertThat(course.chronometrage(frontiere.minusMillis(1)).orElseThrow().boucleCourante()).isEqualTo(1);
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - 1 ms avant la fin de la Boucle 1 : Boucle 1, reste 1 ms, écoulé 59 999 ms")
    void doit_rester_en_boucle_1_une_milliseconde_avant_la_frontiere() {
        Chronometrage c = chronometrageX(T0.plusMillis(59_999));

        assertThat(c.boucleCourante()).isEqualTo(1);
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(1));
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ofMillis(59_999));
    }

    @Test
    @DisplayName("CA2 - pile à la frontière : Boucle 2 et reste 60 000 ms, jamais Boucle 1 ni reste 0")
    void doit_etre_en_boucle_2_avec_un_reste_complet_pile_a_la_frontiere() {
        Chronometrage c = chronometrageX(T0.plusMillis(60_000));

        assertThat(c.boucleCourante()).isEqualTo(2);
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(60_000));
        assertThat(c.tempsAvantFinBoucle()).isNotEqualTo(Duration.ZERO);
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ofMillis(60_000));
    }

    @Test
    @DisplayName("CA2 - 1 ms après la frontière : Boucle 2, reste 59 999 ms")
    void doit_etre_en_boucle_2_une_milliseconde_apres_la_frontiere() {
        Chronometrage c = chronometrageX(T0.plusMillis(60_001));

        assertThat(c.boucleCourante()).isEqualTo(2);
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(59_999));
    }

    @Test
    @DisplayName("CA2 - au départ de la dernière Boucle (t0 + 4 x D) : Boucle 5, derniereBoucle vrai, boucleMaxTerminee faux")
    void doit_etre_en_derniere_boucle_au_depart_de_la_boucle_5() {
        Chronometrage c = chronometrageX(T0.plusMillis(4 * 60_000));

        assertThat(c.boucleCourante()).isEqualTo(5);
        assertThat(c.derniereBoucle()).isTrue();
        assertThat(c.boucleMaxTerminee()).isFalse();
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(60_000));
    }

    @Test
    @DisplayName("CA2 - 1 ms avant le départ de la dernière Boucle : Boucle 4, derniereBoucle faux")
    void doit_etre_en_boucle_4_sans_derniere_boucle_une_milliseconde_avant_la_boucle_5() {
        Chronometrage c = chronometrageX(T0.plusMillis(4 * 60_000 - 1));

        assertThat(c.boucleCourante()).isEqualTo(4);
        assertThat(c.derniereBoucle()).isFalse();
        assertThat(c.boucleMaxTerminee()).isFalse();
    }

    // ---------- CA3 ----------

    @Test
    @DisplayName("CA3 - à t0 + M x D : Boucle 5, boucleMaxTerminee et derniereBoucle vrais, reste 0, écoulé 300 000 ms")
    void doit_constater_le_temps_de_la_derniere_boucle_ecoule_a_m_fois_d() {
        Chronometrage c = chronometrageX(T0.plusMillis(300_000));

        assertThat(c.boucleCourante()).isEqualTo(5);
        assertThat(c.boucleMaxTerminee()).isTrue();
        assertThat(c.derniereBoucle()).isTrue();
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ZERO);
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ofMillis(300_000));
    }

    @Test
    @DisplayName("CA3 - 1 ms avant M x D : boucleMaxTerminee faux, reste 1 ms")
    void doit_ne_pas_constater_le_temps_ecoule_une_milliseconde_avant_m_fois_d() {
        Chronometrage c = chronometrageX(T0.plusMillis(300_000 - 1));

        assertThat(c.boucleCourante()).isEqualTo(5);
        assertThat(c.boucleMaxTerminee()).isFalse();
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    @DisplayName("CA3 - une heure après le départ : Boucle 5 plafonnée (jamais 6), reste 0, écoulé 3 600 000 ms")
    void doit_plafonner_la_boucle_courante_au_nombre_max_de_boucles() {
        Chronometrage c = chronometrageX(T0.plusMillis(3_600_000));

        assertThat(c.boucleCourante()).isEqualTo(5);
        assertThat(c.boucleMaxTerminee()).isTrue();
        assertThat(c.debutBoucleCourante()).isEqualTo(T0.plusMillis(4 * 60_000));
        assertThat(c.finBoucleCourante()).isEqualTo(T0.plusMillis(5 * 60_000));
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ZERO);
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ofMillis(3_600_000));
    }

    @Test
    @DisplayName("CA3 - M = 1 : derniereBoucle vrai dès t0, boucleMaxTerminee faux")
    void doit_etre_en_derniere_boucle_des_le_depart_quand_une_seule_boucle_est_autorisee() {
        Chronometrage c = courseEnCours(1, 1, T0).chronometrage(T0).orElseThrow();

        assertThat(c.boucleCourante()).isEqualTo(1);
        assertThat(c.derniereBoucle()).isTrue();
        assertThat(c.boucleMaxTerminee()).isFalse();
    }

    @Test
    @DisplayName("CA3 - M = 1 : boucleMaxTerminee vrai à t0 + D")
    void doit_constater_le_temps_ecoule_a_t0_plus_d_quand_une_seule_boucle_est_autorisee() {
        Chronometrage c = courseEnCours(1, 1, T0).chronometrage(T0.plusSeconds(60)).orElseThrow();

        assertThat(c.boucleCourante()).isEqualTo(1);
        assertThat(c.boucleMaxTerminee()).isTrue();
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("CA3 - instant antérieur au départ (t0 - 5 s) : Boucle 1, écoulé 0, reste 60 000 ms, jamais négatif")
    void doit_se_comporter_comme_au_depart_pour_un_instant_anterieur_au_depart() {
        Chronometrage c = chronometrageX(T0.minusSeconds(5));

        assertThat(c.boucleCourante()).isEqualTo(1);
        assertThat(c.dureeEcoulee()).isEqualTo(Duration.ZERO);
        assertThat(c.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(60_000));
        assertThat(c.derniereBoucle()).isFalse();
        assertThat(c.boucleMaxTerminee()).isFalse();
    }

    // ---------- CA4 ----------

    @Test
    @DisplayName("CA4 - autoriserPilotage lève CourseNonDemarreeException pour une Course EN_PREPARATION")
    void doit_refuser_le_pilotage_d_une_course_en_preparation() {
        Course course = course(StatutCourse.EN_PREPARATION, 1, 5, null);

        assertThatThrownBy(course::autoriserPilotage).isInstanceOf(CourseNonDemarreeException.class);
    }

    @ParameterizedTest(name = "CA4 - {0} : pilotage autorisé")
    @EnumSource(value = StatutCourse.class, names = {"EN_COURS", "TERMINEE"})
    void doit_autoriser_le_pilotage_d_une_course_en_cours_ou_terminee(StatutCourse statut) {
        Course course = course(statut, 1, 5, T0);

        assertThatCode(course::autoriserPilotage).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA4 - le chronométrage d'une Course TERMINEE est vide, même avec une heure de départ")
    void doit_ne_pas_chronometrer_une_course_terminee() {
        Course course = course(StatutCourse.TERMINEE, 1, 5, T0);

        assertThat(course.chronometrage(T0.plusSeconds(30))).isEmpty();
    }

    @Test
    @DisplayName("CA4 - le chronométrage d'une Course EN_COURS sans demarreeLe est vide")
    void doit_ne_pas_chronometrer_une_course_en_cours_sans_heure_de_depart() {
        Course course = course(StatutCourse.EN_COURS, 1, 5, null);

        assertThat(course.chronometrage(T0)).isEmpty();
    }

    @Test
    @DisplayName("CA4 - deux Courses démarrées à des instants et durées différents sont indépendantes pour un même t")
    void doit_chronometrer_deux_courses_independamment_pour_un_meme_instant() {
        Course courseUneMinute = courseEnCours(1, 5, Instant.parse("2026-10-09T14:03:27Z"));
        Course courseSoixanteMinutes = courseEnCours(60, 24, Instant.parse("2026-10-09T14:03:57Z"));
        Instant maintenant = Instant.parse("2026-10-09T14:04:27Z");

        Chronometrage premiere = courseUneMinute.chronometrage(maintenant).orElseThrow();
        Chronometrage seconde = courseSoixanteMinutes.chronometrage(maintenant).orElseThrow();

        assertThat(premiere.boucleCourante()).isEqualTo(2);
        assertThat(seconde.boucleCourante()).isEqualTo(1);
        assertThat(seconde.dureeEcoulee()).isEqualTo(Duration.ofSeconds(30));
        assertThat(seconde.tempsAvantFinBoucle()).isEqualTo(Duration.ofMinutes(60).minusSeconds(30));
        // lire la seconde Course ne modifie pas la première
        assertThat(courseUneMinute.chronometrage(maintenant).orElseThrow()).isEqualTo(premiere);
    }

    // ---------- fabriques ----------

    private static Chronometrage chronometrageX(Instant maintenant) {
        return courseEnCours(1, 5, T0).chronometrage(maintenant).orElseThrow();
    }

    private static Course courseEnCours(int dureeMinutes, int nombreMaxBoucles, Instant demarreeLe) {
        return course(StatutCourse.EN_COURS, dureeMinutes, nombreMaxBoucles, demarreeLe);
    }

    private static Course course(StatutCourse statut, int dureeMinutes, int nombreMaxBoucles, Instant demarreeLe) {
        return Course.reconstituer(ID, "Backyard des Crêtes", JOUR, statut,
                new ParametresBoucle(400, dureeMinutes, 5), 10, nombreMaxBoucles, Set.of(), demarreeLe);
    }
}
