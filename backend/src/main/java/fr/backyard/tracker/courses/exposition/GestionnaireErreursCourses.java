package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erreurs métier du contexte courses en ProblemDetail (même format que le reste de l'API). Le corps illisible,
 * le 404 générique et la sécurité restent traités globalement. Les messages ne reprennent jamais la valeur saisie.
 */
@RestControllerAdvice(assignableTypes = {CourseController.class, LogoCourseController.class})
public class GestionnaireErreursCourses {

    private static final URI TYPE_GENERIQUE = URI.create("about:blank");

    @ExceptionHandler(DonneesCourseInvalidesException.class)
    ProblemDetail donneesInvalides(DonneesCourseInvalidesException exception) {
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, "Requête invalide",
                "Certains champs sont invalides.", "VALIDATION_ECHOUEE");
        probleme.setProperty("erreurs", exception.violations().stream().map(ErreurChamp::depuis).toList());
        return probleme;
    }

    @ExceptionHandler(CourseIntrouvableException.class)
    ProblemDetail courseIntrouvable() {
        return probleme(HttpStatus.NOT_FOUND, "Introuvable", "La course est introuvable.", "COURSE_INTROUVABLE");
    }

    @ExceptionHandler(CourseNonModifiableException.class)
    ProblemDetail courseNonModifiable() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course n'est plus en préparation : elle ne peut plus être modifiée.", "COURSE_NON_MODIFIABLE");
    }

    /** ProblemDetail au format de l'API : type about:blank, titre, détail et code stable. */
    static ProblemDetail probleme(HttpStatus statut, String titre, String detail, String code) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, detail);
        probleme.setType(TYPE_GENERIQUE);
        probleme.setTitle(titre);
        probleme.setProperty("code", code);
        return probleme;
    }
}
