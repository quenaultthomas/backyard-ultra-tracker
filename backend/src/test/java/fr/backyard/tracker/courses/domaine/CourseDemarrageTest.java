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
import org.junit.jupiter.params.provider.EnumSource;

/** Tests de l'incrément 4.1 : démarrage d'une Course (CA1 ; RG1, RG2, RG4, RG5, RG6) et effets du statut (CA2 ; RG7 à RG9, RG13). */
class CourseDemarrageTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final LocalDate JOUR_DE_LA_COURSE = LocalDate.of(2026, 10, 9);
    private static final Instant MAINTENANT = Instant.parse("2026-10-09T14:03:27.654Z");
    private static final Instant MAINTENANT_TRONQUE = Instant.parse("2026-10-09T14:03:27Z");
    private static final Instant ANCIEN_DEPART = Instant.parse("2026-10-09T08:00:00Z");

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - démarrer renvoie une Course EN_COURS dont demarreeLe est l'instant tronqué à la seconde")
    void doit_demarrer_une_course_en_preparation_avec_l_heure_tronquee_a_la_seconde() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2);

        assertThat(demarree.statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(demarree.demarreeLe()).contains(MAINTENANT_TRONQUE);
    }

    @Test
    @DisplayName("CA1 - un instant déjà rond à la seconde reste inchangé")
    void doit_conserver_un_instant_deja_rond_a_la_seconde() {
        Instant rond = Instant.parse("2026-10-09T14:03:27Z");

        Course demarree = courseEnPreparation().demarrer(rond, JOUR_DE_LA_COURSE, 2);

        assertThat(demarree.demarreeLe()).contains(rond);
    }

    @Test
    @DisplayName("CA1 - la troncature n'arrondit pas à la seconde supérieure (999 ms)")
    void doit_tronquer_et_non_arrondir_a_la_seconde() {
        Course demarree = courseEnPreparation().demarrer(Instant.parse("2026-10-09T14:03:27.999999999Z"),
                JOUR_DE_LA_COURSE, 2);

        assertThat(demarree.demarreeLe()).contains(MAINTENANT_TRONQUE);
    }

    @Test
    @DisplayName("CA1 - les autres champs de la Course démarrée sont identiques")
    void doit_conserver_les_autres_champs_de_la_course() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2);

        assertThat(demarree.id()).isEqualTo(ID);
        assertThat(demarree.nom()).isEqualTo("Backyard des Crêtes");
        assertThat(demarree.date()).isEqualTo(JOUR_DE_LA_COURSE);
        assertThat(demarree.parametresBoucle()).isEqualTo(new ParametresBoucle(6706, 60, 120));
        assertThat(demarree.nombreMaxParticipants()).isEqualTo(50);
        assertThat(demarree.nombreMaxBoucles()).isEqualTo(24);
        assertThat(demarree.benevolesAffectes()).containsExactly(LEO);
    }

    @Test
    @DisplayName("CA1 - la Course d'origine reste EN_PREPARATION sans demarreeLe")
    void doit_laisser_la_course_d_origine_inchangee() {
        Course origine = courseEnPreparation();

        origine.demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2);

        assertThat(origine.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(origine.demarreeLe()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - une seule inscription suffit pour démarrer")
    void doit_accepter_le_demarrage_avec_un_seul_inscrit() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 1);

        assertThat(demarree.statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA1 - une Course complète reste démarrable")
    void doit_accepter_le_demarrage_d_une_course_complete() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 50);

        assertThat(demarree.statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @ParameterizedTest(name = "CA1 - {0} : démarrage refusé")
    @EnumSource(value = StatutCourse.class, names = {"EN_COURS", "TERMINEE"})
    void doit_refuser_le_demarrage_d_une_course_deja_demarree_ou_terminee(StatutCourse statut) {
        Course course = course(statut, ANCIEN_DEPART);

        assertThatThrownBy(() -> course.demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2))
                .isInstanceOf(CourseNonDemarrableException.class);

        assertThat(course.statut()).isEqualTo(statut);
        assertThat(course.demarreeLe()).contains(ANCIEN_DEPART);
    }

    @Test
    @DisplayName("CA1 - un second démarrage est refusé et ne change pas demarreeLe (non idempotent)")
    void doit_refuser_un_second_demarrage_et_conserver_la_premiere_heure() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2);

        assertThatThrownBy(() -> demarree.demarrer(MAINTENANT.plusSeconds(60), JOUR_DE_LA_COURSE, 2))
                .isInstanceOf(CourseNonDemarrableException.class);

        assertThat(demarree.demarreeLe()).contains(MAINTENANT_TRONQUE);
    }

    @Test
    @DisplayName("CA1 - la date du jour est la veille de la Course : CourseHorsDateException")
    void doit_refuser_le_demarrage_avant_le_jour_de_la_course() {
        Course course = courseEnPreparation();

        assertThatThrownBy(() -> course.demarrer(MAINTENANT, JOUR_DE_LA_COURSE.minusDays(1), 2))
                .isInstanceOf(CourseHorsDateException.class);

        assertThat(course.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(course.demarreeLe()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - la date du jour est le lendemain de la Course : CourseHorsDateException")
    void doit_refuser_le_demarrage_apres_le_jour_de_la_course() {
        Course course = courseEnPreparation();

        assertThatThrownBy(() -> course.demarrer(MAINTENANT, JOUR_DE_LA_COURSE.plusDays(1), 2))
                .isInstanceOf(CourseHorsDateException.class);

        assertThat(course.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(course.demarreeLe()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - zéro inscrit : CourseSansInscritException, Course non modifiée")
    void doit_refuser_le_demarrage_sans_inscrit() {
        Course course = courseEnPreparation();

        assertThatThrownBy(() -> course.demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 0))
                .isInstanceOf(CourseSansInscritException.class);

        assertThat(course.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(course.demarreeLe()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - ordre : EN_COURS + mauvaise date + 0 inscrit donne CourseNonDemarrableException")
    void doit_controler_le_statut_avant_la_date_et_les_inscrits() {
        Course enCours = course(StatutCourse.EN_COURS, ANCIEN_DEPART);

        assertThatThrownBy(() -> enCours.demarrer(MAINTENANT, JOUR_DE_LA_COURSE.plusDays(3), 0))
                .isInstanceOf(CourseNonDemarrableException.class);
    }

    @Test
    @DisplayName("CA1 - ordre : EN_PREPARATION + mauvaise date + 0 inscrit donne CourseHorsDateException")
    void doit_controler_la_date_avant_les_inscrits() {
        assertThatThrownBy(() -> courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE.plusDays(3), 0))
                .isInstanceOf(CourseHorsDateException.class);
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - une Course démarrée n'est plus modifiable")
    void doit_refuser_la_modification_d_une_course_demarree() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2);

        assertThatThrownBy(() -> demarree.modifier("Autre nom", JOUR_DE_LA_COURSE, 7000, 60, 120, 50, 24,
                JOUR_DE_LA_COURSE)).isInstanceOf(CourseNonModifiableException.class);
        assertThatThrownBy(demarree::autoriserModification).isInstanceOf(CourseNonModifiableException.class);
    }

    @Test
    @DisplayName("CA2 - une Course démarrée n'accepte plus d'inscription ni de désinscription")
    void doit_fermer_inscriptions_et_desinscriptions_d_une_course_demarree() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2);

        assertThat(demarree.estOuverte()).isFalse();
        assertThatThrownBy(demarree::autoriserInscription).isInstanceOf(CourseNonOuverteException.class);
        assertThatThrownBy(demarree::autoriserDesinscription).isInstanceOf(DesinscriptionImpossibleException.class);
    }

    @Test
    @DisplayName("CA2 - une Course démarrée n'est plus suppressible")
    void doit_refuser_la_suppression_d_une_course_demarree() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2);

        assertThatThrownBy(demarree::verifierSuppressible).isInstanceOf(CourseNonSupprimableException.class);
    }

    @Test
    @DisplayName("CA2 - les bénévoles restent affectables sur une Course démarrée")
    void doit_autoriser_l_affectation_de_benevoles_sur_une_course_demarree() {
        Course demarree = courseEnPreparation().demarrer(MAINTENANT, JOUR_DE_LA_COURSE, 2);
        UUID marc = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

        assertThatCode(demarree::autoriserAffectation).doesNotThrowAnyException();
        demarree.affecterBenevoles(Set.of(LEO, marc));

        assertThat(demarree.benevolesAffectes()).containsExactlyInAnyOrder(LEO, marc);
    }

    @Test
    @DisplayName("CA2 - une Course EN_COURS reconstituée sans heure a un demarreeLe vide, sans exception")
    void doit_reconstituer_une_course_en_cours_sans_heure_de_depart() {
        Course course = Course.reconstituer(ID, "Backyard des Crêtes", JOUR_DE_LA_COURSE, StatutCourse.EN_COURS,
                new ParametresBoucle(6706, 60, 120), 50, 24);

        assertThat(course.demarreeLe()).isEmpty();
    }

    @Test
    @DisplayName("CA2 - une Course reconstituée avec une heure de départ la restitue")
    void doit_restituer_l_heure_de_depart_d_une_course_reconstituee() {
        assertThat(course(StatutCourse.EN_COURS, ANCIEN_DEPART).demarreeLe()).contains(ANCIEN_DEPART);
    }

    @Test
    @DisplayName("CA2 - une Course reconstituée avec une heure de départ nulle a un demarreeLe vide")
    void doit_accepter_une_heure_de_depart_nulle_a_la_reconstitution() {
        assertThat(course(StatutCourse.TERMINEE, null).demarreeLe()).isEmpty();
    }

    private static Course courseEnPreparation() {
        return course(StatutCourse.EN_PREPARATION, null);
    }

    private static Course course(StatutCourse statut, Instant demarreeLe) {
        return Course.reconstituer(ID, "Backyard des Crêtes", JOUR_DE_LA_COURSE, statut,
                new ParametresBoucle(6706, 60, 120), 50, 24, Set.of(LEO), demarreeLe);
    }
}
