package fr.backyard.tracker.comptes.domaine;

/**
 * Durée d'avance de l'horloge refusée : absente, illisible, en mois ou années, nulle, négative ou supérieure à
 * 30 jours. Le message ne reprend jamais la valeur reçue.
 */
public class DureeAvanceInvalideException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DureeAvanceInvalideException() {
        super("La durée doit être une durée ISO-8601 strictement positive d'au plus 30 jours (ex. PT60M).");
    }
}
