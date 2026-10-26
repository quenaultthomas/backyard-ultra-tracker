package fr.backyard.tracker.comptes.domaine;

/** L'admin master est unique et jamais désactivable, y compris par lui-même (SEC-08). */
public final class AdminMasterNonDesactivableException extends DesactivationRefuseeException {

    private static final long serialVersionUID = 1L;

    public AdminMasterNonDesactivableException() {
        super("L'administrateur principal ne peut pas être désactivé.");
    }

    @Override
    public String motif() {
        return "ADMIN_MASTER_NON_DESACTIVABLE";
    }
}
