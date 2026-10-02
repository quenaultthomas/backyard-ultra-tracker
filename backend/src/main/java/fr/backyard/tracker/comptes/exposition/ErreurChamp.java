package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.ViolationValidation;

/** Entrée de la propriété « erreurs » d'un ProblemDetail de validation. */
public record ErreurChamp(String champ, String code, String message) {

    static ErreurChamp depuis(ViolationValidation violation) {
        return new ErreurChamp(violation.champ(), violation.code(), violation.message());
    }
}
