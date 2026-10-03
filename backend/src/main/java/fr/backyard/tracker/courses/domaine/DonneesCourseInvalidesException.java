package fr.backyard.tracker.courses.domaine;

import java.util.List;
import java.util.stream.Collectors;

/** Une ou plusieurs violations des règles de saisie d'une Course (au plus une par champ, dans l'ordre des champs). */
public class DonneesCourseInvalidesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<ViolationValidation> violations;

    public DonneesCourseInvalidesException(List<ViolationValidation> violations) {
        super("Données de course invalides : " + codes(violations));
        this.violations = List.copyOf(violations);
    }

    public List<ViolationValidation> violations() {
        return violations;
    }

    private static String codes(List<ViolationValidation> violations) {
        return violations.stream().map(ViolationValidation::code).collect(Collectors.joining(", "));
    }
}
