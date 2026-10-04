package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionDejaExistanteException;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 3.1 : cas d'usage InscrireCoureur (CA2 ; RG2, RG3, RG4, RG5, RG7). */
class InscrireCoureurTest {

    private static final UUID COURSE_X = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COURSE_Y = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CAROLE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final InscrireCoureur inscrireCoureur = new InscrireCoureur(depotCourses, depotInscriptions,
            new GenerateurJetonQrDeterministeDeTest());

    @Test
    @DisplayName("CA2 - Alice puis Bruno à X, puis Alice à Y : dossards 1, 2 et 1")
    void doit_numeroter_les_dossards_par_course() {
        garnirLesCourses();

        Inscription alicePourX = inscrireCoureur.executer(ALICE, COURSE_X);
        Inscription brunoPourX = inscrireCoureur.executer(BRUNO, COURSE_X);
        Inscription alicePourY = inscrireCoureur.executer(ALICE, COURSE_Y);

        assertThat(alicePourX.dossard()).isEqualTo(1);
        assertThat(brunoPourX.dossard()).isEqualTo(2);
        assertThat(alicePourY.dossard()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - l'inscription créée est EN_COURSE, liée au Compte et à la Course, et enregistrée")
    void doit_enregistrer_une_inscription_en_course_pour_le_compte_et_la_course() {
        garnirLesCourses();

        Inscription inscription = inscrireCoureur.executer(ALICE, COURSE_X);

        assertThat(inscription.statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(inscription.courseId()).isEqualTo(COURSE_X);
        assertThat(inscription.compteId()).isEqualTo(ALICE);
        assertThat(depotInscriptions.inscriptions).containsExactly(inscription);
    }

    @Test
    @DisplayName("CA2 - deux inscriptions ont deux jetons distincts")
    void doit_generer_un_jeton_distinct_par_inscription() {
        garnirLesCourses();

        Inscription premiere = inscrireCoureur.executer(ALICE, COURSE_X);
        Inscription seconde = inscrireCoureur.executer(BRUNO, COURSE_X);

        assertThat(premiere.jetonQr()).isNotEqualTo(seconde.jetonQr());
    }

    @Test
    @DisplayName("CA2 - la Course est chargée par parIdPourModification (verrou)")
    void doit_charger_la_course_sous_verrou() {
        garnirLesCourses();

        inscrireCoureur.executer(ALICE, COURSE_X);

        assertThat(depotCourses.chargementsPourModification).containsExactly(COURSE_X);
    }

    @Test
    @DisplayName("CA2 - Alice une seconde fois à X : InscriptionDejaExistanteException")
    void doit_refuser_une_seconde_inscription_du_meme_compte_a_la_meme_course() {
        garnirLesCourses();
        inscrireCoureur.executer(ALICE, COURSE_X);

        assertThatThrownBy(() -> inscrireCoureur.executer(ALICE, COURSE_X))
                .isInstanceOf(InscriptionDejaExistanteException.class);

        assertThat(depotInscriptions.inscriptions).hasSize(1);
    }

    @Test
    @DisplayName("CA2 - le doublon refusé ne consomme aucun dossard : le suivant dans X est 3")
    void doit_ne_consommer_aucun_dossard_sur_un_doublon() {
        garnirLesCourses();
        inscrireCoureur.executer(ALICE, COURSE_X);
        inscrireCoureur.executer(BRUNO, COURSE_X);
        assertThatThrownBy(() -> inscrireCoureur.executer(ALICE, COURSE_X))
                .isInstanceOf(InscriptionDejaExistanteException.class);

        Inscription suivante = inscrireCoureur.executer(CAROLE, COURSE_X);

        assertThat(suivante.dossard()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA2 - identifiant de Course inconnu : CourseIntrouvableException, dépôt inchangé")
    void doit_refuser_une_course_inconnue() {
        garnirLesCourses();

        assertThatThrownBy(() -> inscrireCoureur.executer(ALICE, ID_INCONNU))
                .isInstanceOf(CourseIntrouvableException.class);

        assertThat(depotInscriptions.inscriptions).isEmpty();
        assertThat(depotCourses.toutes()).hasSize(2);
    }

    private void garnirLesCourses() {
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_X, "Backyard des Crêtes",
                LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_Y, "Backyard express",
                LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION));
    }
}
