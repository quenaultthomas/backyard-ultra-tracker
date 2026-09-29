package fr.backyard.api.validation;

import fr.backyard.domain.PasswordPolicy;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mot de passe d'un compte coureur conforme à RG3 (inc. 5), contrôlé par {@link PasswordPolicy#isAcceptable(String)} :
 * la règle n'est définie que dans le domaine. Une valeur absente est refusée.
 */
@Documented
@Constraint(validatedBy = AccountPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface AccountPassword {

    String message() default "Mot de passe invalide : " + PasswordPolicy.RULE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
