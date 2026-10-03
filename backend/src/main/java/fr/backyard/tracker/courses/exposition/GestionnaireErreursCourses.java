package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erreurs métier du contexte courses en ProblemDetail (même format que le reste de l'API). Le corps illisible,
 * le 404 et la sécurité restent traités globalement. Les messages ne reprennent jamais la valeur saisie.
 */
@RestControllerAdvice(assignableTypes = CourseController.class)
public class GestionnaireErreursCourses {

    private static final URI TYPE_GENERIQUE = URI.create("about:blank");

    @ExceptionHandler(DonneesCourseInvalidesException.class)
    ProblemDetail donneesInvalides(DonneesCourseInvalidesException exception) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Certains champs sont invalides.");
        probleme.setType(TYPE_GENERIQUE);
        probleme.setTitle("Requête invalide");
        probleme.setProperty("code", "VALIDATION_ECHOUEE");
        probleme.setProperty("erreurs", exception.violations().stream().map(ErreurChamp::depuis).toList());
        return probleme;
    }
}
