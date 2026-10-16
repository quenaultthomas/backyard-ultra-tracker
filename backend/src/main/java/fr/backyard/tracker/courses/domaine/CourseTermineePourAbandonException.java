package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Une Course TERMINEE n'accepte plus d'abandon manuel. */
public class CourseTermineePourAbandonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseTermineePourAbandonException(UUID idCourse) {
        super("Course terminée : aucun abandon manuel possible (course " + idCourse + ")");
    }
}
