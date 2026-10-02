package fr.backyard.tracker.comptes.exposition;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôle serveur de l'accès à l'espace d'administration : la règle de rôle (ADMIN_MASTER ou ADMIN)
 * est appliquée par la politique de sécurité, avant ce contrôleur, qui se contente de répondre 204.
 */
@RestController
public class AdministrationController {

    @GetMapping("/api/administration/acces")
    public ResponseEntity<Void> verifierAcces() {
        return ResponseEntity.noContent().build();
    }
}
