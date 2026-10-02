package fr.backyard.tracker.comptes.exposition;

import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fournit à l'application Angular le cookie XSRF-TOKEN à renvoyer dans l'en-tête X-XSRF-TOKEN. */
@RestController
public class CsrfController {

    @GetMapping("/api/csrf")
    public ResponseEntity<Void> obtenirJetonCsrf(CsrfToken jeton) {
        // Le jeton est différé : le lire force sa génération et l'écriture du cookie.
        jeton.getToken();
        return ResponseEntity.noContent().build();
    }
}
