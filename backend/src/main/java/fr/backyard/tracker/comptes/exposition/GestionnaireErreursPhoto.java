package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.PhotoIntrouvableException;
import fr.backyard.tracker.comptes.domaine.PhotoInvalideException;
import fr.backyard.tracker.comptes.domaine.PhotoNonAutoriseeException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * Erreurs propres à la photo de profil en ProblemDetail. Prioritaire sur le gestionnaire global, qui traiterait sinon le
 * dépassement de taille multipart avec un corps générique ; le logo garde son propre gestionnaire (le code 413 dépend
 * donc du contrôleur, c'est-à-dire du chemin). Aucun message ne reprend le nom, le type déclaré, le contenu du fichier
 * ni le jeton demandé ; les refus ne sont pas journalisés.
 */
@RestControllerAdvice(assignableTypes = {PhotoController.class, PhotoPubliqueController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GestionnaireErreursPhoto {

    private static final URI TYPE_GENERIQUE = URI.create("about:blank");
    private static final String TITRE_REQUETE_INVALIDE = "Requête invalide";
    /** Instance fixe du 404 : identique pour tous les jetons, sans écho du chemin demandé (CA9). */
    private static final URI INSTANCE_PHOTOS = URI.create("/api/photos");

    @ExceptionHandler(PhotoInvalideException.class)
    ProblemDetail photoInvalide(PhotoInvalideException exception) {
        return switch (exception.motif()) {
            case REQUISE -> probleme(HttpStatus.BAD_REQUEST, TITRE_REQUETE_INVALIDE,
                    "Le fichier de la photo est obligatoire.", "PHOTO_REQUISE");
            case TROP_VOLUMINEUSE -> photoTropVolumineuse();
            case FORMAT_INVALIDE -> probleme(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Format non supporté",
                    "La photo doit être une image PNG, JPEG ou WebP.", "PHOTO_FORMAT_INVALIDE");
        };
    }

    /** Limite multipart du serveur, réglée sur la taille maximale du domaine : même réponse que le domaine. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail photoTropVolumineuse() {
        return probleme(HttpStatus.CONTENT_TOO_LARGE, "Fichier trop volumineux",
                "La photo ne doit pas dépasser 2 Mo.", "PHOTO_TROP_VOLUMINEUSE");
    }

    @ExceptionHandler(MultipartException.class)
    ProblemDetail multipartIllisible() {
        return probleme(HttpStatus.BAD_REQUEST, TITRE_REQUETE_INVALIDE, "Le corps de la requête est illisible.",
                "CORPS_ILLISIBLE");
    }

    /** Défense en profondeur : même réponse que le refus de rôle de la politique de sécurité HTTP. */
    @ExceptionHandler(PhotoNonAutoriseeException.class)
    ProblemDetail photoNonAutorisee() {
        return probleme(HttpStatus.FORBIDDEN, "Accès refusé", "Vous n'avez pas les droits nécessaires.",
                "ACCES_REFUSE");
    }

    /** Jamais mis en cache (10.2 RG5) : une absence n'est pas mémorisée par le navigateur. */
    @ExceptionHandler(PhotoIntrouvableException.class)
    ResponseEntity<ProblemDetail> photoIntrouvable() {
        ProblemDetail probleme = probleme(HttpStatus.NOT_FOUND, "Introuvable", "La photo est introuvable.",
                "PHOTO_INTROUVABLE");
        probleme.setInstance(INSTANCE_PHOTOS);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).body(probleme);
    }

    private static ProblemDetail probleme(HttpStatus statut, String titre, String detail, String code) {
        ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, detail);
        probleme.setType(TYPE_GENERIQUE);
        probleme.setTitle(titre);
        probleme.setProperty("code", code);
        return probleme;
    }
}
