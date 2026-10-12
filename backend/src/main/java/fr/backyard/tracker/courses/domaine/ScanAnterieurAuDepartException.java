package fr.backyard.tracker.courses.domaine;

import java.util.UUID;

/** L'instant d'un scan précède le départ de la Course. */
public class ScanAnterieurAuDepartException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScanAnterieurAuDepartException(UUID idCourse) {
        super("Scan antérieur au départ (course " + idCourse + ")");
    }
}
