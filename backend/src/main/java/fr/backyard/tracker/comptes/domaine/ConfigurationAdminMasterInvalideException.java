package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Configuration de l'admin master (variables d'environnement) refusée au démarrage. Chaque violation porte
 * le nom de la variable en cause ({@link ViolationValidation#champ()}) et la règle violée, jamais la valeur.
 */
public class ConfigurationAdminMasterInvalideException extends RuntimeException {

    public static final String VARIABLE_PSEUDO = "ADMIN_MASTER_PSEUDO";
    public static final String VARIABLE_MOT_DE_PASSE = "ADMIN_MASTER_MOT_DE_PASSE";

    private static final long serialVersionUID = 1L;

    private final transient List<ViolationValidation> violations;

    public ConfigurationAdminMasterInvalideException(List<ViolationValidation> violations) {
        super("Configuration de l'admin master invalide : " + messages(violations));
        this.violations = List.copyOf(violations);
    }

    /** Une seule des deux variables est renseignée. */
    public static ConfigurationAdminMasterInvalideException variableManquante(String variable) {
        return new ConfigurationAdminMasterInvalideException(List.of(new ViolationValidation(variable,
                "VARIABLE_MANQUANTE", VARIABLE_PSEUDO + " et " + VARIABLE_MOT_DE_PASSE
                + " doivent être renseignées ensemble (manquante : " + variable + ").")));
    }

    /** Le pseudo appartient déjà à un autre compte, qui n'est jamais promu. */
    public static ConfigurationAdminMasterInvalideException pseudoDejaUtilise() {
        return new ConfigurationAdminMasterInvalideException(List.of(new ViolationValidation(VARIABLE_PSEUDO,
                "PSEUDO_DEJA_UTILISE", VARIABLE_PSEUDO + " : ce pseudo est déjà utilisé par un autre compte.")));
    }

    /** Rattache une violation de saisie (pseudo ou mot de passe) à la variable qui l'a fournie. */
    public static ViolationValidation pourVariable(String variable, ViolationValidation violation) {
        return new ViolationValidation(variable, violation.code(),
                variable + " : " + minusculeInitiale(violation.message()));
    }

    public List<ViolationValidation> violations() {
        return violations;
    }

    private static String minusculeInitiale(String message) {
        return message.substring(0, 1).toLowerCase(Locale.ROOT) + message.substring(1);
    }

    private static String messages(List<ViolationValidation> violations) {
        return violations.stream().map(ViolationValidation::message).collect(Collectors.joining(" "));
    }
}
