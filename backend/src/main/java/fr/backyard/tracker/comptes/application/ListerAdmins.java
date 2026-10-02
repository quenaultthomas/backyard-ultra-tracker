package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Liste des comptes ADMIN (jamais l'admin master), par date de création puis pseudo normalisé. */
@Service
public class ListerAdmins {

    private static final Comparator<Compte> ORDRE_DE_CREATION =
            Comparator.comparing(Compte::creeLe).thenComparing(Compte::pseudoNormalise);

    private final DepotComptes depotComptes;

    public ListerAdmins(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    @Transactional(readOnly = true)
    public List<Compte> executer() {
        return depotComptes.listerParRole(Role.ADMIN).stream().sorted(ORDRE_DE_CREATION).toList();
    }
}
