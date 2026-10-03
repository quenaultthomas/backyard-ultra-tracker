package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Seule une Course EN_PREPARATION est modifiable. */
public class CourseNonModifiableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseNonModifiableException(UUID idCourse) {
        super("Course non modifiable : elle n'est plus en préparation (course " + idCourse + ")");
    }
}
