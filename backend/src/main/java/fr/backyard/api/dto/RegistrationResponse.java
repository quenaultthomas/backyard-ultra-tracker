package fr.backyard.api.dto;

import fr.backyard.domain.Runner;

/** Réponse d'inscription : seule réponse publique contenant le qr_token (RG16). */
public record RegistrationResponse(
    Long runnerId,
    Long raceId,
    int bib,
    String name,
    String qrToken
) {

    public static RegistrationResponse from(Runner runner) {
        return new RegistrationResponse(runner.getId(), runner.getRace().getId(), runner.getBib(),
            runner.getName(), runner.getQrToken());
    }
}
