package fr.backyard.tracker.courses.domaine;

import java.time.Duration;

/**
 * Temps écoulé entre un scan et son envoi à l'API (file hors ligne du bénévole). Recevable de 0 à
 * {@link #AGE_MAXIMAL_D_UN_SCAN} inclus : au-delà, le scan est refusé (7.3 RG5). Seule définition de cette borne.
 *
 * @param millisecondes âge en millisecondes, positif ou nul
 */
public record AgeDuScan(long millisecondes) {

    public static final Duration AGE_MAXIMAL_D_UN_SCAN = Duration.ofHours(24);

    public AgeDuScan {
        if (millisecondes < 0) {
            throw new IllegalArgumentException("L'âge d'un scan ne peut pas être négatif.");
        }
        if (millisecondes > AGE_MAXIMAL_D_UN_SCAN.toMillis()) {
            throw new ScanTropAncienException();
        }
    }

    /** @throws ScanTropAncienException âge supérieur à 24 h */
    public static AgeDuScan de(long millisecondes) {
        return new AgeDuScan(millisecondes);
    }
}
