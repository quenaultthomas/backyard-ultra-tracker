package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Réactivation d'un Compte ADMIN par l'admin master ; l'admin master, toujours actif, est une réactivation sans effet.
 */
@Service
public class ReactiverAdmin {

    private final DepotComptes depotComptes;

    public ReactiverAdmin(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    /** @throws CompteNonGerableException aucun Compte géré par cette route pour cet identifiant */
    @Transactional
    public void executer(UUID appelantId, UUID cibleId) {
        Reactivation.executer(depotComptes, ComptesGeres.ADMINS, appelantId, cibleId);
    }
}
