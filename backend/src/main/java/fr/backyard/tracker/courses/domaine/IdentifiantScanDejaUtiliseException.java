package fr.backyard.tracker.courses.domaine;

/** L'identifiant de scan désigne déjà le Passage d'une autre Inscription. */
public class IdentifiantScanDejaUtiliseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdentifiantScanDejaUtiliseException() {
        super("Identifiant de scan déjà utilisé par un autre passage");
    }
}
