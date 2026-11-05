package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.ChangementRoleRefuseException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changement de rôle d'un coureur en bénévole par un admin ou l'admin master (9.6b), dans une seule transaction,
 * selon le déroulé commun {@link ChangementRoleCoureur}.
 */
@Service
public class PasserCoureurEnBenevole {

    private final ChangementRoleCoureur changementRole;

    public PasserCoureurEnBenevole(DepotComptes depotComptes, InvalidationAutresSessions invalidationSessions,
                                   ConsultationInscriptions consultationInscriptions) {
        this.changementRole = new ChangementRoleCoureur(depotComptes, invalidationSessions, consultationInscriptions);
    }

    /**
     * @return le Compte devenu bénévole
     * @throws CompteNonGerableException      aucun coureur connectable pour cet identifiant : rien n'est modifié
     * @throws ChangementRoleRefuseException le coureur est inscrit à une Course non TERMINEE : rien n'est modifié
     */
    @Transactional
    public Compte executer(UUID appelantId, UUID cibleId) {
        return changementRole.executer(appelantId, cibleId, Compte::passerEnBenevole,
                "Coureur passé en bénévole (compte {0} par {1})");
    }
}
