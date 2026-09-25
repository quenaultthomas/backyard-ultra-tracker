package fr.backyard.domain;

import java.util.OptionalInt;

/**
 * Statistiques dérivées d'un coureur (RG11), jamais stockées.
 */
public record RunnerStats(
    int completedLoops,
    long distanceMeters,
    long elevationMeters,
    OptionalInt averagePaceSecondsPerKm,
    boolean corrected
) {
}
