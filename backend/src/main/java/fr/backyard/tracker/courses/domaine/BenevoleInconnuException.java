package fr.backyard.tracker.courses.domaine;

/** Un des identifiants choisis ne désigne pas un Compte bénévole. Le message ne reprend aucun identifiant. */
public class BenevoleInconnuException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BenevoleInconnuException() {
        super("Un des comptes choisis n'est pas un bénévole");
    }
}
