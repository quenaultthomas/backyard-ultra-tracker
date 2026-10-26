package fr.backyard.tracker.comptes.domaine;

/** Paramètre du plafond d'échecs par adresse hors bornes ; le message nomme le paramètre et sa borne. */
public class PlafondEchecsParAdresseInvalideException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlafondEchecsParAdresseInvalideException(String message) {
        super(message);
    }
}
