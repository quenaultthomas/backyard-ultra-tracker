package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/** Arrondi des temps d'attente annoncés au client (Retry-After) : seconde supérieure, au moins une seconde. */
final class DureeEnSecondes {

    private static final Duration UNE_SECONDE = Duration.ofSeconds(1);

    private DureeEnSecondes() {
    }

    static Duration arrondieALaSecondeSuperieure(Duration duree) {
        Duration tronquee = duree.truncatedTo(ChronoUnit.SECONDS);
        Duration arrondie = tronquee.equals(duree) ? tronquee : tronquee.plus(UNE_SECONDE);
        return arrondie.compareTo(UNE_SECONDE) < 0 ? UNE_SECONDE : arrondie;
    }
}
