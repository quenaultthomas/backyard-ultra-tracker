package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.CoursesNonTermineesDuCompte.CourseNonTerminee;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Incrément 9.6b, CA14 : cas d'usage de lecture « Courses non TERMINEE où ce Compte est inscrit » (RG13, RG20), appelé
 * par l'adaptateur d'assemblage du port {@code ConsultationInscriptions}.
 * Signatures attendues (noms choisis par le testeur) : {@code new CoursesNonTermineesDuCompte(DepotInscriptions,
 * DepotCourses)}, {@code List<CourseNonTerminee> lister(UUID compteId)} et
 * {@code record CoursesNonTermineesDuCompte.CourseNonTerminee(UUID id, String nom, StatutCourse statut)}.
 * La règle « tout statut sauf TERMINEE bloque » est définie ici, une seule fois.
 */
class CoursesNonTermineesDuCompteTest {

    private static final UUID PREPARATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EN_COURS = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TERMINEE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID AUTRE_PREPARATION = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final CoursesNonTermineesDuCompte lecture = new CoursesNonTermineesDuCompte(depotInscriptions, depotCourses);
    private int jeton;
    private int dossard;

    private void course(UUID id, String nom, StatutCourse statut) {
        depotCourses.enregistrer(DepotCoursesDeTest.course(id, nom, LocalDate.of(2026, 11, 14), statut));
    }

    private void inscrire(UUID courseId, UUID compteId, StatutInscription statut) {
        jeton++;
        dossard++;
        depotInscriptions.enregistrer(Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                new JetonQr(String.format("%043d", jeton)), statut));
    }

    private void garnirTroisCourses() {
        course(PREPARATION, "Backyard de démo", StatutCourse.EN_PREPARATION);
        course(EN_COURS, "Backyard du jour", StatutCourse.EN_COURS);
        course(TERMINEE, "Backyard terminé", StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA14 - renvoie les Courses EN_PREPARATION et EN_COURS avec leur statut, jamais la TERMINEE")
    void doit_renvoyer_les_courses_en_preparation_et_en_cours_sans_la_terminee() {
        garnirTroisCourses();
        inscrire(PREPARATION, ALICE, StatutInscription.EN_COURSE);
        inscrire(EN_COURS, ALICE, StatutInscription.EN_COURSE);
        inscrire(TERMINEE, ALICE, StatutInscription.VAINQUEUR);

        List<CourseNonTerminee> courses = lecture.lister(ALICE);

        assertThat(courses).containsExactlyInAnyOrder(
                new CourseNonTerminee(PREPARATION, "Backyard de démo", StatutCourse.EN_PREPARATION),
                new CourseNonTerminee(EN_COURS, "Backyard du jour", StatutCourse.EN_COURS));
    }

    @ParameterizedTest(name = "CA14 - une Inscription {0} à une Course EN_COURS bloque encore")
    @EnumSource(StatutInscription.class)
    @DisplayName("CA14 - le statut de l'Inscription est sans effet sur le résultat")
    void doit_ignorer_le_statut_de_l_inscription(StatutInscription statut) {
        garnirTroisCourses();
        inscrire(EN_COURS, ALICE, statut);

        assertThat(lecture.lister(ALICE)).containsExactly(
                new CourseNonTerminee(EN_COURS, "Backyard du jour", StatutCourse.EN_COURS));
    }

    @Test
    @DisplayName("CA14 - aucune Inscription : liste vide")
    void doit_renvoyer_une_liste_vide_sans_inscription() {
        garnirTroisCourses();

        assertThat(lecture.lister(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("CA14 - un Compte inconnu : liste vide")
    void doit_renvoyer_une_liste_vide_pour_un_compte_inconnu() {
        garnirTroisCourses();
        inscrire(PREPARATION, ALICE, StatutInscription.EN_COURSE);

        assertThat(lecture.lister(INCONNU)).isEmpty();
    }

    @Test
    @DisplayName("CA14 - seulement des Inscriptions à des Courses TERMINEE : liste vide")
    void doit_renvoyer_une_liste_vide_quand_toutes_les_courses_sont_terminees() {
        garnirTroisCourses();
        course(UUID.fromString("00000000-0000-0000-0000-000000000009"), "Autre terminée", StatutCourse.TERMINEE);
        inscrire(TERMINEE, ALICE, StatutInscription.VAINQUEUR);
        inscrire(UUID.fromString("00000000-0000-0000-0000-000000000009"), ALICE, StatutInscription.ABANDON);

        assertThat(lecture.lister(ALICE)).isEmpty();
    }

    @Test
    @DisplayName("CA14 - les Inscriptions d'un autre Compte ne comptent pas")
    void doit_ignorer_les_inscriptions_des_autres_comptes() {
        garnirTroisCourses();
        inscrire(PREPARATION, BRUNO, StatutInscription.EN_COURSE);
        inscrire(EN_COURS, ALICE, StatutInscription.EN_COURSE);

        assertThat(lecture.lister(ALICE)).extracting(CourseNonTerminee::id).containsExactly(EN_COURS);
    }

    @Test
    @DisplayName("CA14 - plusieurs Courses EN_PREPARATION en parallèle sont toutes renvoyées")
    void doit_renvoyer_toutes_les_courses_en_preparation() {
        garnirTroisCourses();
        course(AUTRE_PREPARATION, "Backyard express", StatutCourse.EN_PREPARATION);
        inscrire(PREPARATION, ALICE, StatutInscription.EN_COURSE);
        inscrire(AUTRE_PREPARATION, ALICE, StatutInscription.EN_COURSE);

        assertThat(lecture.lister(ALICE)).extracting(CourseNonTerminee::id)
                .containsExactlyInAnyOrder(PREPARATION, AUTRE_PREPARATION);
    }

    @Test
    @DisplayName("CA14 - la lecture n'écrit rien : Inscriptions et Courses sont inchangées")
    void doit_ne_rien_ecrire() {
        garnirTroisCourses();
        inscrire(PREPARATION, ALICE, StatutInscription.EN_COURSE);
        List<Inscription> avant = List.copyOf(depotInscriptions.inscriptions);
        int enregistrementsAvant = depotCourses.nombreDEnregistrements;

        lecture.lister(ALICE);

        assertThat(depotInscriptions.inscriptions).containsExactlyElementsOf(avant);
        assertThat(depotInscriptions.nombreDeMisesAJour).isZero();
        assertThat(depotCourses.nombreDEnregistrements).isEqualTo(enregistrementsAvant);
    }

    @Test
    @DisplayName("CA14 - le résultat ne contient que des noms de Courses : aucun identifiant de Compte")
    void doit_ne_contenir_aucun_identifiant_de_compte() {
        garnirTroisCourses();
        inscrire(PREPARATION, ALICE, StatutInscription.EN_COURSE);

        assertThat(lecture.lister(ALICE)).allSatisfy(course -> {
            assertThat(course.toString()).contains("Backyard de démo").doesNotContain(ALICE.toString());
        });
    }
}
