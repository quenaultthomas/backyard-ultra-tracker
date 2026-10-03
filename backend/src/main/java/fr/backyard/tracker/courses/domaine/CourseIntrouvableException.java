package fr.backyard.tracker.courses.domaine;

/** Aucune Course n'existe pour l'identifiant demandé. Le message ne reprend pas la valeur reçue. */
public class CourseIntrouvableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseIntrouvableException() {
        super("Course introuvable");
    }
}
