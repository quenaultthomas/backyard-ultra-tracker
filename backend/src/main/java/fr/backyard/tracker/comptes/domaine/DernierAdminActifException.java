package fr.backyard.tracker.comptes.domaine;

/** Le dernier Compte ADMIN actif ne peut pas être désactivé (l'admin master n'est pas compté). */
public final class DernierAdminActifException extends DesactivationRefuseeException {

    private static final long serialVersionUID = 1L;

    public DernierAdminActifException() {
        super("Impossible de désactiver le dernier administrateur actif.");
    }

    @Override
    public String motif() {
        return "DERNIER_ADMIN_ACTIF";
    }
}
