package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import java.util.Optional;
import java.util.UUID;

/**
 * Lecture d'un identifiant de chemin (Course ou Inscription) : une valeur qui n'est pas un UUID ne désigne rien.
 */
final class IdentifiantCourse {

    private static final UUID AUCUNE_INSCRIPTION = new UUID(0L, 0L);

    private IdentifiantCourse() {
    }

    /** @throws CourseIntrouvableException la valeur n'est pas un UUID : 404, comme un UUID inconnu */
    static UUID deCourse(String id) {
        return lire(id).orElseThrow(CourseIntrouvableException::new);
    }

    /** @throws InscriptionIntrouvableException la valeur n'est pas un UUID : 404, comme un UUID inconnu */
    static UUID dInscription(String id) {
        return lire(id).orElseThrow(InscriptionIntrouvableException::new);
    }

    /**
     * Un identifiant d'Inscription qui n'est pas un UUID devient l'UUID nul, que les Inscriptions (UUID aléatoires
     * de version 4) ne portent jamais : le cas d'usage contrôle ainsi la Course avant de répondre
     * INSCRIPTION_INTROUVABLE.
     */
    static UUID dInscriptionApresCourse(String id) {
        return lire(id).orElse(AUCUNE_INSCRIPTION);
    }

    static Optional<UUID> lire(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
