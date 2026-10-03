package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.ViolationValidation;

/** Entrée de la propriété « erreurs » d'un ProblemDetail de validation d'une Course. */
public record ErreurChamp(String champ, String code, String message) {

    static ErreurChamp depuis(ViolationValidation violation) {
        return new ErreurChamp(violation.champ(), violation.code(), violation.message());
    }
}
