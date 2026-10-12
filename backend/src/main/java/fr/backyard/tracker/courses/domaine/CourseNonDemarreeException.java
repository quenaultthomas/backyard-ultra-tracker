package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Une Course EN_PREPARATION n'a pas encore démarré : elle ne se pilote pas. */
public class CourseNonDemarreeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseNonDemarreeException(UUID idCourse) {
        super("Course non démarrée : elle est encore en préparation (course " + idCourse + ")");
    }
}
