package fr.backyard.api.validation;

import fr.backyard.domain.Pseudo;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Saisie d'un pseudo conforme au format de RG2 (inc. 5), contrôlé par {@link Pseudo#isWellFormed(String)} : la règle
 * n'est définie que dans le domaine. Une valeur absente est refusée.
 */
@Documented
@Constraint(validatedBy = PseudoInputValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface PseudoInput {

    String message() default "Pseudo invalide : " + Pseudo.FORMAT_RULE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
