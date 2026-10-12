package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.EnregistrerPassage;
import fr.backyard.tracker.courses.domaine.BenevoleNonAffecteException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scan d'un Passage par un bénévole. Réservé à BENEVOLE par la politique de sécurité (/api/benevole/**) ;
 * l'affectation à la Course est contrôlée par le cas d'usage. L'identité vient uniquement de la session, le jeton
 * uniquement du corps. Chemin fréquent : aucun journal (ni jeton, ni pseudo, ni Compte).
 */
@RestController
public class PassageController {

    private final EnregistrerPassage enregistrerPassage;

    public PassageController(EnregistrerPassage enregistrerPassage) {
        this.enregistrerPassage = enregistrerPassage;
    }

    /**
     * Le corps est contrôlé avant la Course. Un identifiant de Course qui n'est pas un UUID est refusé comme un
     * bénévole non affecté : rien ne distingue une Course inconnue. 201 à la création, 200 au rejeu d'un scan.
     */
    @PostMapping(path = "/api/benevole/courses/{courseId}/passages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PassageReponse> scanner(@AuthenticationPrincipal UUID idBenevole,
                                                  @PathVariable("courseId") String courseId,
                                                  @RequestBody PassageRequete requete) {
        PassageRequete.Scan scan = requete.versScan();
        UUID idCourse = IdentifiantCourse.lire(courseId).orElseThrow(BenevoleNonAffecteException::new);
        EnregistrerPassage.Resultat resultat = enregistrerPassage.executer(idCourse, idBenevole, scan.jeton(),
                scan.identifiantScan(), scan.ageDuScanMillisecondes());
        return ResponseEntity.status(resultat.rejeu() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(PassageReponse.depuis(resultat));
    }
}
