package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.application.ListerInscritsCourse.Inscrit;
import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instantané de pilotage d'une Course démarrée : chronométrage à l'instant de l'horloge (lu une seule fois) et
 * inscrits encore en course, triés par dossard. Lecture seule : rien n'est écrit ni stocké, aucune transition n'est
 * déclenchée. Aucun journal en fonctionnement normal (lecture fréquente).
 */
@Service
public class ConsulterPilotage {

    /** Instantané : chronométrage vide pour une Course TERMINEE ou sans heure de départ. */
    public record Pilotage(Course course, Instant heureServeur, Optional<Chronometrage> chronometrage,
                           int nombreInscrits, List<Inscrit> inscritsEnCourse) {

        /** Identifiant de la Course uniquement : ni nom ni pseudo dans le journal. */
        @Override
        public String toString() {
            return "Pilotage[course=" + course.id() + "]";
        }
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final AnnuairePseudos annuairePseudos;
    private final Clock horloge;

    public ConsulterPilotage(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                             AnnuairePseudos annuairePseudos, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.annuairePseudos = annuairePseudos;
        this.horloge = horloge;
    }

    /**
     * @throws CourseIntrouvableException aucune Course pour cet identifiant
     * @throws CourseNonDemarreeException la Course est EN_PREPARATION
     */
    @Transactional(readOnly = true)
    public Pilotage executer(UUID courseId) {
        Course course = depotCourses.parId(courseId).orElseThrow(CourseIntrouvableException::new);
        course.autoriserPilotage();
        Instant maintenant = horloge.instant();
        List<Inscription> inscriptions = depotInscriptions.parCourse(courseId);
        List<Inscription> enCourse = inscriptions.stream().filter(Inscription::estEnCourse).toList();
        return new Pilotage(course, maintenant, course.chronometrage(maintenant), inscriptions.size(),
                ListerInscritsCourse.inscritsParDossard(enCourse, annuairePseudos));
    }
}
