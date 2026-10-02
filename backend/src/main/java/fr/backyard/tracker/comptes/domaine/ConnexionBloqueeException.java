package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Tentative de connexion refusée pendant un blocage. Message fixe, sans aucune donnée saisie :
 * identique que le pseudo existe ou non.
 */
public class ConnexionBloqueeException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final Duration UNE_SECONDE = Duration.ofSeconds(1);

    private final Duration tempsRestant;

    public ConnexionBloqueeException(Duration tempsRestant) {
        super("Trop de tentatives de connexion. Réessayez plus tard.");
        this.tempsRestant = arrondiALaSecondeSuperieure(tempsRestant);
    }

    /** Temps restant arrondi à la seconde supérieure, au moins une seconde. */
    public Duration tempsRestant() {
        return tempsRestant;
    }

    private static Duration arrondiALaSecondeSuperieure(Duration duree) {
        Duration tronquee = duree.truncatedTo(ChronoUnit.SECONDS);
        Duration arrondie = tronquee.equals(duree) ? tronquee : tronquee.plus(UNE_SECONDE);
        return arrondie.compareTo(UNE_SECONDE) < 0 ? UNE_SECONDE : arrondie;
    }
}
