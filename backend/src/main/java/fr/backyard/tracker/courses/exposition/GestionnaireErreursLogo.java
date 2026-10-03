package fr.backyard.tracker.courses.exposition;

import static fr.backyard.tracker.courses.exposition.GestionnaireErreursCourses.probleme;

import fr.backyard.tracker.courses.domaine.LogoIntrouvableException;
import fr.backyard.tracker.courses.domaine.LogoInvalideException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Erreurs propres au logo en ProblemDetail. Prioritaire sur le gestionnaire global, qui traiterait sinon le dépassement
 * de taille multipart avec un corps générique. Aucun message ne reprend le nom, le type déclaré ni le contenu du
 * fichier ; les refus ne sont pas journalisés.
 */
@RestControllerAdvice(assignableTypes = {LogoCourseController.class, LogoPublicController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GestionnaireErreursLogo {

    @ExceptionHandler(LogoInvalideException.class)
    ProblemDetail logoInvalide(LogoInvalideException exception) {
        return switch (exception.motif()) {
            case REQUIS -> probleme(HttpStatus.BAD_REQUEST, "Requête invalide",
                    "Le fichier du logo est obligatoire.", "LOGO_REQUIS");
            case TROP_VOLUMINEUX -> logoTropVolumineux();
            case FORMAT_INVALIDE -> probleme(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Format non supporté",
                    "Le logo doit être une image PNG, JPEG ou WebP.", "LOGO_FORMAT_INVALIDE");
        };
    }

    /** Limite multipart du serveur, réglée sur la taille maximale du domaine : même réponse que le domaine. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail logoTropVolumineux() {
        return probleme(HttpStatus.CONTENT_TOO_LARGE, "Fichier trop volumineux", "Le logo ne doit pas dépasser 2 Mo.",
                "LOGO_TROP_VOLUMINEUX");
    }

    @ExceptionHandler(MultipartException.class)
    ProblemDetail multipartIllisible() {
        return probleme(HttpStatus.BAD_REQUEST, "Requête invalide", "Le corps de la requête est illisible.",
                "CORPS_ILLISIBLE");
    }

    @ExceptionHandler(LogoIntrouvableException.class)
    ProblemDetail logoIntrouvable() {
        return probleme(HttpStatus.NOT_FOUND, "Introuvable", "Le logo est introuvable.", "LOGO_INTROUVABLE");
    }
}
