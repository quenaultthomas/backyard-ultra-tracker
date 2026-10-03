package fr.backyard.tracker.courses.domaine;

/** Un Compte a au plus une Inscription par Course. Le message ne reprend aucun identifiant de Compte. */
public class InscriptionDejaExistanteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InscriptionDejaExistanteException() {
        super("Inscription déjà existante pour ce compte et cette course");
    }
}
