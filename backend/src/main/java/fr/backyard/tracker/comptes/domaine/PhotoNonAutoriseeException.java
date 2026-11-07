package fr.backyard.tracker.comptes.domaine;

/** Seul un Compte COUREUR ajoute ou remplace sa photo de profil (10.1 RG1) ; tout autre rôle peut seulement la retirer. */
public class PhotoNonAutoriseeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PhotoNonAutoriseeException() {
        super("Seul un compte coureur peut ajouter ou remplacer sa photo.");
    }
}
