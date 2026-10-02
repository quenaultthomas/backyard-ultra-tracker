package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduction des erreurs en ProblemDetail avec une propriété « code » stable.
 * Les messages ne reprennent jamais le contenu reçu (aucun écho d'un mot de passe).
 */
@RestControllerAdvice
public class GestionnaireErreurs extends ResponseEntityExceptionHandler {

    private static final String TITRE_REQUETE_INVALIDE = "Requête invalide";
    /** Explicite : Spring 7 omet le type par défaut, le contrat d'API l'exige. */
    private static final URI TYPE_GENERIQUE = URI.create("about:blank");

    @ExceptionHandler(DonneesCompteInvalidesException.class)
    ProblemDetail donneesInvalides(DonneesCompteInvalidesException exception) {
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, TITRE_REQUETE_INVALIDE,
                "Certains champs sont invalides.", "VALIDATION_ECHOUEE");
        List<ErreurChamp> erreurs = exception.violations().stream().map(ErreurChamp::depuis).toList();
        probleme.setProperty("erreurs", erreurs);
        return probleme;
    }

    @ExceptionHandler(PseudoDejaUtiliseException.class)
    ProblemDetail pseudoDejaUtilise() {
        return probleme(HttpStatus.CONFLICT, "Conflit", "Ce pseudo est déjà utilisé.", "PSEUDO_DEJA_UTILISE");
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                  HttpHeaders entetes, HttpStatusCode statut,
                                                                  WebRequest requete) {
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, TITRE_REQUETE_INVALIDE,
                "Le corps de la requête est illisible.", "CORPS_ILLISIBLE");
        return ResponseEntity.badRequest().body(probleme);
    }

    private static ProblemDetail probleme(HttpStatus statut, String titre, String detail, String code) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, detail);
        probleme.setType(TYPE_GENERIQUE);
        probleme.setTitle(titre);
        probleme.setProperty("code", code);
        return probleme;
    }
}
