package fr.backyard.api.validation;

import fr.backyard.domain.Pseudo;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Délègue au domaine le contrôle du format d'un pseudo saisi (RG2 inc. 5). */
public class PseudoInputValidator implements ConstraintValidator<PseudoInput, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return Pseudo.isWellFormed(value);
    }
}
