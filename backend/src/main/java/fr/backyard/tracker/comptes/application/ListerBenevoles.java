package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Liste des comptes BENEVOLE, par date de création puis pseudo normalisé. */
@Service
public class ListerBenevoles {

    private final DepotComptes depotComptes;

    public ListerBenevoles(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    @Transactional(readOnly = true)
    public List<Compte> executer() {
        return depotComptes.listerParRole(Role.BENEVOLE).stream().sorted(Compte.ORDRE_DE_CREATION).toList();
    }
}
