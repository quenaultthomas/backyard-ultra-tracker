package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.application.PasserCoureurEnAdmin;
import fr.backyard.tracker.comptes.application.PasserCoureurEnBenevole;
import fr.backyard.tracker.comptes.application.RechercherCoureurs;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recherche de coureurs par fragment de pseudo (9.5) et changement de rôle d'un coureur en bénévole (9.6b) ou en admin
 * (9.7). Réservé à ADMIN et ADMIN_MASTER par la politique de sécurité (/api/administration/**), passage-admin à
 * ADMIN_MASTER seul, avant ce contrôleur ; la validation du fragment est celle du domaine (q absent compris).
 * Les journaux sont écrits par les cas d'usage, sans fragment, pseudo ni nom de Course.
 */
@RestController
@RequestMapping("/api/administration/coureurs")
public class CoureursController {

    private final RechercherCoureurs rechercherCoureurs;
    private final PasserCoureurEnBenevole passerCoureurEnBenevole;
    private final PasserCoureurEnAdmin passerCoureurEnAdmin;

    public CoureursController(RechercherCoureurs rechercherCoureurs,
                              PasserCoureurEnBenevole passerCoureurEnBenevole,
                              PasserCoureurEnAdmin passerCoureurEnAdmin) {
        this.rechercherCoureurs = rechercherCoureurs;
        this.passerCoureurEnBenevole = passerCoureurEnBenevole;
        this.passerCoureurEnAdmin = passerCoureurEnAdmin;
    }

    /** Principal : identifiant du compte de l'admin connecté (voir {@link SessionConnexion}). */
    @GetMapping
    public ResponseEntity<RechercheCoureursReponse> rechercherCoureurs(
            @RequestParam(name = "q", required = false) String q, @AuthenticationPrincipal UUID idAppelant) {
        RechercherCoureurs.Resultat resultat = rechercherCoureurs.executer(idAppelant, q);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(RechercheCoureursReponse.depuis(resultat));
    }

    /** Sans corps (un corps éventuel est ignoré) ; une valeur qui n'est pas un UUID est un Compte inconnu (404). */
    @PostMapping("/{id}/passage-benevole")
    public CompteReponse passerEnBenevole(@PathVariable("id") String id, @AuthenticationPrincipal UUID idAppelant) {
        return CompteReponse.depuis(passerCoureurEnBenevole.executer(idAppelant, IdentifiantCompte.lire(id)));
    }

    /** Sans corps (un corps éventuel est ignoré) ; une valeur qui n'est pas un UUID est un Compte inconnu (404). */
    @PostMapping("/{id}/passage-admin")
    public CompteReponse passerEnAdmin(@PathVariable("id") String id, @AuthenticationPrincipal UUID idAppelant) {
        return CompteReponse.depuis(passerCoureurEnAdmin.executer(idAppelant, IdentifiantCompte.lire(id)));
    }
}
