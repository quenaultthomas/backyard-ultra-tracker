package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Annuaire factice du suivi (6.2) : enregistre chaque appel et l'ordre d'appel, omet les Comptes inconnus. */
final class AnnuaireSuiviDeTest implements AnnuairePseudos {

    final Map<UUID, String> pseudos = new HashMap<>();
    final List<Collection<UUID>> appels = new ArrayList<>();
    /** Si posé, reçoit "pseudos" à chaque appel (ordre des lectures). */
    List<String> journalDesLectures;

    @Override
    public Map<UUID, String> pseudosDe(Collection<UUID> idsComptes) {
        appels.add(List.copyOf(idsComptes));
        if (journalDesLectures != null) {
            journalDesLectures.add("pseudos");
        }
        Map<UUID, String> trouves = new HashMap<>();
        idsComptes.stream().filter(pseudos::containsKey).forEach(id -> trouves.put(id, pseudos.get(id)));
        return trouves;
    }
}
