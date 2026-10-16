package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.ReintegrerCoureur;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réintégration d'un coureur en abandon depuis le pilotage. Réservé à ADMIN et ADMIN_MASTER par la politique de
 * sécurité (/api/administration/**). Sans corps (un corps éventuel est ignoré). Le journal est tenu par le cas
 * d'usage.
 */
@RestController
public class ReintegrationController {

    private final ReintegrerCoureur reintegrerCoureur;

    public ReintegrationController(ReintegrerCoureur reintegrerCoureur) {
        this.reintegrerCoureur = reintegrerCoureur;
    }

    /** Un identifiant d'Inscription illisible n'est refusé qu'après les contrôles de la Course. */
    @PostMapping("/api/administration/courses/{id}/inscriptions/{inscriptionId}/reintegration")
    public ResponseEntity<ReintegrationReponse> reintegrer(@PathVariable("id") String id,
                                                           @PathVariable("inscriptionId") String inscriptionId) {
        UUID courseId = IdentifiantCourse.deCourse(id);
        UUID idInscription = IdentifiantCourse.dInscriptionApresCourse(inscriptionId);
        ReintegrerCoureur.Resultat resultat = reintegrerCoureur.executer(courseId, idInscription);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ReintegrationReponse.depuis(resultat));
    }
}
