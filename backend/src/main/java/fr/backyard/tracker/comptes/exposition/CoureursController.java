package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.RechercherCoureurs;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recherche de coureurs par fragment de pseudo (9.5). Réservé à ADMIN et ADMIN_MASTER par la politique de sécurité
 * (/api/administration/**), avant ce contrôleur ; la validation du fragment est celle du domaine (q absent compris).
 * Le journal est écrit par le cas d'usage, sans le fragment.
 */
@RestController
@RequestMapping("/api/administration/coureurs")
public class CoureursController {

    private final RechercherCoureurs rechercherCoureurs;

    public CoureursController(RechercherCoureurs rechercherCoureurs) {
        this.rechercherCoureurs = rechercherCoureurs;
    }

    /** Principal : identifiant du compte de l'admin connecté (voir {@link SessionConnexion}). */
    @GetMapping
    public ResponseEntity<RechercheCoureursReponse> rechercherCoureurs(
            @RequestParam(name = "q", required = false) String q, @AuthenticationPrincipal UUID idAppelant) {
        RechercherCoureurs.Resultat resultat = rechercherCoureurs.executer(idAppelant, q);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RechercheCoureursReponse.depuis(resultat));
    }
}
