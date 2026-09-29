package fr.backyard.api.dto;

import fr.backyard.domain.Runner;

/**
 * Réponse d'inscription (E3, E20) : avec « Mes inscriptions » (E21), seule réponse au coureur contenant le qr_token
 * (RG16 inc. 3, RG11 inc. 5). {@code name} est le nom affiché (RG18), {@code pseudo} le pseudo stocké du compte.
 */
public record RegistrationResponse(
    Long runnerId,
    Long raceId,
    int bib,
    String name,
    String pseudo,
    String qrToken
) {

    public static RegistrationResponse from(Runner runner) {
        return new RegistrationResponse(runner.getId(), runner.getRace().getId(), runner.getBib(),
            runner.displayName(), runner.accountPseudo(), runner.getQrToken());
    }
}
