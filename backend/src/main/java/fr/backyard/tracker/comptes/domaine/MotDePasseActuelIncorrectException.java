package fr.backyard.tracker.comptes.domaine;

/**
 * Le mot de passe actuel fourni pour un changement est faux. Message unique quelle que soit la cause
 * (valeur fausse ou hors bornes) et sans aucune donnée saisie.
 */
public class MotDePasseActuelIncorrectException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MotDePasseActuelIncorrectException() {
        super("Le mot de passe actuel est incorrect.");
    }
}
