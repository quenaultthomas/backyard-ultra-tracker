package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.4 : cas d'usage LireFicheCourse (CA3 ; RG6). */
class LireFicheCourseTest {

    // L'ordre texte diffère de l'ordre de UUID.compareTo : "f..." (long négatif) précéderait "0..." et "7..."
    private static final UUID COMMENCE_PAR_0 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COMMENCE_PAR_7 = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID COMMENCE_PAR_F = UUID.fromString("f0000000-0000-0000-0000-000000000001");
    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotCoursesAvecAffectationsDeTest depot = new DepotCoursesAvecAffectationsDeTest();
    private final LireFicheCourse lireFicheCourse = new LireFicheCourse(depot);

    @Test
    @DisplayName("CA3 - renvoie la Course et ses identifiants de bénévoles triés par forme texte")
    void doit_renvoyer_la_course_et_les_benevoles_tries_par_forme_texte() {
        depot.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION,
                Set.of(COMMENCE_PAR_F, COMMENCE_PAR_0, COMMENCE_PAR_7)));

        LireFicheCourse.Fiche fiche = lireFicheCourse.executer(ID_A);

        assertThat(fiche.course().id()).isEqualTo(ID_A);
        assertThat(fiche.benevoleIds()).containsExactly(COMMENCE_PAR_0, COMMENCE_PAR_7, COMMENCE_PAR_F);
    }

    @Test
    @DisplayName("CA3 - une Course sans bénévole donne une liste vide")
    void doit_renvoyer_une_liste_vide_pour_une_course_sans_benevole() {
        depot.enregistrer(course(ID_B, StatutCourse.EN_PREPARATION, Set.of()));

        assertThat(lireFicheCourse.executer(ID_B).benevoleIds()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - la lecture est permise pour une Course TERMINEE (tous statuts)")
    void doit_lire_la_fiche_d_une_course_terminee() {
        depot.enregistrer(course(ID_A, StatutCourse.TERMINEE, Set.of(COMMENCE_PAR_0)));

        LireFicheCourse.Fiche fiche = lireFicheCourse.executer(ID_A);

        assertThat(fiche.course().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(fiche.benevoleIds()).containsExactly(COMMENCE_PAR_0);
    }

    @Test
    @DisplayName("CA3 - identifiant inconnu : CourseIntrouvableException")
    void doit_refuser_un_identifiant_de_course_inconnu() {
        depot.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION, Set.of()));

        assertThatThrownBy(() -> lireFicheCourse.executer(ID_INCONNU)).isInstanceOf(CourseIntrouvableException.class);
    }

    private static fr.backyard.tracker.courses.domaine.Course course(UUID id, StatutCourse statut,
                                                                     Set<UUID> benevoles) {
        return DepotCoursesAvecAffectationsDeTest.course(id, "Backyard des Crêtes", LocalDate.of(2026, 11, 14),
                statut, benevoles);
    }
}
