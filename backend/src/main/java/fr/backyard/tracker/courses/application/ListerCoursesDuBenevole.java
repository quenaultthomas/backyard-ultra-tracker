package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.application.ListerCourses.CourseListee;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotLogos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Courses auxquelles un bénévole est affecté, tous statuts, dans l'ordre de liste du domaine, avec l'empreinte de
 * leur logo (octets jamais lus).
 */
@Service
public class ListerCoursesDuBenevole {

    private final DepotCourses depotCourses;
    private final DepotLogos depotLogos;

    public ListerCoursesDuBenevole(DepotCourses depotCourses, DepotLogos depotLogos) {
        this.depotCourses = depotCourses;
        this.depotLogos = depotLogos;
    }

    @Transactional(readOnly = true)
    public List<CourseListee> executer(UUID idBenevole) {
        Map<UUID, String> empreintes = depotLogos.empreintesParIdCourse();
        return depotCourses.parBenevole(idBenevole).stream()
                .sorted(Course.ORDRE_DE_LISTE)
                .map(course -> new CourseListee(course, Optional.ofNullable(empreintes.get(course.id()))))
                .toList();
    }
}
