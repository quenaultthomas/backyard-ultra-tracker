package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Double en mémoire du port DepotCourses pour les tests de SupprimerCourse (2.5). Il trace les chargements sous verrou
 * (parIdPourModification) et distingue les lectures simples, pour vérifier que la suppression passe par le verrou.
 */
final class DepotCoursesAvecSuppressionDeTest implements DepotCourses {

    private final Map<UUID, Course> courses = new LinkedHashMap<>();
    final List<UUID> chargementsPourModification = new ArrayList<>();
    final List<UUID> suppressions = new ArrayList<>();

    @Override
    public void enregistrer(Course course) {
        courses.put(course.id(), course);
    }

    @Override
    public List<Course> toutes() {
        return new ArrayList<>(courses.values());
    }

    @Override
    public Optional<Course> parId(UUID id) {
        return Optional.ofNullable(courses.get(id));
    }

    @Override
    public Optional<Course> parIdPourModification(UUID id) {
        chargementsPourModification.add(id);
        return Optional.ofNullable(courses.get(id));
    }

    @Override
    public void supprimer(UUID id) {
        suppressions.add(id);
        courses.remove(id);
    }
}
