package fr.backyard.tracker.comptes.domaine;

/** Paramètre du plafond de créations de Comptes par adresse hors bornes ; le message nomme le paramètre et sa borne. */
public class PlafondCreationsCompteInvalideException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlafondCreationsCompteInvalideException(String message) {
        super(message);
    }
}
