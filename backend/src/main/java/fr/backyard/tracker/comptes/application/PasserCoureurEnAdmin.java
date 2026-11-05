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
 * Changement de rôle d'un coureur en admin par l'admin master (9.7), dans une seule transaction, selon le déroulé
 * commun {@link ChangementRoleCoureur}. La restriction à ADMIN_MASTER est portée par la politique de sécurité HTTP.
 */
@Service
public class PasserCoureurEnAdmin {

    private final ChangementRoleCoureur changementRole;

    public PasserCoureurEnAdmin(DepotComptes depotComptes, InvalidationAutresSessions invalidationSessions,
                                ConsultationInscriptions consultationInscriptions) {
        this.changementRole = new ChangementRoleCoureur(depotComptes, invalidationSessions, consultationInscriptions);
    }

    /**
     * @return le Compte devenu admin
     * @throws CompteNonGerableException      aucun coureur connectable pour cet identifiant : rien n'est modifié
     * @throws ChangementRoleRefuseException le coureur est inscrit à une Course non TERMINEE : rien n'est modifié
     */
    @Transactional
    public Compte executer(UUID appelantId, UUID cibleId) {
        return changementRole.executer(appelantId, cibleId, Compte::passerEnAdmin,
                "Coureur passé en admin (compte {0} par {1})");
    }
}
