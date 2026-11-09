package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AnnuairePhotos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Annuaire factice des photos (10.2) : enregistre chaque appel, omet les Comptes sans photo ou inconnus.
 * Signature supposée du port : {@code Map<UUID, String> jetonsPhotoDe(Collection<UUID> idsComptes)}
 * (jeton opaque de photo par Compte, absent de la Map s'il n'y a pas de photo).
 */
final class AnnuairePhotosDeTest implements AnnuairePhotos {

    final Map<UUID, String> jetons = new HashMap<>();
    final List<Collection<UUID>> appels = new ArrayList<>();

    @Override
    public Map<UUID, String> jetonsPhotoDe(Collection<UUID> idsComptes) {
        appels.add(List.copyOf(idsComptes));
        Map<UUID, String> trouves = new HashMap<>();
        idsComptes.stream().filter(jetons::containsKey).forEach(id -> trouves.put(id, jetons.get(id)));
        return trouves;
    }
}
