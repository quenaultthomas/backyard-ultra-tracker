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
 * Lecture pour les autres contextes (10.2 RG2) : jeton public de la photo de chaque Compte trouvé qui en a une, en une
 * seule recherche. Un identifiant inconnu ou un Compte sans photo (dont un Compte anonymisé) est omis, sans exception.
 */
@Service
public class LireJetonsPhoto {

    private final DepotComptes depotComptes;

    public LireJetonsPhoto(DepotComptes depotComptes) {
        this.depotComptes = depotComptes;
    }

    @Transactional(readOnly = true)
    public Map<UUID, String> executer(Collection<UUID> idsComptes) {
        if (idsComptes.isEmpty()) {
            return Map.of();
        }
        return depotComptes.trouverParIds(idsComptes).stream().filter(compte -> compte.photo() != null)
                .collect(Collectors.toUnmodifiableMap(Compte::id, compte -> compte.photo().jeton()));
    }
}
