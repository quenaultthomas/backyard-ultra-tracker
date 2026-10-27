package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;

/**
 * Tentative de connexion refusée pendant un blocage. Message fixe, sans aucune donnée saisie :
 * identique que le pseudo existe ou non.
 */
public class ConnexionBloqueeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Duration tempsRestant;

    public ConnexionBloqueeException(Duration tempsRestant) {
        super("Trop de tentatives de connexion. Réessayez plus tard.");
        this.tempsRestant = DureeEnSecondes.arrondieALaSecondeSuperieure(tempsRestant);
    }

    /** Temps restant arrondi à la seconde supérieure, au moins une seconde. */
    public Duration tempsRestant() {
        return tempsRestant;
    }
}
