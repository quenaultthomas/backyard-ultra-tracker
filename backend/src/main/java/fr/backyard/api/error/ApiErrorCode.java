package fr.backyard.api.error;

import org.springframework.http.HttpStatus;

/**
 * Codes d'erreur stables exposés dans la propriété {@code code} des ProblemDetail (RG5, RG6 inc. 3),
 * avec leur statut HTTP.
 */
public enum ApiErrorCode {

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentification requise"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Accès refusé"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Ressource introuvable"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Méthode non supportée"),
    BUSINESS_CONFLICT(HttpStatus.CONFLICT, "Conflit avec l'état courant"),
    DATA_INTEGRITY(HttpStatus.CONFLICT, "Conflit d'intégrité des données"),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "Paramètre invalide"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Requête invalide"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Requête mal formée"),
    INTERNAL_INCONSISTENCY(HttpStatus.INTERNAL_SERVER_ERROR, "Incohérence interne"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne");

    private final HttpStatus status;
    private final String title;

    ApiErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
