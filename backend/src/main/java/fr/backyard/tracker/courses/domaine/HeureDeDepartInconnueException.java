package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** Une Course EN_COURS sans heure de départ ne permet de placer aucun Passage sur une Boucle. */
public class HeureDeDepartInconnueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HeureDeDepartInconnueException(UUID idCourse) {
        super("Heure de départ inconnue (course " + idCourse + ")");
    }
}
