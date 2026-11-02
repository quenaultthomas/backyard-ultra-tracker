package fr.backyard.tracker.comptes.domaine;

/** Le dernier Compte ADMIN actif ne peut pas être supprimé (l'admin master n'est pas compté). */
public final class DernierAdminActifNonSupprimableException extends SuppressionAdminRefuseeException {

    private static final long serialVersionUID = 1L;

    public DernierAdminActifNonSupprimableException() {
        super("Impossible de supprimer le dernier administrateur actif.");
    }

    @Override
    public String motif() {
        return "DERNIER_ADMIN_ACTIF";
    }
}
