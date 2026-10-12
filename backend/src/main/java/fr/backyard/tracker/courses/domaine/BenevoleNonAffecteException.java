package fr.backyard.tracker.courses.domaine;

/** Le bénévole n'est pas affecté à la Course, ou la Course n'existe pas : aucune distinction n'est faite. */
public class BenevoleNonAffecteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BenevoleNonAffecteException() {
        super("Bénévole non affecté à la course");
    }
}
