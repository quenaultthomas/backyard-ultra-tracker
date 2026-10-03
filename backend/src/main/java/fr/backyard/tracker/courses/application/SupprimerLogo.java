package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotLogos;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Suppression du logo d'une Course par un admin. Idempotente : une Course modifiable sans logo est acceptée. */
@Service
public class SupprimerLogo {

    private final DepotCourses depotCourses;
    private final DepotLogos depotLogos;

    public SupprimerLogo(DepotCourses depotCourses, DepotLogos depotLogos) {
        this.depotCourses = depotCourses;
        this.depotLogos = depotLogos;
    }

    /**
     * @return vrai si un logo existait et a été supprimé, faux si la Course n'en avait pas
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonModifiableException la Course n'est plus EN_PREPARATION, qu'elle ait un logo ou non
     */
    @Transactional
    public boolean executer(UUID idCourse) {
        Course course = depotCourses.parId(idCourse).orElseThrow(CourseIntrouvableException::new);
        course.autoriserModification();
        if (depotLogos.empreinteParIdCourse(idCourse).isEmpty()) {
            return false;
        }
        depotLogos.supprimer(idCourse);
        return true;
    }
}
