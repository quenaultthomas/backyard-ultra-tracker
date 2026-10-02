package fr.backyard.tracker.comptes.domaine;

/** Le compte d'une session ouverte n'existe plus ou ne peut plus se connecter (anonymisé). */
public class CompteNonConnectableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CompteNonConnectableException() {
        super("Le compte n'existe plus ou ne peut plus se connecter.");
    }
}
