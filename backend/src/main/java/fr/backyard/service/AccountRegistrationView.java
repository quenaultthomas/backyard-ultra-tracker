package fr.backyard.service;

import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.RunnerStatus;

import java.time.LocalDate;

/** Une inscription d'un compte coureur, avec son qr_token (RG11 inc. 5). */
public record AccountRegistrationView(
    Long raceId,
    String raceName,
    LocalDate raceDate,
    RaceStatus raceStatus,
    Long runnerId,
    int bib,
    RunnerStatus status,
    String qrToken
) {
}
