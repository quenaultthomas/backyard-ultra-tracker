package fr.backyard.tracker.courses.domaine;

/** Un des bénévoles nouvellement choisis est désactivé. Le message ne reprend aucun identifiant. */
public class BenevoleDesactiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BenevoleDesactiveException() {
        super("Un des bénévoles choisis est désactivé.");
    }
}
