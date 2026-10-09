package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Une Course ne peut être démarrée que le jour de sa date. */
public class CourseHorsDateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseHorsDateException(UUID idCourse) {
        super("Course non démarrable : ce n'est pas le jour de sa date (course " + idCourse + ")");
    }
}
