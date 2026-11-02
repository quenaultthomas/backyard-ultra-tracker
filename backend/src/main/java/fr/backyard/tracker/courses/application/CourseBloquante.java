package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import java.util.Comparator;
import java.util.UUID;

/** Course EN_COURS qui empêche la suppression d'un bénévole : identifiant et nom, rien d'autre. */
public record CourseBloquante(UUID id, String nom) {

    /** Ordre de présentation : nom sans tenir compte de la casse, puis identifiant. */
    static final Comparator<CourseBloquante> ORDRE = Comparator
            .comparing(CourseBloquante::nom, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(CourseBloquante::id);

    static CourseBloquante depuis(Course course) {
        return new CourseBloquante(course.id(), course.nom());
    }
}
