package fr.backyard.tracker.courses.domaine;

/** Seule une Course EN_PREPARATION accepte une désinscription. Le message ne reprend aucun identifiant. */
public class DesinscriptionImpossibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DesinscriptionImpossibleException() {
        super("Désinscription impossible : course non en préparation");
    }
}
