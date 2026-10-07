package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DesinscriptionImpossibleException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Désinscription d'un Compte coureur (identifié par la session, le rôle est contrôlé avant ce cas d'usage) : son
 * Inscription est supprimée définitivement, jeton QR compris. La Course est chargée sous verrou, comme pour
 * l'inscription et le démarrage, puis l'Inscription est relue sous ce verrou : une désinscription concurrente, la
 * suppression de la Course ou son démarrage sont ainsi sérialisés. Ordre des refus : introuvable, puis course non
 * en préparation.
 */
@Service
public class SeDesinscrire {

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;

    public SeDesinscrire(DepotCourses depotCourses, DepotInscriptions depotInscriptions) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
    }

    /**
     * @return l'Inscription supprimée
     * @throws InscriptionIntrouvableException aucune Inscription de ce Compte pour cet identifiant ; rien n'est supprimé
     * @throws DesinscriptionImpossibleException la Course n'est plus EN_PREPARATION ; rien n'est supprimé
     */
    @Transactional
    public Inscription executer(UUID compteId, UUID inscriptionId) {
        Inscription inscription = inscriptionDuCompte(compteId, inscriptionId);
        Course course = depotCourses.parIdPourModification(inscription.courseId())
                .orElseThrow(InscriptionIntrouvableException::new);
        Inscription inscriptionSousVerrou = inscriptionDuCompte(compteId, inscriptionId);
        course.autoriserDesinscription();
        depotInscriptions.supprimer(inscriptionSousVerrou.id());
        return inscriptionSousVerrou;
    }

    private Inscription inscriptionDuCompte(UUID compteId, UUID inscriptionId) {
        return depotInscriptions.parIdEtCompte(inscriptionId, compteId)
                .orElseThrow(InscriptionIntrouvableException::new);
    }
}
