package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ListerCourses.CourseListee;
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.4 : cas d'usage ListerCoursesDuBenevole (CA4 ; RG9). */
class ListerCoursesDuBenevoleTest {

    private static final UUID L1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID L2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID SANS_AFFECTATION = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID ID_C = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID ID_D = UUID.fromString("00000000-0000-0000-0000-0000000000e4");

    private final DepotCoursesAvecAffectationsDeTest depotCourses = new DepotCoursesAvecAffectationsDeTest();
    private final DepotLogosDeTest depotLogos = new DepotLogosDeTest();
    private final ListerCoursesDuBenevole lister = new ListerCoursesDuBenevole(depotCourses, depotLogos);

    @Test
    @DisplayName("CA4 - pour l1 : D, A, B dans cet ordre (date décroissante, nom insensible à la casse), pas C")
    void doit_lister_les_courses_du_benevole_triees_sans_celles_des_autres() {
        garnirLesDepots();

        List<CourseListee> resultat = lister.executer(L1);

        assertThat(resultat).extracting(c -> c.course().id()).containsExactly(ID_D, ID_A, ID_B);
    }

    @Test
    @DisplayName("CA4 - une Course TERMINEE affectée est incluse")
    void doit_inclure_les_courses_terminees() {
        garnirLesDepots();

        List<CourseListee> resultat = lister.executer(L1);

        assertThat(resultat).filteredOn(c -> c.course().id().equals(ID_B))
                .singleElement().extracting(c -> c.course().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA4 - pour l2 : B seule")
    void doit_ne_lister_que_les_courses_du_second_benevole() {
        garnirLesDepots();

        assertThat(lister.executer(L2)).extracting(c -> c.course().id()).containsExactly(ID_B);
    }

    @Test
    @DisplayName("CA4 - un bénévole sans affectation obtient une liste vide")
    void doit_renvoyer_une_liste_vide_pour_un_benevole_sans_affectation() {
        garnirLesDepots();

        assertThat(lister.executer(SANS_AFFECTATION)).isEmpty();
    }

    @Test
    @DisplayName("CA4 - l'empreinte du logo est donnée sans lire les octets ; vide sans logo")
    void doit_donner_l_empreinte_du_logo_sans_lire_les_octets() {
        garnirLesDepots();
        depotLogos.enregistrer(ID_D, Logo.depuis(png()));
        depotLogos.interdireLaLectureDesOctets();

        List<CourseListee> resultat = lister.executer(L1);

        assertThat(resultat.get(0).course().id()).isEqualTo(ID_D);
        assertThat(resultat.get(0).empreinteLogo()).contains(Logo.depuis(png()).empreinte());
        assertThat(resultat.get(1).empreinteLogo()).isEmpty();
        assertThat(resultat.get(2).empreinteLogo()).isEmpty();
    }

    private void garnirLesDepots() {
        depotCourses.enregistrer(course(ID_A, "Zèbre", LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION,
                Set.of(L1)));
        depotCourses.enregistrer(course(ID_B, "Backyard ancien", LocalDate.of(2025, 5, 1), StatutCourse.TERMINEE,
                Set.of(L1, L2)));
        depotCourses.enregistrer(course(ID_C, "Non affectée", LocalDate.of(2026, 12, 1),
                StatutCourse.EN_PREPARATION, Set.of()));
        depotCourses.enregistrer(course(ID_D, "abeille", LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION,
                Set.of(L1)));
    }

    private static fr.backyard.tracker.courses.domaine.Course course(UUID id, String nom, LocalDate date,
                                                                     StatutCourse statut, Set<UUID> benevoles) {
        return DepotCoursesAvecAffectationsDeTest.course(id, nom, date, statut, benevoles);
    }
}
