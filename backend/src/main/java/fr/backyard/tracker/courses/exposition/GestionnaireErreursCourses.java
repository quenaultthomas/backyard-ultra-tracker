package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.BenevoleInconnuException;
import fr.backyard.tracker.courses.domaine.CourseCompleteException;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.CourseNonOuverteException;
import fr.backyard.tracker.courses.domaine.CourseNonSupprimableException;
import fr.backyard.tracker.courses.domaine.CourseTermineeException;
import fr.backyard.tracker.courses.domaine.DesinscriptionImpossibleException;
import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import fr.backyard.tracker.courses.domaine.InscriptionDejaExistanteException;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import fr.backyard.tracker.courses.domaine.ViolationValidation;
import java.util.List;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erreurs métier du contexte courses en ProblemDetail (même format que le reste de l'API). Le corps illisible,
 * le 404 générique et la sécurité restent traités globalement. Les messages ne reprennent jamais la valeur saisie.
 */
@RestControllerAdvice(assignableTypes = {CourseController.class, LogoCourseController.class,
        FicheCourseController.class, CoursesDuBenevoleController.class, InscriptionsCoureurController.class,
        MesInscriptionsController.class})
public class GestionnaireErreursCourses {

    private static final URI TYPE_GENERIQUE = URI.create("about:blank");

    @ExceptionHandler(DonneesCourseInvalidesException.class)
    ProblemDetail donneesInvalides(DonneesCourseInvalidesException exception) {
        return validationEchouee(exception.violations());
    }

    /** Aucun identifiant dans la réponse : une seule erreur pour toute la liste. */
    @ExceptionHandler(BenevoleInconnuException.class)
    ProblemDetail benevoleInconnu() {
        return validationEchouee(List.of(new ViolationValidation("benevoleIds", "BENEVOLE_INCONNU",
                "Un des comptes choisis n'est pas un bénévole.")));
    }

    @ExceptionHandler(CourseTermineeException.class)
    ProblemDetail courseTerminee() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course est terminée : ses bénévoles ne peuvent plus être modifiés.", "COURSE_TERMINEE");
    }

    private static ProblemDetail validationEchouee(List<ViolationValidation> violations) {
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, "Requête invalide",
                "Certains champs sont invalides.", "VALIDATION_ECHOUEE");
        probleme.setProperty("erreurs", violations.stream().map(ErreurChamp::depuis).toList());
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

    @ExceptionHandler(CourseNonSupprimableException.class)
    ProblemDetail courseNonSupprimable() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course n'est plus en préparation : elle ne peut plus être supprimée.", "COURSE_NON_SUPPRIMABLE");
    }

    @ExceptionHandler(InscriptionDejaExistanteException.class)
    ProblemDetail inscriptionDejaExistante() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "Vous êtes déjà inscrit à cette course.",
                "INSCRIPTION_DEJA_EXISTANTE");
    }

    @ExceptionHandler(CourseNonOuverteException.class)
    ProblemDetail courseNonOuverte() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "La course n'est plus ouverte aux inscriptions.",
                "COURSE_NON_OUVERTE");
    }

    @ExceptionHandler(CourseCompleteException.class)
    ProblemDetail courseComplete() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "La course est complète.", "COURSE_COMPLETE");
    }

    @ExceptionHandler(InscriptionIntrouvableException.class)
    ProblemDetail inscriptionIntrouvable() {
        return probleme(HttpStatus.NOT_FOUND, "Introuvable", "L'inscription est introuvable.",
                "INSCRIPTION_INTROUVABLE");
    }

    @ExceptionHandler(DesinscriptionImpossibleException.class)
    ProblemDetail desinscriptionImpossible() {
        return probleme(HttpStatus.CONFLICT, "Conflit",
                "La course n'est plus en préparation : la désinscription est impossible.", "DESINSCRIPTION_IMPOSSIBLE");
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
