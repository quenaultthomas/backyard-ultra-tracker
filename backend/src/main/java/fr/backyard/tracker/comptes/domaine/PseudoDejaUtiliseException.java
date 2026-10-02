package fr.backyard.tracker.comptes.domaine;

/** Le pseudo (comparé sans tenir compte de la casse) appartient déjà à un compte. */
public class PseudoDejaUtiliseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final String MESSAGE = "Ce pseudo est déjà utilisé.";

    public PseudoDejaUtiliseException() {
        super(MESSAGE);
    }

    /** Conflit détecté par la persistance (création concurrente du même pseudo). */
    public PseudoDejaUtiliseException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
