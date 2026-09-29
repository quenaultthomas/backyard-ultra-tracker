package fr.backyard.api.dto;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;

/**
 * Coureur vu par l'admin, avec son qr_token pour l'impression des QR codes (RG16 inc. 3). {@code name} est le nom
 * affiché (RG18 inc. 5) ; {@code accountId} et {@code pseudo} valent null sans compte (RG12 inc. 5).
 */
public record AdminRunnerResponse(
    Long id,
    Long raceId,
    int bib,
    String name,
    Long accountId,
    String pseudo,
    String qrToken,
    RunnerStatus status,
    DnfReason dnfReason,
    Integer dnfYard
) {

    public static AdminRunnerResponse from(Runner runner) {
        return new AdminRunnerResponse(runner.getId(), runner.getRace().getId(), runner.getBib(),
            runner.displayName(), runner.accountId(), runner.accountPseudo(), runner.getQrToken(),
            runner.getStatus(), runner.getDnfReason(), runner.getDnfYard());
    }
}
