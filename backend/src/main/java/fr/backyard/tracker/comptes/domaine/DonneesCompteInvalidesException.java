package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.stream.Collectors;

/** Une ou plusieurs violations des règles de saisie d'un compte (au plus une par champ). */
public class DonneesCompteInvalidesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<ViolationValidation> violations;

    public DonneesCompteInvalidesException(List<ViolationValidation> violations) {
        super("Données de compte invalides : " + codes(violations));
        this.violations = List.copyOf(violations);
    }

    public List<ViolationValidation> violations() {
        return violations;
    }

    private static String codes(List<ViolationValidation> violations) {
        return violations.stream().map(ViolationValidation::code).collect(Collectors.joining(", "));
    }
}
