package fr.backyard.tracker.courses.application;

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
 * Liste de toutes les Courses, tous statuts confondus, dans l'ordre défini par le domaine, avec l'empreinte de leur
 * logo. Les octets des logos ne sont jamais lus.
 */
@Service
public class ListerCourses {

    /** Course et empreinte de son logo (vide sans logo). */
    public record CourseListee(Course course, Optional<String> empreinteLogo) {
    }

    private final DepotCourses depotCourses;
    private final DepotLogos depotLogos;

    public ListerCourses(DepotCourses depotCourses, DepotLogos depotLogos) {
        this.depotCourses = depotCourses;
        this.depotLogos = depotLogos;
    }

    @Transactional(readOnly = true)
    public List<Course> executer() {
        return depotCourses.toutes().stream().sorted(Course.ORDRE_DE_LISTE).toList();
    }

    /** Une seule lecture des empreintes pour toute la liste. */
    @Transactional(readOnly = true)
    public List<CourseListee> executerAvecLogos() {
        Map<UUID, String> empreintes = depotLogos.empreintesParIdCourse();
        return executer().stream()
                .map(course -> new CourseListee(course, Optional.ofNullable(empreintes.get(course.id()))))
                .toList();
    }

    /** Une Course déjà lue (après sa modification, par exemple), complétée de l'empreinte de son logo. */
    @Transactional(readOnly = true)
    public CourseListee avecEmpreinteLogo(Course course) {
        return new CourseListee(course, depotLogos.empreinteParIdCourse(course.id()));
    }
}
