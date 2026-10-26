package fr.backyard.tracker.comptes.domaine;

/**
 * Refus métier d'une désactivation de Compte (8.1 RG3). Le motif est un code stable, sans pseudo ni identifiant, repris
 * tel quel par le journal.
 */
public abstract sealed class DesactivationRefuseeException extends RuntimeException
        permits AdminMasterNonDesactivableException, AutoDesactivationInterditeException, DernierAdminActifException {

    private static final long serialVersionUID = 1L;

    protected DesactivationRefuseeException(String message) {
        super(message);
    }

    /** Code stable du refus. */
    public abstract String motif();
}
