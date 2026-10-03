package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fiche d'administration d'une Course, quel que soit son statut : la Course et ses bénévoles affectés. */
@Service
public class LireFicheCourse {

    /** Course et identifiants de ses bénévoles, triés par leur forme texte. */
    public record Fiche(Course course, List<UUID> benevoleIds) {

        public static Fiche de(Course course) {
            return new Fiche(course, course.benevolesAffectes().stream()
                    .sorted(Comparator.comparing(UUID::toString)).toList());
        }
    }

    private final DepotCourses depotCourses;

    public LireFicheCourse(DepotCourses depotCourses) {
        this.depotCourses = depotCourses;
    }

    /** @throws CourseIntrouvableException aucune Course pour cet identifiant */
    @Transactional(readOnly = true)
    public Fiche executer(UUID idCourse) {
        return Fiche.de(depotCourses.parId(idCourse).orElseThrow(CourseIntrouvableException::new));
    }
}
