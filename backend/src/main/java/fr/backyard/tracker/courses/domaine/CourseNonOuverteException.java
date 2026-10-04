package fr.backyard.tracker.courses.domaine;

/** Seule une Course ouverte (EN_PREPARATION) accepte une Inscription. Le message ne reprend aucun identifiant. */
public class CourseNonOuverteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseNonOuverteException() {
        super("Course non ouverte aux inscriptions");
    }
}
