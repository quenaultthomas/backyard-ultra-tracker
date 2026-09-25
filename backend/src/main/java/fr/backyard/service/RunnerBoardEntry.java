package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.RunnerStatus;

import java.util.OptionalInt;

/**
 * Ligne du tableau de bord pour un coureur (RG24 inc. 3). Aucun qr_token.
 *
 * @param dnfReason               null si le coureur n'est pas DNF
 * @param dnfYard                 null si le coureur n'est pas DNF
 * @param averagePaceSecondsPerKm vide sans passage SCAN horodaté
 */
public record RunnerBoardEntry(
    Long runnerId,
    int bib,
    String name,
    RunnerStatus status,
    DnfReason dnfReason,
    Integer dnfYard,
    int completedLoops,
    long distanceMeters,
    long elevationMeters,
    OptionalInt averagePaceSecondsPerKm,
    boolean corrected
) {
}
