package fr.backyard.service;

import java.util.List;

/** « Mes inscriptions » (RG11 inc. 5) : pseudo stocké et inscriptions par date de course puis id de course. */
public record AccountRegistrationsView(String pseudo, List<AccountRegistrationView> registrations) {

    public AccountRegistrationsView {
        registrations = List.copyOf(registrations);
    }
}
