package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Courses ouvertes aux inscriptions, dans l'ordre de liste du domaine, chacune avec l'Inscription du Compte demandeur
 * s'il en a une. Les Inscriptions des autres Comptes ne sont jamais lues.
 */
@Service
public class ListerCoursesOuvertes {

    /** Course ouverte et Inscription du Compte demandeur à cette Course (vide s'il n'y est pas inscrit). */
    public record CourseOuverte(Course course, Optional<Inscription> monInscription) {
    }

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;

    public ListerCoursesOuvertes(DepotCourses depotCourses, DepotInscriptions depotInscriptions) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
    }

    @Transactional(readOnly = true)
    public List<CourseOuverte> executer(UUID compteId) {
        Map<UUID, Inscription> mesInscriptions = depotInscriptions.parCompte(compteId).stream()
                .collect(Collectors.toMap(Inscription::courseId, Function.identity()));
        return depotCourses.toutes().stream()
                .filter(Course::estOuverte)
                .sorted(Course.ORDRE_DE_LISTE)
                .map(course -> new CourseOuverte(course, Optional.ofNullable(mesInscriptions.get(course.id()))))
                .toList();
    }
}
