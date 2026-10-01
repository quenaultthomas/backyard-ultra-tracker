package fr.backyard.api.dto;

import fr.backyard.api.validation.AccountPassword;
import fr.backyard.api.validation.PseudoInput;

/**
 * Création d'un compte coureur seul, sans course (E26, RG5 inc. 7). Mêmes validations que l'inscription publique
 * (E3) ; le pseudo est transmis tel que saisi, sa normalisation est faite par le domaine.
 */
public record AccountCreationRequest(
    @PseudoInput String pseudo,
    @AccountPassword String password
) {

    @Override
    public String toString() {
        return "AccountCreationRequest[password=***]";
    }
}
