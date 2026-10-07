package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture pour les autres contextes : pseudo tel que saisi de chaque Compte trouvé, en une seule recherche. Un
 * identifiant inconnu est omis, sans exception.
 */
@Service
public class LirePseudos {

    private final DepotComptes depotComptes;

    public LirePseudos(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> executer(Collection<UUID> idsComptes) {
        if (idsComptes.isEmpty()) {
            return Map.of();
        }
        return depotComptes.trouverParIds(idsComptes).stream()
                .collect(Collectors.toUnmodifiableMap(Compte::id, compte -> compte.pseudo().valeur()));
    }
}
