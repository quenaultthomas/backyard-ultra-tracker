package fr.backyard.tracker.comptes.domaine;

/**
 * Contrat du port {@link EncodeurMotDePasse} : trop de calculs d'empreinte simultanés, la demande est refusée
 * plutôt que mise en attente indéfinie. Aucun effet n'a eu lieu (ni échec de connexion compté, ni compte créé) :
 * la même requête peut être rejouée. Message fixe, sans aucune donnée saisie.
 */
public class HachageSatureException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final String MESSAGE = "Le service est momentanément saturé. Réessayez dans quelques secondes.";

    public HachageSatureException() {
        super(MESSAGE);
    }

    /** Attente d'une place interrompue : la cause est conservée. */
    public HachageSatureException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
