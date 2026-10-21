package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 6.1 : cas d'usage ConsulterSuivi (CA1 à CA5 ; RG3, RG4, RG5, RG6).
 * Mis à jour en 6.3 : le constructeur devient
 * {@code ConsulterSuivi(DepotCourses, DepotInscriptions, AnnuairePseudos, DepotPassages, Clock)} (aucune assertion
 * modifiée : seule la construction change, les Passages sont vides ici).
 * Mis à jour en 6.2 : le constructeur devient {@code ConsulterSuivi(DepotCourses, DepotInscriptions, AnnuairePseudos, Clock)}
 * (les Inscriptions sont vides ici ; les nouveautés sont dans ConsulterSuiviCoureursTest).
 * Signatures attendues en 6.1 : {@code ConsulterSuivi(DepotCourses, Clock)} ; {@code Suivi executer(UUID courseId)} où
 * {@code Suivi} (record imbriqué) expose {@code course()} (Course), {@code heureServeur()} (Instant, lu une seule
 * fois sur le Clock) et {@code chronometrage()} ({@code Optional<Chronometrage>}, issu de
 * {@code Course.chronometrage(heureServeur)}). Le logo est complété à part, par l'exposition
 * ({@code ListerCourses.avecEmpreinteLogo}), comme pour le pilotage.
 */
class ConsulterSuiviTest {

