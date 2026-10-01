package fr.backyard.api.dto;

import fr.backyard.domain.Account;

/** Compte créé par E26 : le pseudo stocké seulement, ni mot de passe, ni hash, ni identifiant (RG5 inc. 7). */
public record AccountCreationResponse(String pseudo) {

    public static AccountCreationResponse from(Account account) {
        return new AccountCreationResponse(account.getPseudo());
    }
}
