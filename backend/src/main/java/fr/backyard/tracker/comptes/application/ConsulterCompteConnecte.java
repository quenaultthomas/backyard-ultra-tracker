package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonConnectableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Relit en base le compte d'une session ouverte, pour restaurer l'état connecté. */
@Service
public class ConsulterCompteConnecte {

    private final DepotComptes depotComptes;

    public ConsulterCompteConnecte(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    /** @throws CompteNonConnectableException si le compte a disparu ou ne peut plus se connecter */
    @Transactional(readOnly = true)
    public Compte executer(UUID idCompte) {
        return depotComptes.trouverParId(idCompte)
                .filter(Compte::peutSeConnecter)
                .orElseThrow(CompteNonConnectableException::new);
    }
}
