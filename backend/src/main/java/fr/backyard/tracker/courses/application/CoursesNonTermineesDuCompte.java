package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture : Courses non TERMINEE auxquelles un Compte est inscrit, quel que soit le statut de l'Inscription. Unique
 * définition de « quel statut de Course bloque un changement de rôle » : tout sauf TERMINEE (9.6b RG13, RG20).
 * N'écrit rien ; rejoint la transaction de l'appelant, statuts lus au moment de la consultation.
 */
@Service
public class CoursesNonTermineesDuCompte {

    /** Course non TERMINEE d'un Compte : identifiant, nom et statut, sans le Compte. */
    public record CourseNonTerminee(UUID id, String nom, StatutCourse statut) {
    }

    private final DepotInscriptions depotInscriptions;
    private final DepotCourses depotCourses;

    public CoursesNonTermineesDuCompte(DepotInscriptions depotInscriptions, DepotCourses depotCourses) {
        this.depotInscriptions = depotInscriptions;
        this.depotCourses = depotCourses;
    }

    /** @return les Courses non TERMINEE du Compte, dans un ordre quelconque ; vide pour un Compte inconnu */
    @Transactional
    public List<CourseNonTerminee> lister(UUID compteId) {
        List<UUID> courseIds = depotInscriptions.parCompte(compteId).stream().map(Inscription::courseId).toList();
        return depotCourses.parIds(courseIds).stream()
                .filter(course -> course.statut() != StatutCourse.TERMINEE)
                .map(CoursesNonTermineesDuCompte::enCourseNonTerminee)
                .toList();
    }

    private static CourseNonTerminee enCourseNonTerminee(Course course) {
        return new CourseNonTerminee(course.id(), course.nom(), course.statut());
    }
}
