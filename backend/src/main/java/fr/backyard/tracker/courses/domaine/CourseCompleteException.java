package fr.backyard.tracker.courses.domaine;

/** Une Course complète refuse toute nouvelle Inscription. Le message ne reprend aucun identifiant. */
public class CourseCompleteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CourseCompleteException() {
        super("Course complète : plus aucune place disponible");
    }
}
