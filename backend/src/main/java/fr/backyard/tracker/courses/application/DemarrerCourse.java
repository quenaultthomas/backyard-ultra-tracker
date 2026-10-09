package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseHorsDateException;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarrableException;
import fr.backyard.tracker.courses.domaine.CourseSansInscritException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Démarrage d'une Course par un admin (le rôle est contrôlé avant ce cas d'usage). La Course est chargée sous verrou :
 * statut, date et nombre d'Inscriptions sont relus sans concurrence avec les autres opérations sur la Course.
 */
@Service
public class DemarrerCourse {

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final Clock horloge;

    public DemarrerCourse(DepotCourses depotCourses, DepotInscriptions depotInscriptions, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.horloge = horloge;
    }

    /**
     * L'instant est lu une seule fois : il donne à la fois l'heure de départ et le jour contrôlé.
     *
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonDemarrableException la Course n'est plus EN_PREPARATION
     * @throws CourseHorsDateException la date de la Course n'est pas celle du jour
     * @throws CourseSansInscritException la Course n'a aucune Inscription
     */
    @Transactional
    public Course executer(UUID idCourse) {
        Course course = depotCourses.parIdPourModification(idCourse).orElseThrow(CourseIntrouvableException::new);
        Clock instantane = Clock.fixed(Instant.now(horloge), horloge.getZone());
        Course demarree = course.demarrer(instantane.instant(), CalendrierDesCourses.aujourdhui(instantane),
                depotInscriptions.nombreInscrits(idCourse));
        depotCourses.enregistrer(demarree);
        return demarree;
    }
}
