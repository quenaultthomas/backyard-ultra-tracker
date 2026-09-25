package fr.backyard.api.error;

import org.springframework.http.ProblemDetail;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Fabrique unique des corps d'erreur RFC 9457 (RG5 inc. 3), utilisée par {@link ApiExceptionHandler}
 * et par les gestionnaires 401/403 de la sécurité : un seul format d'erreur dans toute l'API.
 */
public final class ProblemDetailFactory {

    public static final String CODE_PROPERTY = "code";
    public static final String ERRORS_PROPERTY = "errors";

    /**
     * Type RFC 9457 par défaut. Spring 7 laisse {@code type} à null (donc absent du JSON) :
     * il est positionné explicitement pour que le champ soit toujours présent (RG5).
     */
    private static final URI BLANK_TYPE = URI.create("about:blank");

    private ProblemDetailFactory() {
    }

    /**
     * ProblemDetail de type {@code about:blank}, avec titre et statut issus du code, le détail
     * exploitable et le chemin de la requête en {@code instance}.
     */
    public static ProblemDetail create(ApiErrorCode code, String detail, String requestPath) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(BLANK_TYPE);
        problem.setTitle(code.title());
        problem.setInstance(toInstanceUri(requestPath));
        problem.setProperty(CODE_PROPERTY, code.name());
        return problem;
    }

    /**
     * Le chemin reçu est normalement déjà encodé par le client ; s'il contient malgré tout un caractère
     * interdit dans une URI, il est encodé plutôt que de faire échouer la production de l'erreur.
     */
    private static URI toInstanceUri(String requestPath) {
        try {
            return URI.create(requestPath);
        } catch (IllegalArgumentException notAnEncodedPath) {
            return UriComponentsBuilder.fromPath(requestPath).encode().build().toUri();
        }
    }
}
