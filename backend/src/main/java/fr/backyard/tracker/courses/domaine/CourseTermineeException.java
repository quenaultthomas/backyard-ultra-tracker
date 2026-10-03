package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Une Course TERMINEE n'accepte plus de changement de ses bénévoles affectés. */
public class CourseTermineeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseTermineeException(UUID idCourse) {
        super("Course terminée : ses bénévoles ne peuvent plus être modifiés (course " + idCourse + ")");
    }
}
