package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Une Course TERMINEE n'est jamais rouverte : aucune réintégration n'y est possible. */
public class CourseTermineePourReintegrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseTermineePourReintegrationException(UUID idCourse) {
        super("Course terminée : aucune réintégration possible (course " + idCourse + ")");
    }
}
