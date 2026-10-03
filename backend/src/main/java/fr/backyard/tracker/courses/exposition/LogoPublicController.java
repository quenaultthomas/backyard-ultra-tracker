package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.LireLogo;
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.LogoIntrouvableException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lecture publique du logo d'une Course (seule route publique sous /api/courses, ouverte par la politique de
 * sécurité). Le type servi est celui reconnu par le serveur, jamais celui déclaré à l'envoi ; le navigateur revalide
 * à chaque usage par l'ETag (304 si inchangé, traité par Spring à partir de l'ETag de la réponse).
 */
@RestController
public class LogoPublicController {

    private static final String CHEMIN = "/api/courses/{id}/logo";
    private static final String MISE_EN_CACHE = "public, no-cache";
    private static final String SANS_DEVINETTE_DE_TYPE = "nosniff";

    private final LireLogo lireLogo;

    public LogoPublicController(LireLogo lireLogo) {
        this.lireLogo = lireLogo;
    }

    /** Adresse publique du logo d'une Course, sans paramètre de version. */
    static String adresse(UUID idCourse) {
        return CHEMIN.replace("{id}", idCourse.toString());
    }

    /** Course sans logo, inconnue ou identifiant invalide : même 404, sans distinction. */
    @GetMapping(CHEMIN)
    public ResponseEntity<byte[]> lireLogo(@PathVariable("id") String id) {
        Logo logo = lireLogo.executer(IdentifiantCourse.lire(id).orElseThrow(LogoIntrouvableException::new));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.typeMime()))
                .eTag(logo.empreinte())
                .header(HttpHeaders.CACHE_CONTROL, MISE_EN_CACHE)
                .header("X-Content-Type-Options", SANS_DEVINETTE_DE_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .body(logo.octets());
    }

    /**
     * Spring MVC sert implicitement HEAD sur un GET : seul GET est exposé, HEAD est traité comme toute méthode non
     * exposée (même 404 que pour un chemin inconnu).
     */
    @RequestMapping(path = CHEMIN, method = RequestMethod.HEAD)
    public void refuserHead() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(HttpMethod.HEAD.name(), List.of(HttpMethod.GET.name()));
    }
}
