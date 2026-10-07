package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import java.util.Optional;
import java.util.UUID;

/**
 * Lecture d'un identifiant de chemin (Course ou Inscription) : une valeur qui n'est pas un UUID ne désigne rien.
 */
final class IdentifiantCourse {

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

    static Optional<UUID> lire(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
