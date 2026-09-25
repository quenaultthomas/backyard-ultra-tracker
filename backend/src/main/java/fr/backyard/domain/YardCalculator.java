package fr.backyard.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Calculs temporels des yards (RG2 à RG4). Fonctions pures : l'instant de référence est toujours
 * passé en paramètre, aucune lecture de l'horloge système.
 */
public class YardCalculator {

    private static final int NO_YARD = 0;
    private static final long MILLIS_PER_SECOND = 1000L;

    public YardCalculator() {
        // calculateur sans état
    }

    /**
     * Numéro du yard dont la fenêtre semi-ouverte contient {@code instant} (RG3), 0 si la course
     * n'a pas de départ ou si l'instant est antérieur au départ.
     */
    public int yardAt(Race race, Instant instant) {
        Instant startedAt = race.getStartedAt();
        if (startedAt == null || instant.isBefore(startedAt)) {
            return NO_YARD;
        }
        long elapsedMillis = Duration.between(startedAt, instant).toMillis();
        long loopMillis = race.getLoopDuration() * MILLIS_PER_SECOND;
        return Math.toIntExact(elapsedMillis / loopMillis + 1);
    }

    /**
     * Yard courant d'une course (RG4) : yard contenant {@code now} si la course est RUNNING, 0 sinon.
     */
    public int currentYard(Race race, Instant now) {
        if (!race.isRunning()) {
            return NO_YARD;
        }
        if (race.getStartedAt() == null) {
            throw new IllegalStateException("Donnée incohérente : course RUNNING sans started_at (course "
                + race.getId() + ")");
        }
        return yardAt(race, now);
    }

    /** Début inclus de la fenêtre du yard (RG2). */
    public Instant yardStart(Race race, int yard) {
        requireValidYard(yard);
        return startOf(race).plusSeconds((long) (yard - 1) * race.getLoopDuration());
    }

    /** Fin exclue de la fenêtre du yard (RG2), égale au début du yard suivant. */
    public Instant yardEnd(Race race, int yard) {
        requireValidYard(yard);
        return startOf(race).plusSeconds((long) yard * race.getLoopDuration());
    }

    private static void requireValidYard(int yard) {
        if (yard < 1) {
            throw new IllegalArgumentException("Numéro de yard invalide : " + yard + " (minimum 1)");
        }
    }

    private static Instant startOf(Race race) {
        Instant startedAt = race.getStartedAt();
        if (startedAt == null) {
            throw new IllegalStateException("La course " + race.getId() + " n'a pas de started_at");
        }
        return startedAt;
    }
}