    private static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 9);
    private static final long BOUCLE_MS = 3_600_000L;

    private static final UUID X = id(1);
    private static final UUID X_UNE_BOUCLE_MAX = id(2);
    private static final UUID COURSE_90_MIN = id(3);
    private static final UUID EN_PREPARATION = id(4);
    private static final UUID EN_COURS_SANS_DEPART = id(5);
    private static final UUID TERMINEE = id(6);
    private static final UUID INCONNUE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();

    @BeforeEach
    void garnirLeDepot() {
        depotCourses.enregistrer(course(X, "Backyard des Crêtes", StatutCourse.EN_COURS, 60, 5, T0));
        depotCourses.enregistrer(course(X_UNE_BOUCLE_MAX, "Une seule boucle", StatutCourse.EN_COURS, 60, 1, T0));
        depotCourses.enregistrer(course(COURSE_90_MIN, "Boucles de 90 min", StatutCourse.EN_COURS, 90, 5, T0));
        depotCourses.enregistrer(course(EN_PREPARATION, "Pas démarrée", StatutCourse.EN_PREPARATION, 60, 5, null));
        depotCourses.enregistrer(course(EN_COURS_SANS_DEPART, "Sans départ", StatutCourse.EN_COURS, 60, 5, null));
        depotCourses.enregistrer(course(TERMINEE, "Finie", StatutCourse.TERMINEE, 60, 5, T0));
        depotCourses.nombreDEnregistrements = 0;
        depotCourses.nombreDeChargementsParId = 0;
    }

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - au départ (t0) : Boucle 1, reste 3 600 000 ms, écoulé 0")
    void doit_etre_en_boucle_1_avec_toute_la_boucle_restante_au_depart() {
        var suivi = consulterA(T0).executer(X);

        assertChrono(suivi, 1, BOUCLE_MS, 0);
    }

    @Test
    @DisplayName("CA1 - à t0 + 30 min : Boucle 1, reste 1 800 000 ms, écoulé 1 800 000 ms")
    void doit_etre_en_boucle_1_a_mi_boucle() {
        var suivi = consulterA(T0.plus(Duration.ofMinutes(30))).executer(X);

        assertChrono(suivi, 1, 1_800_000, 1_800_000);
    }

    @Test
    @DisplayName("CA1 - à t0 + 3 h 25 min : Boucle 4, reste 2 100 000 ms, écoulé 12 300 000 ms")
    void doit_etre_en_boucle_4_apres_3_heures_25() {
        var suivi = consulterA(T0.plus(Duration.ofMinutes(205))).executer(X);

        assertChrono(suivi, 4, 2_100_000, 12_300_000);
    }

    @Test
    @DisplayName("CA1 - avant le départ (t0 - 5 s) : Boucle 1, reste 3 600 000 ms, écoulé 0")
    void doit_compter_un_instant_anterieur_au_depart_comme_le_depart() {
        var suivi = consulterA(T0.minusSeconds(5)).executer(X);

        assertChrono(suivi, 1, BOUCLE_MS, 0);
    }

    @Test
    @DisplayName("CA1 - heureServeur restitue l'instant du Clock, millisecondes comprises")
    void doit_restituer_l_instant_du_clock_dans_heure_serveur() {
        Instant maintenant = T0.plusMillis(12_345);

        var suivi = consulterA(maintenant).executer(X);

        assertThat(suivi.heureServeur()).isEqualTo(maintenant);
    }

    @Test
    @DisplayName("CA1 - le Clock n'est lu qu'une fois : heureServeur et chronométrage viennent de la même lecture")
    void doit_lire_le_clock_une_seule_fois() {
        ClockAvanceALaLecture clock = new ClockAvanceALaLecture(T0.plusMillis(60_000), Duration.ofSeconds(7));

        var suivi = new ConsulterSuivi(depotCourses, new DepotInscriptionsEspionDeTest().port, new AnnuaireSuiviDeTest(),
                new DepotPassagesEnMemoireDeTest(), clock).executer(X);

        assertThat(clock.lectures.get()).isEqualTo(1);
        assertThat(suivi.heureServeur()).isEqualTo(T0.plusMillis(60_000));
        assertThat(suivi.chronometrage().orElseThrow().dureeEcoulee()).isEqualTo(Duration.ofMillis(60_000));
    }

    @Test
    @DisplayName("CA1 - restitue la Course lue : identifiant, nom et statut")
    void doit_restituer_la_course_lue() {
        var suivi = consulterA(T0).executer(X);

        assertThat(suivi.course().id()).isEqualTo(X);
        assertThat(suivi.course().nom()).isEqualTo("Backyard des Crêtes");
        assertThat(suivi.course().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - 1 ms avant la frontière (t0 + 3 599 999 ms) : Boucle 1, reste 1 ms")
    void doit_rester_en_boucle_1_une_milliseconde_avant_la_frontiere() {
        var suivi = consulterA(T0.plusMillis(BOUCLE_MS - 1)).executer(X);

        assertChrono(suivi, 1, 1, BOUCLE_MS - 1);
    }

    @Test
    @DisplayName("CA2 - à la milliseconde exacte de la frontière (t0 + 3 600 000 ms) : Boucle 2, reste 3 600 000 ms")
    void doit_passer_en_boucle_2_a_la_milliseconde_exacte_de_la_frontiere() {
        var suivi = consulterA(T0.plusMillis(BOUCLE_MS)).executer(X);

        assertChrono(suivi, 2, BOUCLE_MS, BOUCLE_MS);
    }

    @Test
    @DisplayName("CA2 - 1 ms après la frontière (t0 + 3 600 001 ms) : Boucle 2, reste 3 599 999 ms")
    void doit_etre_en_boucle_2_une_milliseconde_apres_la_frontiere() {
        var suivi = consulterA(T0.plusMillis(BOUCLE_MS + 1)).executer(X);

        assertChrono(suivi, 2, BOUCLE_MS - 1, BOUCLE_MS + 1);
    }

    @Test
    @DisplayName("CA2 - une Course de 90 min est encore en Boucle 1 à 90 min - 1 ms")
    void doit_rester_en_boucle_1_une_milliseconde_avant_90_minutes() {
        var suivi = consulterA(T0.plus(Duration.ofMinutes(90)).minusMillis(1)).executer(COURSE_90_MIN);

        assertThat(suivi.chronometrage().orElseThrow().boucleCourante()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - une Course de 90 min passe en Boucle 2 à t0 + 90 min exactement")
    void doit_passer_en_boucle_2_a_90_minutes_exactement_pour_une_course_de_90_minutes() {
        var suivi = consulterA(T0.plus(Duration.ofMinutes(90))).executer(COURSE_90_MIN);

        assertChrono(suivi, 2, 90 * 60_000L, 90 * 60_000L);
    }

    // ---------- CA3 ----------

    @Test
    @DisplayName("CA3 - à t0 + 4 x 60 min : Boucle 5, derniereBoucle vrai, boucleMaxTerminee faux, reste 3 600 000 ms")
    void doit_etre_en_derniere_boucle_au_depart_de_la_boucle_5() {
        var suivi = consulterA(T0.plusMillis(4 * BOUCLE_MS)).executer(X);

        assertChrono(suivi, 5, BOUCLE_MS, 4 * BOUCLE_MS);
        assertThat(suivi.chronometrage().orElseThrow().derniereBoucle()).isTrue();
        assertThat(suivi.chronometrage().orElseThrow().boucleMaxTerminee()).isFalse();
    }

    @Test
    @DisplayName("CA3 - à t0 + 5 x 60 min - 1 ms : Boucle 5, reste 1 ms, boucleMaxTerminee faux")
    void doit_avoir_1_ms_de_reste_une_milliseconde_avant_la_fin_de_la_derniere_boucle() {
        var suivi = consulterA(T0.plusMillis(5 * BOUCLE_MS - 1)).executer(X);

        assertChrono(suivi, 5, 1, 5 * BOUCLE_MS - 1);
        assertThat(suivi.chronometrage().orElseThrow().boucleMaxTerminee()).isFalse();
    }

    @Test
    @DisplayName("CA3 - à t0 + 5 x 60 min : boucleMaxTerminee vrai, Boucle 5, reste 0, écoulé 18 000 000 ms")
    void doit_etre_en_boucle_max_terminee_a_la_fin_de_la_derniere_boucle() {
        var suivi = consulterA(T0.plusMillis(5 * BOUCLE_MS)).executer(X);

        assertChrono(suivi, 5, 0, 18_000_000);
        assertThat(suivi.chronometrage().orElseThrow().boucleMaxTerminee()).isTrue();
        assertThat(suivi.chronometrage().orElseThrow().derniereBoucle()).isTrue();
    }

    @Test
    @DisplayName("CA3 - à t0 + 6 h : jamais Boucle 6, reste 0, écoulé 21 600 000 ms sans plafond")
    void doit_plafonner_la_boucle_au_maximum_et_ne_pas_plafonner_la_duree_ecoulee() {
        var suivi = consulterA(T0.plus(Duration.ofHours(6))).executer(X);

        assertChrono(suivi, 5, 0, 21_600_000);
        assertThat(suivi.chronometrage().orElseThrow().boucleMaxTerminee()).isTrue();
    }

    @Test
    @DisplayName("CA3 - avec nombreMaxBoucles = 1 : derniereBoucle vrai dès t0")
    void doit_etre_en_derniere_boucle_des_le_depart_quand_une_seule_boucle_est_autorisee() {
        var suivi = consulterA(T0).executer(X_UNE_BOUCLE_MAX);

        assertChrono(suivi, 1, BOUCLE_MS, 0);
        assertThat(suivi.chronometrage().orElseThrow().derniereBoucle()).isTrue();
        assertThat(suivi.chronometrage().orElseThrow().boucleMaxTerminee()).isFalse();
    }

    // ---------- CA4 ----------

    @Test
    @DisplayName("CA4 - Course EN_PREPARATION : identité et statut restitués, chronométrage absent, sans exception")
    void doit_renvoyer_un_suivi_sans_chronometrage_pour_une_course_en_preparation() {
        var suivi = consulterA(T0.plusSeconds(90)).executer(EN_PREPARATION);

        assertThat(suivi.course().id()).isEqualTo(EN_PREPARATION);
        assertThat(suivi.course().nom()).isEqualTo("Pas démarrée");
        assertThat(suivi.course().statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(suivi.chronometrage()).isEmpty();
    }

    @Test
    @DisplayName("CA4 - Course EN_COURS sans heure de départ : statut EN_COURS, chronométrage absent, sans exception")
    void doit_renvoyer_un_suivi_sans_chronometrage_pour_une_course_en_cours_sans_heure_de_depart() {
        var suivi = consulterA(T0.plusSeconds(90)).executer(EN_COURS_SANS_DEPART);

        assertThat(suivi.course().id()).isEqualTo(EN_COURS_SANS_DEPART);
        assertThat(suivi.course().nom()).isEqualTo("Sans départ");
        assertThat(suivi.course().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(suivi.chronometrage()).isEmpty();
    }

    @Test
    @DisplayName("CA4 - Course TERMINEE : statut TERMINEE, chronométrage absent, sans exception")
    void doit_renvoyer_un_suivi_sans_chronometrage_pour_une_course_terminee() {
        var suivi = consulterA(T0.plus(Duration.ofHours(2))).executer(TERMINEE);

        assertThat(suivi.course().id()).isEqualTo(TERMINEE);
        assertThat(suivi.course().nom()).isEqualTo("Finie");
        assertThat(suivi.course().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(suivi.chronometrage()).isEmpty();
    }

    @Test
    @DisplayName("CA4 - heureServeur reste renseigné même sans chronométrage")
    void doit_renseigner_l_heure_serveur_meme_sans_chronometrage() {
        Instant maintenant = T0.plusSeconds(90);

        assertThat(consulterA(maintenant).executer(EN_PREPARATION).heureServeur()).isEqualTo(maintenant);
        assertThat(consulterA(maintenant).executer(TERMINEE).heureServeur()).isEqualTo(maintenant);
    }

    // ---------- CA5 ----------

    @Test
    @DisplayName("CA5 - identifiant sans Course : CourseIntrouvableException")
    void doit_refuser_une_course_inconnue() {
        assertThatThrownBy(() -> consulterA(T0).executer(INCONNUE)).isInstanceOf(CourseIntrouvableException.class);
    }

    @Test
    @DisplayName("CA5 - une seule lecture de Course, aucune écriture (lecture seule, aucun changement de statut)")
    void doit_lire_une_seule_course_sans_rien_ecrire() {
        // temps largement écoulé : aucune bascule de statut ne doit être déclenchée par la consultation
        consulterA(T0.plus(Duration.ofHours(6))).executer(X);

        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(1);
        assertThat(depotCourses.nombreDeChargementsPourModification).isZero();
        assertThat(depotCourses.nombreDEnregistrements).isZero();
        assertThat(depotCourses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA5 - isolation : le suivi d'une Course ne dépend que d'elle (90 min et 60 min au même instant)")
    void doit_calculer_chaque_course_independamment_des_autres() {
        Instant maintenant = T0.plus(Duration.ofMinutes(60));

        var suiviDe60Minutes = consulterA(maintenant).executer(X);
        var suiviDe90Minutes = consulterA(maintenant).executer(COURSE_90_MIN);

        assertThat(suiviDe60Minutes.chronometrage().orElseThrow().boucleCourante()).isEqualTo(2);
        assertThat(suiviDe90Minutes.chronometrage().orElseThrow().boucleCourante()).isEqualTo(1);
    }

    // ---------- fabriques et doubles ----------

    private static void assertChrono(ConsulterSuivi.Suivi suivi, int boucle, long resteMs, long ecouleMs) {
        Chronometrage chrono = suivi.chronometrage().orElseThrow();
        assertThat(chrono.boucleCourante()).isEqualTo(boucle);
        assertThat(chrono.tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(resteMs));
        assertThat(chrono.dureeEcoulee()).isEqualTo(Duration.ofMillis(ecouleMs));
    }

    private ConsulterSuivi consulterA(Instant maintenant) {
        return new ConsulterSuivi(depotCourses, new DepotInscriptionsEspionDeTest().port, new AnnuaireSuiviDeTest(),
                new DepotPassagesEnMemoireDeTest(), Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private static Course course(UUID id, String nom, StatutCourse statut, int dureeBoucleMinutes, int maxBoucles,
                                 Instant demarreeLe) {
        return Course.reconstituer(id, nom, JOUR, statut, new ParametresBoucle(400, dureeBoucleMinutes, 5), 10,
                maxBoucles, Set.of(), demarreeLe);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }

    /** Clock dont chaque lecture avance l'instant : toute seconde lecture donnerait une valeur différente. */
    private static final class ClockAvanceALaLecture extends Clock {
        final AtomicInteger lectures = new AtomicInteger();
        private final Instant depart;
        private final Duration pas;

        ClockAvanceALaLecture(Instant depart, Duration pas) {
            this.depart = depart;
            this.pas = pas;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return depart.plus(pas.multipliedBy(lectures.getAndIncrement()));
        }
    }
}
