package fr.backyard.api.dto;

import fr.backyard.api.validation.AccountPassword;

/** Nouveau mot de passe d'un compte coureur (E22 par l'admin, E23 par le titulaire ; RG3, RG14, RG19 inc. 5). */
public record PasswordChangeRequest(
    @AccountPassword String newPassword
) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[newPassword=***]";
    }
}
