package fr.backyard.tracker.comptes.domaine;

/**
 * Règle de saisie non respectée sur un champ. Le message est destiné à l'utilisateur
 * et ne reprend jamais la valeur saisie.
 */
public record ViolationValidation(String champ, String code, String message) {
}
