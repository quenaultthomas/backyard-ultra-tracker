package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lecture pour les autres contextes : un identifiant désigne-t-il un Compte de rôle BENEVOLE ? */
@Service
public class VerifierBenevole {

    private final DepotComptes depotComptes;

    public VerifierBenevole(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    @Transactional(readOnly = true)
    public boolean estBenevole(UUID idCompte) {
        return depotComptes.trouverParId(idCompte).map(Compte::role).filter(Role.BENEVOLE::equals).isPresent();
    }
}
