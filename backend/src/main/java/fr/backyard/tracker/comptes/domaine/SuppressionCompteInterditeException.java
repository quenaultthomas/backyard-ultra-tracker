package fr.backyard.tracker.comptes.domaine;

/** Seul un Compte coureur peut être supprimé (anonymisé) ; l'admin master, les admins et les bénévoles jamais. */
public class SuppressionCompteInterditeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SuppressionCompteInterditeException() {
        super("Seul un compte coureur peut être supprimé.");
    }
}
