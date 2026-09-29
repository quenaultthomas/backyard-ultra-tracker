package fr.backyard.api.validation;

import fr.backyard.domain.PasswordPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Délègue au domaine le contrôle d'un mot de passe de compte coureur (RG3 inc. 5). */
public class AccountPasswordValidator implements ConstraintValidator<AccountPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return PasswordPolicy.isAcceptable(value);
    }
}
