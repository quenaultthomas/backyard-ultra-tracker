package fr.backyard.tracker.comptes.exposition;

import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import java.util.UUID;

/** Lecture d'un identifiant de Compte dans un chemin : une valeur qui n'est pas un UUID ne désigne aucun Compte. */
final class IdentifiantCompte {

    private IdentifiantCompte() {
    }

    /** @throws CompteNonGerableException la valeur n'est pas un UUID : 404, comme un UUID inconnu */
    static UUID lire(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            throw new CompteNonGerableException();
        }
    }
}
