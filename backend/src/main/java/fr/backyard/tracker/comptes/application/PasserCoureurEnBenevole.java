package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.ChangementRoleRefuseException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changement de rôle d'un coureur en bénévole par un admin ou l'admin master (9.6b), dans une seule transaction :
 * Compte relu sous verrou, garde du domaine (coureur connectable), refus s'il est inscrit à une Course non TERMINEE,
 * mise à jour du rôle, puis fermeture de toutes ses sessions après validation. Le compteur d'échecs de connexion
 * n'est pas touché (même Compte, même pseudo). Journal sans pseudo, mot de passe ni nom de Course (RG21).
 */
@Service
public class PasserCoureurEnBenevole {

    private static final Logger JOURNAL = System.getLogger(PasserCoureurEnBenevole.class.getName());

    private final DepotComptes depotComptes;
    private final InvalidationAutresSessions invalidationSessions;
    private final ConsultationInscriptions consultationInscriptions;

    public PasserCoureurEnBenevole(DepotComptes depotComptes, InvalidationAutresSessions invalidationSessions,
                                   ConsultationInscriptions consultationInscriptions) {
        this.depotComptes = depotComptes;
        this.invalidationSessions = invalidationSessions;
        this.consultationInscriptions = consultationInscriptions;
    }

    /**
     * @return le Compte devenu bénévole
     * @throws CompteNonGerableException      aucun coureur connectable pour cet identifiant : rien n'est modifié
     * @throws ChangementRoleRefuseException le coureur est inscrit à une Course non TERMINEE : rien n'est modifié
     */
    @Transactional
    public Compte executer(UUID appelantId, UUID cibleId) {
        Compte benevole = depotComptes.trouverParIdPourModification(cibleId)
                .orElseThrow(CompteNonGerableException::new)
                .passerEnBenevole();
        verifierAucuneCourseNonTerminee(cibleId);
        depotComptes.mettreAJour(benevole);
        JOURNAL.log(Level.INFO, "Coureur passé en bénévole (compte {0} par {1})", cibleId, appelantId);
        Desactivation.fermerSessionsApresValidation(invalidationSessions, cibleId);
        return benevole;
    }

    private void verifierAucuneCourseNonTerminee(UUID cibleId) {
        try {
            CourseNonTerminee.refuserSiInscrit(consultationInscriptions.coursesNonTerminees(cibleId));
        } catch (ChangementRoleRefuseException refus) {
            JOURNAL.log(Level.INFO, "Changement de rôle refusé (compte {0}, motif {1}, {2} course(s))", cibleId,
                    refus.motif(), refus.courses().size());
            throw refus;
        }
    }
}
