package fr.backyard.api.dto;

import fr.backyard.service.AccountRegistrationsView;

import java.util.List;

/** « Mes inscriptions » (E21, RG11 inc. 5) : pseudo stocké et inscriptions du compte authentifié. */
public record AccountRegistrationsResponse(
    String pseudo,
    List<AccountRegistrationResponse> registrations
) {

    public static AccountRegistrationsResponse from(AccountRegistrationsView view) {
        return new AccountRegistrationsResponse(view.pseudo(),
            view.registrations().stream().map(AccountRegistrationResponse::from).toList());
    }
}
