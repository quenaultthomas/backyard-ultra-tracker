package fr.backyard.tracker.comptes.domaine;

/**
 * Le Compte visé n'existe pas ou n'est pas géré par la route appelée (rôle différent, Compte coureur) : même refus
 * dans tous les cas, sans indice sur le rôle réel.
 */
public class CompteNonGerableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CompteNonGerableException() {
        super("Ce compte est introuvable.");
    }
}
