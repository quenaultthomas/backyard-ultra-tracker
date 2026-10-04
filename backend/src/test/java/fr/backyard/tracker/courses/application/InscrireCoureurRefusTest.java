package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseCompleteException;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonOuverteException;
import fr.backyard.tracker.courses.domaine.GenerateurJetonQr;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionDejaExistanteException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 3.2 : refus d'inscription par InscrireCoureur (CA2 ; RG1 à RG5, RG7). */
class InscrireCoureurRefusTest {

    private static final UUID COURSE_D = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID COURSE_E = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CHLOE = UUID.fromString("00000000-0000-0000-0000-0000000000c9");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final GenerateurJetonQrCompteur generateur = new GenerateurJetonQrCompteur();
    private final InscrireCoureur inscrireCoureur = new InscrireCoureur(depotCourses, depotInscriptions, generateur);

    @Test
    @DisplayName("CA2 - Alice puis Bruno s'inscrivent à D (2 places) : dossards 1 et 2")
    void doit_accepter_les_inscriptions_tant_qu_il_reste_des_places() {
        garnirLesCourses(2);

        Inscription alice = inscrireCoureur.executer(ALICE, COURSE_D);
        Inscription bruno = inscrireCoureur.executer(BRUNO, COURSE_D);

        assertThat(alice.dossard()).isEqualTo(1);
        assertThat(bruno.dossard()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2 - Chloé sur D pleine : CourseCompleteException, dépôt inchangé et aucun jeton consommé")
    void doit_refuser_une_inscription_a_une_course_pleine_sans_rien_creer() {
        garnirLesCourses(2);
        inscrireCoureur.executer(ALICE, COURSE_D);
        inscrireCoureur.executer(BRUNO, COURSE_D);
        int jetonsAvant = generateur.nombreGeneres;

        assertThatThrownBy(() -> inscrireCoureur.executer(CHLOE, COURSE_D))
                .isInstanceOf(CourseCompleteException.class);

        assertThat(depotInscriptions.inscriptions).hasSize(2);
        assertThat(generateur.nombreGeneres).isEqualTo(jetonsAvant);
    }

    @Test
    @DisplayName("CA2 - la Course est chargée par parIdPourModification, y compris pour un refus")
    void doit_controler_la_completude_sous_verrou() {
        garnirLesCourses(1);
        inscrireCoureur.executer(ALICE, COURSE_D);
        depotCourses.chargementsPourModification.clear();

        assertThatThrownBy(() -> inscrireCoureur.executer(CHLOE, COURSE_D))
                .isInstanceOf(CourseCompleteException.class);

        assertThat(depotCourses.chargementsPourModification).containsExactly(COURSE_D);
    }

    @Test
    @DisplayName("CA2 - Alice une seconde fois sur D pleine : InscriptionDejaExistanteException, pas CourseComplete")
    void doit_signaler_le_doublon_avant_la_completude() {
        garnirLesCourses(2);
        inscrireCoureur.executer(ALICE, COURSE_D);
        inscrireCoureur.executer(BRUNO, COURSE_D);

        assertThatThrownBy(() -> inscrireCoureur.executer(ALICE, COURSE_D))
                .isInstanceOf(InscriptionDejaExistanteException.class);

        assertThat(depotInscriptions.inscriptions).hasSize(2);
    }

    @Test
    @DisplayName("CA2 - Alice sur E EN_COURS : CourseNonOuverteException, aucune inscription ni jeton")
    void doit_refuser_une_inscription_a_une_course_demarree() {
        garnirLesCourses(2);

        assertThatThrownBy(() -> inscrireCoureur.executer(ALICE, COURSE_E))
                .isInstanceOf(CourseNonOuverteException.class);

        assertThat(depotInscriptions.inscriptions).isEmpty();
        assertThat(generateur.nombreGeneres).isZero();
    }

    @Test
    @DisplayName("CA2 - Bruno déjà inscrit à E : CourseNonOuverteException, pas InscriptionDejaExistante")
    void doit_signaler_la_course_non_ouverte_avant_le_doublon() {
        garnirLesCourses(2);
        depotInscriptions.enregistrer(inscription(COURSE_E, BRUNO, 1, StatutInscription.EN_COURSE));

        assertThatThrownBy(() -> inscrireCoureur.executer(BRUNO, COURSE_E))
                .isInstanceOf(CourseNonOuverteException.class);

        assertThat(depotInscriptions.inscriptions).hasSize(1);
    }

    @Test
    @DisplayName("CA2 - E EN_COURS et pleine : CourseNonOuverteException, pas CourseComplete")
    void doit_signaler_la_course_non_ouverte_avant_la_completude() {
        garnirLesCourses(2);
        depotInscriptions.enregistrer(inscription(COURSE_E, BRUNO, 1, StatutInscription.EN_COURSE));
        depotInscriptions.enregistrer(inscription(COURSE_E, CHLOE, 2, StatutInscription.EN_COURSE));
        depotCourses.enregistrer(course(COURSE_E, StatutCourse.EN_COURS, 2));

        assertThatThrownBy(() -> inscrireCoureur.executer(ALICE, COURSE_E))
                .isInstanceOf(CourseNonOuverteException.class);
    }

    @Test
    @DisplayName("CA2 - E TERMINEE : CourseNonOuverteException")
    void doit_refuser_une_inscription_a_une_course_terminee() {
        garnirLesCourses(2);
        depotCourses.enregistrer(course(COURSE_E, StatutCourse.TERMINEE, 50));

        assertThatThrownBy(() -> inscrireCoureur.executer(ALICE, COURSE_E))
                .isInstanceOf(CourseNonOuverteException.class);
    }

    @Test
    @DisplayName("CA2 - identifiant inconnu : CourseIntrouvableException")
    void doit_refuser_une_course_inconnue_avant_tout_autre_controle() {
        garnirLesCourses(2);

        assertThatThrownBy(() -> inscrireCoureur.executer(ALICE, ID_INCONNU))
                .isInstanceOf(CourseIntrouvableException.class);

        assertThat(depotInscriptions.inscriptions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - avec une Inscription ABANDON et une VAINQUEUR, la Course à 2 places est complète")
    void doit_compter_les_inscriptions_quel_que_soit_leur_statut() {
        garnirLesCourses(2);
        depotInscriptions.enregistrer(inscription(COURSE_D, ALICE, 1, StatutInscription.ABANDON));
        depotInscriptions.enregistrer(inscription(COURSE_D, BRUNO, 2, StatutInscription.VAINQUEUR));

        assertThatThrownBy(() -> inscrireCoureur.executer(CHLOE, COURSE_D))
                .isInstanceOf(CourseCompleteException.class);

        assertThat(depotInscriptions.inscriptions).hasSize(2);
    }

    @Test
    @DisplayName("CA2 - une Course à 2 places avec 1 inscription d'Alice accepte Bruno : dossard 2")
    void doit_accepter_la_derniere_place_disponible() {
        garnirLesCourses(2);
        inscrireCoureur.executer(ALICE, COURSE_D);

        Inscription bruno = inscrireCoureur.executer(BRUNO, COURSE_D);

        assertThat(bruno.dossard()).isEqualTo(2);
        assertThat(depotInscriptions.inscriptions).hasSize(2);
    }

    @Test
    @DisplayName("CA2 - D pleine ne bloque pas l'inscription à une autre Course ouverte")
    void doit_compter_les_places_course_par_course() {
        garnirLesCourses(1);
        depotCourses.enregistrer(course(UUID.fromString("00000000-0000-0000-0000-0000000000f1"),
                StatutCourse.EN_PREPARATION, 50));
        inscrireCoureur.executer(ALICE, COURSE_D);

        Inscription chloe = inscrireCoureur.executer(CHLOE, UUID.fromString("00000000-0000-0000-0000-0000000000f1"));

        assertThat(chloe.dossard()).isEqualTo(1);
    }

    private void garnirLesCourses(int placesDeD) {
        depotCourses.enregistrer(course(COURSE_D, StatutCourse.EN_PREPARATION, placesDeD));
        depotCourses.enregistrer(course(COURSE_E, StatutCourse.EN_COURS, 50));
    }

    private static Course course(UUID id, StatutCourse statut, int nombreMaxParticipants) {
        return Course.reconstituer(id, "Backyard duo", LocalDate.of(2026, 11, 14), statut,
                new ParametresBoucle(6706, 60, 120), nombreMaxParticipants, 24);
    }

    private static Inscription inscription(UUID courseId, UUID compteId, int dossard, StatutInscription statut) {
        return Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                new JetonQr(String.format("%043d", 900 + dossard)), statut);
    }

    /** Générateur déterministe qui compte les jetons consommés. */
    private static final class GenerateurJetonQrCompteur implements GenerateurJetonQr {

        private final GenerateurJetonQrDeterministeDeTest delegue = new GenerateurJetonQrDeterministeDeTest();
        int nombreGeneres;

        @Override
        public JetonQr generer() {
            nombreGeneres++;
            return delegue.generer();
        }
    }
}
