package fr.backyard.tracker.comptes.domaine;

/** Paramètre de la politique de blocage des connexions hors borne ; le message nomme le paramètre et la borne. */
public class PolitiqueBlocageInvalideException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PolitiqueBlocageInvalideException(String message) {
        super(message);
    }
}
