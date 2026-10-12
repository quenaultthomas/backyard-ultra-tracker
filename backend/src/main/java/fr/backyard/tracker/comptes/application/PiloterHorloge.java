package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.DecalageHorlogeExcessifException;
import fr.backyard.tracker.comptes.domaine.DureeAvanceInvalideException;
import fr.backyard.tracker.comptes.domaine.ReglageHorloge;
import java.time.Duration;

/**
 * Pilotage de l'horloge applicative en test (profil e2e, 4.3 RG4 à RG6) : lire, avancer, réinitialiser le décalage.
 * Seul endroit des bornes : une avance est strictement positive et d'au plus 30 jours, le cumul d'au plus 365 jours.
 * Le port n'est jamais modifié quand une avance est refusée. Bean déclaré par la configuration du profil e2e.
 */
public class PiloterHorloge {

    static final Duration AVANCE_MAXIMALE = Duration.ofDays(30);
    static final Duration DECALAGE_MAXIMAL = Duration.ofDays(365);

    private final ReglageHorloge reglageHorloge;

    public PiloterHorloge(ReglageHorloge reglageHorloge) {
        this.reglageHorloge = reglageHorloge;
    }

    public Duration lire() {
        return reglageHorloge.decalage();
    }

    /**
     * @return le décalage cumulé après l'avance
     * @throws DureeAvanceInvalideException si la durée est absente, nulle, négative ou dépasse 30 jours
     * @throws DecalageHorlogeExcessifException si le cumul dépasserait 365 jours
     */
    public synchronized Duration avancer(Duration duree) {
        verifierAvance(duree);
        if (reglageHorloge.decalage().plus(duree).compareTo(DECALAGE_MAXIMAL) > 0) {
            throw new DecalageHorlogeExcessifException();
        }
        return reglageHorloge.avancer(duree);
    }

    /** Idempotent : renvoie toujours {@code PT0S}. */
    public synchronized Duration reinitialiser() {
        reglageHorloge.reinitialiser();
        return reglageHorloge.decalage();
    }

    private static void verifierAvance(Duration duree) {
        if (duree == null || duree.isNegative() || duree.isZero() || duree.compareTo(AVANCE_MAXIMALE) > 0) {
            throw new DureeAvanceInvalideException();
        }
    }
}
