package fr.backyard.api.dto;

import fr.backyard.api.validation.AccountPassword;
import fr.backyard.api.validation.PseudoInput;

/**
 * Inscription publique avec création de compte (E3, RG7 inc. 5). Le pseudo est transmis tel que saisi : sa
 * normalisation est faite par le domaine (RG2). L'ancien corps {@code {"name"}} seul est refusé en 400.
 */
public record RegistrationRequest(
    @PseudoInput String pseudo,
    @AccountPassword String password
) {

    @Override
    public String toString() {
        return "RegistrationRequest[password=***]";
    }
}
