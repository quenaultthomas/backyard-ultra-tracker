package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.DecalageHorlogeExcessifException;
import fr.backyard.tracker.comptes.domaine.DureeAvanceInvalideException;
import java.net.URI;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erreurs de l'horloge pilotable (profil e2e) en ProblemDetail, même format que le reste de l'API. Le corps illisible
 * et le 404 restent traités globalement. Les messages ne reprennent jamais la valeur reçue.
 */
@RestControllerAdvice(assignableTypes = HorlogeController.class)
@Profile("e2e")
@Order(0)
public class GestionnaireErreursHorloge {

    private static final URI TYPE_GENERIQUE = URI.create("about:blank");

    @ExceptionHandler(DureeAvanceInvalideException.class)
    ProblemDetail dureeInvalide(DureeAvanceInvalideException exception) {
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, "Requête invalide",
                "Certains champs sont invalides.", "VALIDATION_ECHOUEE");
        probleme.setProperty("erreurs", List.of(new ErreurChamp("duree", "DUREE_INVALIDE", exception.getMessage())));
        return probleme;
    }

    @ExceptionHandler(DecalageHorlogeExcessifException.class)
    ProblemDetail decalageExcessif(DecalageHorlogeExcessifException exception) {
        return probleme(HttpStatus.CONFLICT, "Conflit", exception.getMessage(), "DECALAGE_HORLOGE_EXCESSIF");
    }

    private static ProblemDetail probleme(HttpStatus statut, String titre, String detail, String code) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, detail);
        probleme.setType(TYPE_GENERIQUE);
        probleme.setTitle(titre);
        probleme.setProperty("code", code);
        return probleme;
    }
}
