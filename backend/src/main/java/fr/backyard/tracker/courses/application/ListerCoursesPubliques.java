package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.application.ListerCourses.CourseListee;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotLogos;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liste publique des Courses, regroupées par statut enregistré (aucune lecture de l'horloge) et triées par le
 * domaine. Une lecture des Courses et une lecture groupée des empreintes de logo ; ni Inscriptions, ni Passages, ni
 * Comptes.
 */
@Service
public class ListerCoursesPubliques {

    /** Les trois rubriques de l'accueil ; une rubrique sans Course est une liste vide. */
    public record CoursesPubliques(List<CourseListee> enCours, List<CourseListee> aVenir,
                                   List<CourseListee> terminees) {

        public CoursesPubliques {
            enCours = List.copyOf(enCours);
            aVenir = List.copyOf(aVenir);
            terminees = List.copyOf(terminees);
        }

        /** Effectifs des rubriques uniquement. */
        @Override
        public String toString() {
            return "CoursesPubliques[enCours=" + enCours.size() + ", aVenir=" + aVenir.size() + ", terminees="
                    + terminees.size() + "]";
        }
    }

    private final ListerCourses listerCourses;

    public ListerCoursesPubliques(DepotCourses depotCourses, DepotLogos depotLogos) {
        this.listerCourses = new ListerCourses(depotCourses, depotLogos);
    }

    @Transactional(readOnly = true)
    public CoursesPubliques executer() {
        List<CourseListee> courses = listerCourses.executerAvecLogos();
        return new CoursesPubliques(
                rubrique(courses, StatutCourse.EN_COURS, Course.ORDRE_DE_LISTE),
                rubrique(courses, StatutCourse.EN_PREPARATION, Course.ORDRE_A_VENIR),
                rubrique(courses, StatutCourse.TERMINEE, Course.ORDRE_DE_LISTE));
    }

    private static List<CourseListee> rubrique(List<CourseListee> courses, StatutCourse statut,
                                               Comparator<Course> ordre) {
        return courses.stream()
                .filter(courseListee -> courseListee.course().statut() == statut)
                .sorted(Comparator.comparing(CourseListee::course, ordre))
                .toList();
    }
}
