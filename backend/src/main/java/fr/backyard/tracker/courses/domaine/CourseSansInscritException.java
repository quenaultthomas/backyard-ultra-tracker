package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Une Course sans aucune Inscription ne peut pas être démarrée. */
public class CourseSansInscritException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseSansInscritException(UUID idCourse) {
        super("Course non démarrable : aucune inscription (course " + idCourse + ")");
    }
}
