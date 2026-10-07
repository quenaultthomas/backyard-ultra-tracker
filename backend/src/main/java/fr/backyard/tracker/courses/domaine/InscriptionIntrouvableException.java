package fr.backyard.tracker.courses.domaine;

/**
 * Aucune Inscription du Compte pour l'identifiant demandé (inconnue, d'un autre Compte ou déjà supprimée : même
 * exception, pour ne rien révéler). Le message ne reprend aucun identifiant.
 */
public class InscriptionIntrouvableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InscriptionIntrouvableException() {
        super("Inscription introuvable");
    }
}
