package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.AbandonnerCoureur;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Abandon manuel d'un coureur depuis le pilotage. Réservé à ADMIN et ADMIN_MASTER par la politique de sécurité
 * (/api/administration/**). Le journal est tenu par le cas d'usage.
 */
@RestController
public class AbandonManuelController {

    private final AbandonnerCoureur abandonnerCoureur;

    public AbandonManuelController(AbandonnerCoureur abandonnerCoureur) {
        this.abandonnerCoureur = abandonnerCoureur;
    }

    /**
     * Le corps est contrôlé avant les identifiants (400 même pour une Course inexistante), la Course avant
     * l'Inscription.
     */
    @PostMapping(path = "/api/administration/courses/{id}/inscriptions/{inscriptionId}/abandon",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public AbandonManuelReponse abandonner(@PathVariable("id") String id,
                                           @PathVariable("inscriptionId") String inscriptionId,
                                           @RequestBody AbandonManuelRequete requete) {
        MotifAbandon motif = requete.motifValide();
        UUID courseId = IdentifiantCourse.deCourse(id);
        UUID idInscription = IdentifiantCourse.dInscriptionApresCourse(inscriptionId);
        return AbandonManuelReponse.depuis(abandonnerCoureur.executer(courseId, idInscription, motif));
    }
}
