package fr.backyard.api.dto;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.RunnerDetailView;

import java.util.List;

/** Détail public d'un coureur (RG26). Aucun qr_token. */
public record RunnerDetailResponse(
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
    Integer averagePaceSecondsPerKm,
    boolean corrected,
    List<PassageResponse> passages
) {

    public static RunnerDetailResponse from(RunnerDetailView view) {
        return new RunnerDetailResponse(view.runnerId(), view.raceId(), view.bib(), view.name(), view.status(),
            view.dnfReason(), view.dnfYard(), view.completedLoops(), view.distanceMeters(), view.elevationMeters(),
            NullableValues.of(view.averagePaceSecondsPerKm()), view.corrected(),
            view.passages().stream().map(PassageResponse::from).toList());
    }
}
