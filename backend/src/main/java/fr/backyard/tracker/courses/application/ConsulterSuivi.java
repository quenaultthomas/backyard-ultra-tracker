package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suivi public d'une Course : la Course et son chronométrage à l'instant de l'horloge (lu une seule fois), quel que
 * soit son statut. Une seule lecture de Course : ni Inscription, ni Passage, ni Compte. Lecture seule : rien n'est
 * écrit, aucune transition n'est déclenchée. Aucun journal en fonctionnement normal (lecture fréquente et publique).
 */
@Service
public class ConsulterSuivi {

    /** Instantané : chronométrage vide hors EN_COURS ou sans heure de départ. */
    public record Suivi(Course course, Instant heureServeur, Optional<Chronometrage> chronometrage) {

        /** Identifiant de la Course uniquement. */
        @Override
        public String toString() {
            return "Suivi[course=" + course.id() + "]";
        }
    }

    private final DepotCourses depotCourses;
    private final Clock horloge;

    public ConsulterSuivi(DepotCourses depotCourses, Clock horloge) {
        this.depotCourses = depotCourses;
        this.horloge = horloge;
    }

    /** @throws CourseIntrouvableException aucune Course pour cet identifiant */
    @Transactional(readOnly = true)
    public Suivi executer(UUID courseId) {
        Course course = depotCourses.parId(courseId).orElseThrow(CourseIntrouvableException::new);
        Instant maintenant = horloge.instant();
        return new Suivi(course, maintenant, course.chronometrage(maintenant));
    }
}
