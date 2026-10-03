package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import java.util.Optional;
import java.util.UUID;

/** Lecture de l'identifiant de Course d'un chemin : une valeur qui n'est pas un UUID ne désigne aucune Course. */
final class IdentifiantCourse {

    private IdentifiantCourse() {
    }

    /** @throws CourseIntrouvableException la valeur n'est pas un UUID : 404, comme un UUID inconnu */
    static UUID deCourse(String id) {
        return lire(id).orElseThrow(CourseIntrouvableException::new);
    }

    static Optional<UUID> lire(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
