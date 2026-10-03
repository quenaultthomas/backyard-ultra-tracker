package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException;
import fr.backyard.tracker.comptes.domaine.CompteNonConnectableException;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.MotDePasseActuelIncorrectException;
import fr.backyard.tracker.comptes.domaine.NouveauMotDePasseIdentiqueException;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduction des erreurs en ProblemDetail avec une propriété « code » stable.
 * Les messages ne reprennent jamais le contenu reçu (aucun écho d'un mot de passe).
 */
@RestControllerAdvice
public class GestionnaireErreurs extends ResponseEntityExceptionHandler {

    private static final String TITRE_REQUETE_INVALIDE = "Requête invalide";
    /** Explicite : Spring 7 omet le type par défaut, le contrat d'API l'exige. */
    private static final URI TYPE_GENERIQUE = URI.create("about:blank");
    private static final Logger JOURNAL = LoggerFactory.getLogger(GestionnaireErreurs.class);

    private final SessionConnexion sessionConnexion;

    public GestionnaireErreurs(SessionConnexion sessionConnexion) {
        this.sessionConnexion = sessionConnexion;
    }

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

    /** Réponse identique quelle que soit la cause ; journal sans pseudo ni mot de passe. */
    @ExceptionHandler(IdentifiantsInvalidesException.class)
    ProblemDetail identifiantsInvalides() {
        JOURNAL.info("Échec de connexion");
        return probleme(HttpStatus.UNAUTHORIZED, "Authentification échouée", "Pseudo ou mot de passe incorrect.",
                "IDENTIFIANTS_INVALIDES");
    }

    /** Blocage temporaire : réponse identique pour tout pseudo, aucune session créée. */
    @ExceptionHandler(ConnexionBloqueeException.class)
    ResponseEntity<ProblemDetail> connexionBloquee(ConnexionBloqueeException exception) {
        long secondes = exception.tempsRestant().toSeconds();
        ProblemDetail probleme = probleme(HttpStatus.TOO_MANY_REQUESTS, "Trop de tentatives",
                "Trop de tentatives de connexion. Réessayez plus tard.", "TENTATIVES_EXCESSIVES");
        probleme.setProperty("reessayerDansSecondes", secondes);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(secondes))
                .body(probleme);
    }

    /** Réponse identique quelle que soit la cause (valeur fausse ou hors bornes), sans erreurs par champ. */
    @ExceptionHandler(MotDePasseActuelIncorrectException.class)
    ProblemDetail motDePasseActuelIncorrect() {
        return probleme(HttpStatus.BAD_REQUEST, "Mot de passe incorrect", "Le mot de passe actuel est incorrect.",
                "MOT_DE_PASSE_ACTUEL_INCORRECT");
    }

    @ExceptionHandler(NouveauMotDePasseIdentiqueException.class)
    ProblemDetail nouveauMotDePasseIdentique() {
        return probleme(HttpStatus.BAD_REQUEST, TITRE_REQUETE_INVALIDE,
                "Le nouveau mot de passe doit être différent de l'actuel.", "NOUVEAU_MOT_DE_PASSE_IDENTIQUE");
    }

    /** Session d'un compte disparu ou anonymisé : la session est fermée, comme pour une session absente. */
    @ExceptionHandler({CompteNonConnectableException.class, CompteIntrouvableOuInutilisableException.class})
    ProblemDetail compteNonConnectable(HttpServletRequest requete, HttpServletResponse reponse) {
        sessionConnexion.fermer(requete, reponse);
        return probleme(HttpStatus.UNAUTHORIZED, "Authentification requise", "Vous devez être connecté.",
                "NON_AUTHENTIFIE");
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(NoHandlerFoundException exception,
                                                                   HttpHeaders entetes, HttpStatusCode statut,
                                                                   WebRequest requete) {
        return introuvable();
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
                                                                    HttpHeaders entetes, HttpStatusCode statut,
                                                                    WebRequest requete) {
        return introuvable();
    }

    /**
     * Une méthode non exposée sur un chemin existant (PATCH /api/administration/courses/{id} alors que seuls
     * GET et PUT existent) est traitée comme une ressource inexistante : même 404 que pour un chemin inconnu.
     */
    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpHeaders entetes, HttpStatusCode statut,
            WebRequest requete) {
        return introuvable();
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                  HttpHeaders entetes, HttpStatusCode statut,
                                                                  WebRequest requete) {
        ProblemDetail probleme = probleme(HttpStatus.BAD_REQUEST, TITRE_REQUETE_INVALIDE,
                "Le corps de la requête est illisible.", "CORPS_ILLISIBLE");
        return ResponseEntity.badRequest().body(probleme);
    }

    private static ResponseEntity<Object> introuvable() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(probleme(HttpStatus.NOT_FOUND, "Introuvable",
                "La ressource demandée est introuvable.", "RESSOURCE_INTROUVABLE"));
    }

    private static ProblemDetail probleme(HttpStatus statut, String titre, String detail, String code) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, detail);
        probleme.setType(TYPE_GENERIQUE);
        probleme.setTitle(titre);
        probleme.setProperty("code", code);
        return probleme;
    }
}
