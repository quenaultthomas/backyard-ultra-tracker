package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Seule une Course EN_PREPARATION peut être démarrée : un second démarrage est refusé. */
public class CourseNonDemarrableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseNonDemarrableException(UUID idCourse) {
        super("Course non démarrable : elle n'est plus en préparation (course " + idCourse + ")");
    }
}
