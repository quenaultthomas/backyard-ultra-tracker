package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lecture pour les autres contextes : un identifiant désigne-t-il un Compte de rôle BENEVOLE, actif ou non ? */
@Service
public class VerifierBenevole {

    private final DepotComptes depotComptes;

    public VerifierBenevole(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    @Transactional(readOnly = true)
    public boolean estBenevole(UUID idCompte) {
        return benevole(idCompte).isPresent();
    }

    /** Vrai si le Compte existe, est de rôle BENEVOLE et actif (8.1 RG13). */
    @Transactional(readOnly = true)
    public boolean estBenevoleActif(UUID idCompte) {
        return benevole(idCompte).filter(Compte::actif).isPresent();
    }

    private Optional<Compte> benevole(UUID idCompte) {
        return depotComptes.trouverParId(idCompte).filter(compte -> compte.role() == Role.BENEVOLE);
    }
}
