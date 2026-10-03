package fr.backyard.tracker.comptes.domaine;

/** Le nouveau mot de passe est exactement le mot de passe actuel. Message sans aucune donnée saisie. */
public class NouveauMotDePasseIdentiqueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NouveauMotDePasseIdentiqueException() {
        super("Le nouveau mot de passe doit être différent de l'actuel.");
    }
}
