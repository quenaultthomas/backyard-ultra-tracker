package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonSupprimableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression définitive d'une Course par l'admin master (le rôle est contrôlé avant ce cas d'usage). La Course est
 * relue sous verrou : son statut ne peut pas changer entre le contrôle et la suppression. Le logo et les
 * affectations de bénévoles disparaissent avec elle.
 */
@Service
public class SupprimerCourse {

    private final DepotCourses depotCourses;

    public SupprimerCourse(DepotCourses depotCourses) {
        this.depotCourses = depotCourses;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonSupprimableException la Course n'est plus EN_PREPARATION ; rien n'est supprimé
     */
    @Transactional
    public void executer(UUID idCourse) {
        Course course = depotCourses.parIdPourModification(idCourse).orElseThrow(CourseIntrouvableException::new);
        course.verifierSuppressible();
        depotCourses.supprimer(course.id());
    }
}
