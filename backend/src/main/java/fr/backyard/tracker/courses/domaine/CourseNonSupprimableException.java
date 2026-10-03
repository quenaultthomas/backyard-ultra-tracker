package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Seule une Course EN_PREPARATION peut être supprimée. */
public class CourseNonSupprimableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseNonSupprimableException(UUID idCourse) {
        super("Course non supprimable : elle n'est plus en préparation (course " + idCourse + ")");
    }
}
