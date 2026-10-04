package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ListerCoursesOuvertes.CourseOuverte;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 3.2 : indicateur « complète » de ListerCoursesOuvertes (CA3 ; RG3, RG6). */
class ListerCoursesOuvertesCompleteTest {

    private static final UUID COURSE_D = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID COURSE_X = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COURSE_E = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CHLOE = UUID.fromString("00000000-0000-0000-0000-0000000000c9");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final ListerCoursesOuvertes lister = new ListerCoursesOuvertes(depotCourses, depotInscriptions);

    @Test
    @DisplayName("CA3 - pour Chloé : D et X seulement, E EN_COURS exclue")
    void doit_ne_lister_que_les_courses_ouvertes_pour_un_coureur_sans_inscription() {
        garnirLesCoursesEtLesInscriptions();

        List<CourseOuverte> resultat = lister.executer(CHLOE);

        assertThat(resultat).extracting(c -> c.course().id()).containsExactlyInAnyOrder(COURSE_D, COURSE_X);
    }

    @Test
    @DisplayName("CA3 - pour Chloé : D est complète sans monInscription, X ne l'est pas")
    void doit_marquer_la_course_pleine_complete_et_l_autre_non() {
        garnirLesCoursesEtLesInscriptions();

        List<CourseOuverte> resultat = lister.executer(CHLOE);

        CourseOuverte d = ligne(resultat, COURSE_D);
        assertThat(d.complete()).isTrue();
        assertThat(d.monInscription()).isEmpty();
        assertThat(ligne(resultat, COURSE_X).complete()).isFalse();
    }

    @Test
    @DisplayName("CA3 - pour Alice : D est complète et monInscription est renseignée")
    void doit_marquer_complete_une_course_pleine_ou_le_coureur_est_inscrit() {
        garnirLesCoursesEtLesInscriptions();

        CourseOuverte d = ligne(lister.executer(ALICE), COURSE_D);

        assertThat(d.complete()).isTrue();
        assertThat(d.monInscription()).isPresent();
        assertThat(d.monInscription().orElseThrow().compteId()).isEqualTo(ALICE);
    }

    @Test
    @DisplayName("CA3 - D repasse à complète = faux quand le double ne contient plus qu'une Inscription")
    void doit_liberer_la_place_quand_une_inscription_disparait() {
        garnirLesCoursesEtLesInscriptions();
        depotInscriptions.inscriptions.removeIf(i -> i.compteId().equals(BRUNO));

        List<CourseOuverte> resultat = lister.executer(CHLOE);

        assertThat(ligne(resultat, COURSE_D).complete()).isFalse();
    }

    @Test
    @DisplayName("CA3 - le comptage de D n'influence pas X, et X pleine ne change pas D")
    void doit_calculer_complete_course_par_course() {
        garnirLesCoursesEtLesInscriptions();
        depotCourses.enregistrer(course(COURSE_X, StatutCourse.EN_PREPARATION, 1));

        List<CourseOuverte> avantX = lister.executer(CHLOE);
        depotInscriptions.enregistrer(inscription(COURSE_X, ALICE, 1, StatutInscription.EN_COURSE));
        List<CourseOuverte> apresX = lister.executer(CHLOE);

        assertThat(ligne(avantX, COURSE_X).complete()).isFalse();
        assertThat(ligne(apresX, COURSE_X).complete()).isTrue();
        assertThat(ligne(apresX, COURSE_D).complete()).isTrue();
    }

    @Test
    @DisplayName("CA3 - les Inscriptions ABANDON et VAINQUEUR comptent pour l'indicateur")
    void doit_compter_toutes_les_inscriptions_pour_l_indicateur() {
        depotCourses.enregistrer(course(COURSE_D, StatutCourse.EN_PREPARATION, 2));
        depotInscriptions.enregistrer(inscription(COURSE_D, ALICE, 1, StatutInscription.ABANDON));
        depotInscriptions.enregistrer(inscription(COURSE_D, BRUNO, 2, StatutInscription.VAINQUEUR));

        assertThat(ligne(lister.executer(CHLOE), COURSE_D).complete()).isTrue();
    }

    private static CourseOuverte ligne(List<CourseOuverte> resultat, UUID courseId) {
        return resultat.stream().filter(c -> c.course().id().equals(courseId)).findFirst().orElseThrow();
    }

    private void garnirLesCoursesEtLesInscriptions() {
        depotCourses.enregistrer(course(COURSE_D, StatutCourse.EN_PREPARATION, 2));
        depotCourses.enregistrer(course(COURSE_X, StatutCourse.EN_PREPARATION, 50));
        depotCourses.enregistrer(course(COURSE_E, StatutCourse.EN_COURS, 50));
        depotInscriptions.enregistrer(inscription(COURSE_D, ALICE, 1, StatutInscription.EN_COURSE));
        depotInscriptions.enregistrer(inscription(COURSE_D, BRUNO, 2, StatutInscription.EN_COURSE));
    }

    private static Course course(UUID id, StatutCourse statut, int nombreMaxParticipants) {
        return Course.reconstituer(id, "Course " + id, LocalDate.of(2026, 11, 14), statut,
                new ParametresBoucle(6706, 60, 120), nombreMaxParticipants, 24);
    }

    private static Inscription inscription(UUID courseId, UUID compteId, int dossard, StatutInscription statut) {
        return Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                new JetonQr(String.format("%043d", 900 + dossard)), statut);
    }
}
