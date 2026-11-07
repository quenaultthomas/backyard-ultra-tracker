package fr.backyard.tracker.comptes.domaine;

/**
 * Aucune photo servie pour ce jeton : jeton mal formé, inconnu, retiré, remplacé, Compte supprimé ou anonymisé, ou
 * fichier manquant. Une seule exception, sans distinction ni écho du jeton.
 */
public class PhotoIntrouvableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PhotoIntrouvableException() {
        super("La photo est introuvable.");
    }
}
