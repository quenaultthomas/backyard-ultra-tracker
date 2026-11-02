package fr.backyard.tracker.comptes.domaine;

/** L'admin master est unique et jamais supprimable, y compris par lui-même (SEC-08). */
public final class AdminMasterNonSupprimableException extends SuppressionAdminRefuseeException {

    private static final long serialVersionUID = 1L;

    public AdminMasterNonSupprimableException() {
        super("L'administrateur principal ne peut pas être supprimé.");
    }

    @Override
    public String motif() {
        return "ADMIN_MASTER_NON_SUPPRIMABLE";
    }
}
