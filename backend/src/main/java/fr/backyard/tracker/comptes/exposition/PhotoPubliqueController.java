package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.LirePhoto;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lecture publique d'une photo de profil par son jeton (10.1 RG7), ouverte par la politique de sécurité. Le type servi
 * est celui reconnu à l'envoi, jamais celui déclaré ; le navigateur revalide à chaque usage par l'ETag (le jeton, qui
 * change à chaque remplacement) : 304 si inchangé, traité par Spring à partir de l'ETag de la réponse. Aucune donnée
 * de Compte dans la réponse.
 */
@RestController
public class PhotoPubliqueController {

    private static final String PREFIXE = "/api/photos/";
    private static final String MISE_EN_CACHE = "public, no-cache";
    private static final String SANS_DEVINETTE_DE_TYPE = "nosniff";

    private final LirePhoto lirePhoto;

    public PhotoPubliqueController(LirePhoto lirePhoto) {
        this.lirePhoto = lirePhoto;
    }

    /** Unique calcul de l'adresse publique relative d'une photo (10.1 RG11). */
    static String adresse(String jeton) {
        return PREFIXE + jeton;
    }

    /** Jeton mal formé, inconnu, retiré ou fichier manquant : même 404 (PHOTO_INTROUVABLE), sans distinction. */
    @GetMapping(PREFIXE + "{jeton}")
    public ResponseEntity<byte[]> lirePhoto(@PathVariable("jeton") String jeton) {
        LirePhoto.PhotoLue photo = lirePhoto.executer(jeton);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(photo.format().typeMime()))
                .eTag(photo.jeton())
                .header(HttpHeaders.CACHE_CONTROL, MISE_EN_CACHE)
                .header("X-Content-Type-Options", SANS_DEVINETTE_DE_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .body(photo.octets());
    }
}
