package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Seule une Course EN_COURS peut être terminée : TERMINEE n'est jamais quittée ni rejouée. */
public class CourseNonTerminableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseNonTerminableException(UUID idCourse) {
        super("Course non terminable : elle n'est pas en cours (course " + idCourse + ")");
    }
}
