package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.1a : cas d'usage ListerCourses (CA8). Le tri est appliqué par le cas d'usage, pas par le port. */
class ListerCoursesTest {

    private static final UUID ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final DepotCoursesEnMemoire depot = new DepotCoursesEnMemoire();
    private final ListerCourses listerCourses = new ListerCourses(depot);

    // CA8
    @Test
    @DisplayName("CA8 - tri par date décroissante, puis nom croissant insensible à la casse, puis id croissant")
    void doit_trier_par_date_decroissante_puis_nom_puis_id() {
        Course zebre = course(UUID.randomUUID(), "Zèbre", LocalDate.of(2026, 12, 1));
        Course alpha = course(UUID.randomUUID(), "alpha", LocalDate.of(2026, 12, 1));
        Course beta = course(UUID.randomUUID(), "Beta", LocalDate.of(2027, 1, 10));
        Course aube = course(UUID.randomUUID(), "Aube", LocalDate.of(2026, 11, 1));
        Course memeDeuxieme = course(ID_2, "Même", LocalDate.of(2026, 12, 1));
        Course memePremiere = course(ID_1, "Même", LocalDate.of(2026, 12, 1));
        depot.enregistrer(zebre);
        depot.enregistrer(aube);
        depot.enregistrer(memeDeuxieme);
        depot.enregistrer(alpha);
        depot.enregistrer(beta);
        depot.enregistrer(memePremiere);

        List<Course> courses = listerCourses.executer();

        assertThat(courses).containsExactly(beta, alpha, memePremiere, memeDeuxieme, zebre, aube);
    }

    @Test
    @DisplayName("CA8 - dépôt vide : liste vide")
    void doit_renvoyer_une_liste_vide_quand_le_depot_est_vide() {
        assertThat(listerCourses.executer()).isEmpty();
    }

    @Test
    @DisplayName("CA8 - toutes les Courses sont listées, quel que soit leur statut")
    void doit_lister_les_courses_de_tous_les_statuts() {
        Course enPreparation = course(ID_1, "Une", LocalDate.of(2026, 12, 1));
        Course terminee = Course.reconstituer(ID_2, "Deux", LocalDate.of(2026, 11, 1), StatutCourse.TERMINEE,
                new ParametresBoucle(6706, 60, 120), 50, 24);
        depot.enregistrer(enPreparation);
        depot.enregistrer(terminee);

        assertThat(listerCourses.executer()).containsExactly(enPreparation, terminee);
    }

    private static Course course(UUID id, String nom, LocalDate date) {
        return Course.reconstituer(id, nom, date, StatutCourse.EN_PREPARATION,
                new ParametresBoucle(6706, 60, 120), 50, 24);
    }

    /** Dépôt en mémoire qui ne trie pas : l'ordre renvoyé est l'ordre d'insertion. */
    private static final class DepotCoursesEnMemoire implements DepotCourses {
        private final List<Course> courses = new ArrayList<>();

        @Override
        public void enregistrer(Course course) {
            courses.add(course);
        }

        @Override
        public List<Course> toutes() {
            return List.copyOf(courses);
        }
    }
}
