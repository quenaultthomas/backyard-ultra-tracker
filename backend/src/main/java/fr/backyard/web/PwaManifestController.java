package fr.backyard.web;

import fr.backyard.config.PwaPaths;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Manifeste de la PWA (RG44, RG53 inc. 4), servi en {@code application/manifest+json} et sans cache long.
 * Le service des ressources statiques ne connaît pas l'extension {@code .webmanifest} (il répondrait
 * {@code application/octet-stream}) : ce fichier est donc servi ici, avec son type explicite.
 */
@Controller
public class PwaManifestController {

    static final MediaType MANIFEST_MEDIA_TYPE = MediaType.parseMediaType("application/manifest+json");

    private final Resource manifest = new ClassPathResource("static" + PwaPaths.MANIFEST);

    @GetMapping(PwaPaths.MANIFEST)
    public ResponseEntity<Resource> manifest() {
        return ResponseEntity.ok()
            .contentType(MANIFEST_MEDIA_TYPE)
            .cacheControl(CacheControl.noCache())
            .body(manifest);
    }
}
