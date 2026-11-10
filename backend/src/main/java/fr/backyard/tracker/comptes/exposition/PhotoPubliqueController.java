package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.LirePhoto;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Lecture publique d'une photo de profil par son jeton (10.1 RG7), ouverte par la politique de sécurité. Le type servi
 * est celui reconnu à l'envoi, jamais celui déclaré. Mise en cache 5 minutes par le navigateur (10.2 RG5) : l'adresse
 * change à chaque remplacement ; une photo retirée peut rester visible au plus 5 minutes là où elle est déjà en cache.
 * Revalidation ensuite par l'ETag (le jeton). Correctif audit jalon 10 RG3 : le jeton est d'abord résolu (base), puis
 * {@code If-None-Match} est comparé à l'ETag (règles HTTP de Spring : liste, {@code *}, forme faible) ; un 304 ne lit
 * jamais le fichier. Aucune donnée de Compte dans la réponse.
 */
@RestController
public class PhotoPubliqueController {

    private static final String MISE_EN_CACHE = "public, max-age=300";
    private static final String SANS_DEVINETTE_DE_TYPE = "nosniff";

    private final LirePhoto lirePhoto;

    public PhotoPubliqueController(LirePhoto lirePhoto) {
        this.lirePhoto = lirePhoto;
    }

    /** Jeton mal formé, inconnu, retiré ou fichier manquant : même 404 (PHOTO_INTROUVABLE), sans distinction. */
    @GetMapping(RoutePhotos.PREFIXE + "{jeton}")
    public ResponseEntity<byte[]> lirePhoto(@PathVariable("jeton") String jeton, HttpServletRequest requete) {
        ReferencePhoto reference = lirePhoto.resoudre(jeton);
        if (inchangeeChezLeClient(requete, reference)) {
            return enTetes(ResponseEntity.status(HttpStatus.NOT_MODIFIED), reference).build();
        }
        LirePhoto.PhotoLue photo = lirePhoto.lire(reference);
        return enTetes(ResponseEntity.ok(), reference).body(photo.octets());
    }

    /**
     * {@code If-None-Match} correspond à l'ETag du jeton. Évalué sans toucher à la réponse (aucun en-tête ni statut
     * posé ici) ; une requête conditionnelle {@code If-Match} garde le traitement habituel de Spring. {@code *}
     * correspond toujours : la photo vient d'être résolue, elle existe (Spring ne le traite pas pour un GET).
     */
    private static boolean inchangeeChezLeClient(HttpServletRequest requete, ReferencePhoto reference) {
        if (requete.getHeader(HttpHeaders.IF_NONE_MATCH) == null || requete.getHeader(HttpHeaders.IF_MATCH) != null) {
            return false;
        }
        return contientEtoile(requete) || new ServletWebRequest(requete).checkNotModified(etag(reference));
    }

    private static boolean contientEtoile(HttpServletRequest requete) {
        return Collections.list(requete.getHeaders(HttpHeaders.IF_NONE_MATCH)).stream()
                .flatMap(valeur -> Arrays.stream(valeur.split(",")))
                .anyMatch(valeur -> "*".equals(valeur.trim()));
    }

    private static ResponseEntity.BodyBuilder enTetes(ResponseEntity.BodyBuilder reponse, ReferencePhoto reference) {
        return reponse.contentType(MediaType.parseMediaType(reference.format().typeMime()))
                .eTag(etag(reference))
                .header(HttpHeaders.CACHE_CONTROL, MISE_EN_CACHE)
                .header("X-Content-Type-Options", SANS_DEVINETTE_DE_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString());
    }

    private static String etag(ReferencePhoto reference) {
        return "\"" + reference.jeton() + "\"";
    }
}
