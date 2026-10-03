package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Double en mémoire du port DepotCourses pour les tests des cas d'usage de logo (2.3). */
final class DepotCoursesDeTest implements DepotCourses {

    private final Map<UUID, Course> courses = new LinkedHashMap<>();
    int nombreDEnregistrements;

    static Course course(UUID id, String nom, LocalDate date, StatutCourse statut) {
        return Course.reconstituer(id, nom, date, statut, new ParametresBoucle(6706, 60, 120), 50, 24);
    }

    @Override
    public void enregistrer(Course course) {
        courses.put(course.id(), course);
        nombreDEnregistrements++;
    }

    @Override
    public List<Course> toutes() {
        return new ArrayList<>(courses.values());
    }

    @Override
    public Optional<Course> parId(UUID id) {
        return Optional.ofNullable(courses.get(id));
    }
}
