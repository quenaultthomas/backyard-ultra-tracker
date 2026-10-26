package fr.backyard.tracker.comptes.domaine;

/** Un Compte ne se désactive jamais lui-même. */
public final class AutoDesactivationInterditeException extends DesactivationRefuseeException {

    private static final long serialVersionUID = 1L;

    public AutoDesactivationInterditeException() {
        super("Vous ne pouvez pas désactiver votre propre compte.");
    }

    @Override
    public String motif() {
        return "AUTO_DESACTIVATION_INTERDITE";
    }
}
