package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.CourseTermineePourReintegrationException;
import fr.backyard.tracker.courses.domaine.InscriptionNonEnAbandonException;
import fr.backyard.tracker.courses.domaine.TempsDeCourseEcouleException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Refus propres à la réintégration, avec leur {@code detail} dédié ; codes partagés avec le reste de l'API. Consulté
 * avant {@link GestionnaireErreursCourses}, qui traite les autres erreurs de ce contrôleur (Course ou Inscription
 * introuvable, Course non démarrée, heure de départ inconnue). Ni dossard ni pseudo dans les réponses.
 */
@RestControllerAdvice(assignableTypes = ReintegrationController.class)
@Order(-1)
public class GestionnaireErreursReintegration {

    @ExceptionHandler(CourseTermineePourReintegrationException.class)
    ProblemDetail courseTerminee() {
        return conflit("La course est terminée : aucune réintégration n'est possible.", "COURSE_TERMINEE");
    }

    @ExceptionHandler(TempsDeCourseEcouleException.class)
    ProblemDetail tempsDeCourseEcoule() {
        return conflit("Le temps de la dernière boucle est écoulé : la réintégration n'est plus possible.",
                "TEMPS_DE_COURSE_ECOULE");
    }

    @ExceptionHandler(InscriptionNonEnAbandonException.class)
    ProblemDetail inscriptionNonEnAbandon(InscriptionNonEnAbandonException refus) {
        ProblemDetail probleme = conflit("Ce coureur n'est pas en abandon.", "INSCRIPTION_NON_EN_ABANDON");
        probleme.setProperty("statut", refus.statut());
        return probleme;
    }

    private static ProblemDetail conflit(String detail, String code) {
        return GestionnaireErreursCourses.probleme(HttpStatus.CONFLICT, "Conflit", detail, code);
    }
}
