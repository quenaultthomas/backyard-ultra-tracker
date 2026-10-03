package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ListerCoursesOuvertes.CourseOuverte;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 3.1 : cas d'usage ListerCoursesOuvertes (CA2 ; RG1, RG10). */
class ListerCoursesOuvertesTest {

    private static final UUID COURSE_X = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COURSE_Y = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID COURSE_Z = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID COURSE_W = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID SANS_INSCRIPTION = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final InscrireCoureur inscrireCoureur = new InscrireCoureur(depotCourses, depotInscriptions,
            new GenerateurJetonQrDeterministeDeTest());
    private final ListerCoursesOuvertes lister = new ListerCoursesOuvertes(depotCourses, depotInscriptions);

    @Test
    @DisplayName("CA2 - seules les Courses EN_PREPARATION sont listées, EN_COURS et TERMINEE exclues")
    void doit_ne_lister_que_les_courses_ouvertes() {
        garnirLesCourses();

        List<CourseOuverte> resultat = lister.executer(ALICE);

        assertThat(resultat).extracting(c -> c.course().id()).containsExactlyInAnyOrder(COURSE_X, COURSE_Y);
    }

    @Test
    @DisplayName("CA2 - ordre ORDRE_DE_LISTE : date décroissante puis nom")
    void doit_lister_dans_l_ordre_de_liste_du_domaine() {
        garnirLesCourses();

        List<CourseOuverte> resultat = lister.executer(ALICE);

        // Y a une date plus récente que X : elle vient en premier.
        assertThat(resultat).extracting(c -> c.course().id()).containsExactly(COURSE_Y, COURSE_X);
    }

    @Test
    @DisplayName("CA2 - pour Alice inscrite à X et Y : monInscription renseignée (dossards 1 et 1)")
    void doit_renseigner_l_inscription_d_alice_sur_x_et_y() {
        garnirLesCourses();
        inscrireCoureur.executer(ALICE, COURSE_X);
        inscrireCoureur.executer(BRUNO, COURSE_X);
        inscrireCoureur.executer(ALICE, COURSE_Y);

        List<CourseOuverte> resultat = lister.executer(ALICE);

        assertThat(inscriptionSur(resultat, COURSE_X).dossard()).isEqualTo(1);
        assertThat(inscriptionSur(resultat, COURSE_Y).dossard()).isEqualTo(1);
        assertThat(inscriptionSur(resultat, COURSE_X).compteId()).isEqualTo(ALICE);
    }

    @Test
    @DisplayName("CA2 - pour Bruno : X avec le dossard 2 et Y sans inscription")
    void doit_ne_montrer_que_les_inscriptions_du_coureur_demandeur() {
        garnirLesCourses();
        inscrireCoureur.executer(ALICE, COURSE_X);
        inscrireCoureur.executer(BRUNO, COURSE_X);
        inscrireCoureur.executer(ALICE, COURSE_Y);

        List<CourseOuverte> resultat = lister.executer(BRUNO);

        assertThat(inscriptionSur(resultat, COURSE_X).dossard()).isEqualTo(2);
        assertThat(inscriptionSur(resultat, COURSE_X).compteId()).isEqualTo(BRUNO);
        assertThat(resultat).filteredOn(c -> c.course().id().equals(COURSE_Y)).singleElement()
                .satisfies(c -> assertThat(c.monInscription()).isEmpty());
    }

    @Test
    @DisplayName("CA2 - un coureur sans inscription voit les Courses ouvertes sans monInscription")
    void doit_lister_les_courses_ouvertes_sans_inscription_pour_un_nouveau_coureur() {
        garnirLesCourses();
        inscrireCoureur.executer(ALICE, COURSE_X);

        List<CourseOuverte> resultat = lister.executer(SANS_INSCRIPTION);

        assertThat(resultat).hasSize(2).allSatisfy(c -> assertThat(c.monInscription()).isEmpty());
    }

    @Test
    @DisplayName("CA2 - aucune Course ouverte : liste vide")
    void doit_renvoyer_une_liste_vide_sans_course_ouverte() {
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_Z, "Z", LocalDate.of(2026, 11, 14),
                StatutCourse.EN_COURS));

        assertThat(lister.executer(ALICE)).isEmpty();
    }

    private static Inscription inscriptionSur(List<CourseOuverte> resultat, UUID courseId) {
        return resultat.stream().filter(c -> c.course().id().equals(courseId)).findFirst().orElseThrow()
                .monInscription().orElseThrow();
    }

    private void garnirLesCourses() {
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_X, "Backyard des Crêtes",
                LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_Y, "Backyard express",
                LocalDate.of(2026, 12, 5), StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_Z, "Backyard en cours",
                LocalDate.of(2026, 10, 3), StatutCourse.EN_COURS));
        depotCourses.enregistrer(DepotCoursesDeTest.course(COURSE_W, "Backyard terminé",
                LocalDate.of(2026, 9, 1), StatutCourse.TERMINEE));
    }
}
