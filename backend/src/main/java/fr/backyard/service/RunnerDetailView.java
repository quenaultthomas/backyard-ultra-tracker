package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.RunnerStatus;

import java.util.List;
import java.util.OptionalInt;

/**
 * Détail public d'un coureur (RG26 inc. 3) : statistiques et passages triés par yard. Aucun qr_token.
 */
public record RunnerDetailView(
    Long runnerId,
    Long raceId,
    int bib,
    String name,
    RunnerStatus status,
    DnfReason dnfReason,
    Integer dnfYard,
    int completedLoops,
    long distanceMeters,
    long elevationMeters,
    OptionalInt averagePaceSecondsPerKm,
    boolean corrected,
    List<PassageView> passages
) {

    public RunnerDetailView {
        passages = List.copyOf(passages);
    }
}
