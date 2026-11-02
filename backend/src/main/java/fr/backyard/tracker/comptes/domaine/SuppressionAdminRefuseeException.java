package fr.backyard.tracker.comptes.domaine;

/**
 * Refus métier d'une suppression de Compte ADMIN (9.4 RG2, RG4). Le motif est un code stable, sans pseudo ni
 * identifiant, repris tel quel par le journal.
 */
public abstract sealed class SuppressionAdminRefuseeException extends RuntimeException
        permits AdminMasterNonSupprimableException, DernierAdminActifNonSupprimableException {

    private static final long serialVersionUID = 1L;

    protected SuppressionAdminRefuseeException(String message) {
        super(message);
    }

    /** Code stable du refus. */
    public abstract String motif();
}
