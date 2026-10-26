package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Réactivation d'un Compte BENEVOLE par un admin ou l'admin master ; il n'est réaffecté à aucune Course. */
@Service
public class ReactiverBenevole {

    private final DepotComptes depotComptes;

    public ReactiverBenevole(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    /** @throws CompteNonGerableException aucun Compte géré par cette route pour cet identifiant */
    @Transactional
    public void executer(UUID appelantId, UUID cibleId) {
        Reactivation.executer(depotComptes, ComptesGeres.BENEVOLES, appelantId, cibleId);
    }
}
