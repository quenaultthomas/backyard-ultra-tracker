package fr.backyard.tracker.comptes.domaine;

/**
 * Échec de connexion. Message unique quelle que soit la cause (pseudo inconnu, mot de passe faux,
 * compte sans empreinte, saisie hors bornes) et sans aucune donnée saisie : pas d'énumération des pseudos.
 */
public class IdentifiantsInvalidesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdentifiantsInvalidesException() {
        super("Pseudo ou mot de passe incorrect.");
    }
}
