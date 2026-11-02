package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retrait d'un bénévole supprimé de toutes ses Courses, tout ou rien. Chaque Course candidate est chargée sous verrou
 * dans l'ordre croissant des identifiants, son statut relu sous ce verrou ; une Course supprimée entre-temps est
 * ignorée. Si l'une bloque la suppression, aucune Course n'est modifiée et les bloquantes sont retournées. Rejoint la
 * transaction de l'appelant.
 */
@Service
public class RetirerBenevoleDeToutesSesCourses {

    private final DepotCourses depotCourses;

    public RetirerBenevoleDeToutesSesCourses(DepotCourses depotCourses) {
        this.depotCourses = depotCourses;
    }

    @Transactional
    public RetraitAffectations retirer(UUID benevoleId) {
        List<Course> courses = coursesVerrouillees(benevoleId);
        List<CourseBloquante> bloquantes = courses.stream()
                .filter(Course::bloqueLaSuppressionDUnBenevole)
                .map(CourseBloquante::depuis)
                .sorted(CourseBloquante.ORDRE)
                .toList();
        if (!bloquantes.isEmpty()) {
            return new RetraitAffectations(0, bloquantes);
        }
        return new RetraitAffectations(retirerDe(courses, benevoleId), List.of());
    }

    /** Courses où le bénévole est encore affecté sous verrou, chargées par identifiant croissant. */
    private List<Course> coursesVerrouillees(UUID benevoleId) {
        return depotCourses.parBenevole(benevoleId).stream()
                .map(Course::id)
                .sorted()
                .map(depotCourses::parIdPourModification)
                .flatMap(Optional::stream)
                .filter(course -> course.benevolesAffectes().contains(benevoleId))
                .toList();
    }

    private int retirerDe(List<Course> courses, UUID benevoleId) {
        int retirees = 0;
        for (Course course : courses) {
            if (course.retirerBenevoleSupprime(benevoleId)) {
                depotCourses.enregistrer(course);
                retirees++;
            }
        }
        return retirees;
    }
}
