package fr.backyard.api.dto;

import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.AccountRegistrationView;

import java.time.LocalDate;

/** Une inscription de « Mes inscriptions » (E21, RG11 inc. 5), avec son qr_token. */
public record AccountRegistrationResponse(
    Long raceId,
    String raceName,
    LocalDate raceDate,
    RaceStatus raceStatus,
    Long runnerId,
    int bib,
    RunnerStatus status,
    String qrToken
) {

    public static AccountRegistrationResponse from(AccountRegistrationView view) {
        return new AccountRegistrationResponse(view.raceId(), view.raceName(), view.raceDate(), view.raceStatus(),
            view.runnerId(), view.bib(), view.status(), view.qrToken());
    }
}
