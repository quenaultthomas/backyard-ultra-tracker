package fr.backyard.tracker.comptes.domaine;

/**
 * Le compte visé par une action sur son propre compte n'existe plus ou ne peut plus se connecter
 * (anonymisé) : aucune modification n'est faite.
 */
public class CompteIntrouvableOuInutilisableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CompteIntrouvableOuInutilisableException() {
        super("Le compte n'existe plus ou ne peut plus se connecter.");
    }
}
