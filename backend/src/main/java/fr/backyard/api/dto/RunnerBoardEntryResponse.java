package fr.backyard.api.dto;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.RunnerBoardEntry;

/** Ligne du tableau de bord public (RG24). Aucun qr_token. */
public record RunnerBoardEntryResponse(
    Long runnerId,
    int bib,
    String name,
    RunnerStatus status,
    DnfReason dnfReason,
    Integer dnfYard,
    int completedLoops,
    long distanceMeters,
    long elevationMeters,
    Integer averagePaceSecondsPerKm,
    boolean corrected
) {

    public static RunnerBoardEntryResponse from(RunnerBoardEntry entry) {
        return new RunnerBoardEntryResponse(entry.runnerId(), entry.bib(), entry.name(), entry.status(),
            entry.dnfReason(), entry.dnfYard(), entry.completedLoops(), entry.distanceMeters(),
            entry.elevationMeters(), NullableValues.of(entry.averagePaceSecondsPerKm()), entry.corrected());
    }
}
