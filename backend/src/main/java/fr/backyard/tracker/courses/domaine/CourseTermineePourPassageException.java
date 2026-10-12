package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Une Course TERMINEE n'accepte plus de Passage. */
public class CourseTermineePourPassageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseTermineePourPassageException(UUID idCourse) {
        super("Course terminée : aucun passage accepté (course " + idCourse + ")");
    }
}
