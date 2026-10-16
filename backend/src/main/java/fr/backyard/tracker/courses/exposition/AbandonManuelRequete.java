package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.ViolationValidation;
import java.util.List;

/**
 * Corps de POST /api/administration/courses/{id}/inscriptions/{inscriptionId}/abandon. Une valeur de motif inconnue
 * rend le corps illisible (lecture JSON) ; tout autre champ est ignoré.
 */
public record AbandonManuelRequete(MotifAbandon motif) {

    private static final String CHAMP = "motif";

    /**
     * Contrôle de format, avant toute lecture de la Course : le motif est obligatoire et doit être permis pour un
     * abandon manuel (règle du domaine).
     *
     * @throws DonneesCourseInvalidesException motif absent, null ou réservé à l'abandon automatique
     */
    MotifAbandon motifValide() {
        if (motif == null) {
            throw invalide("MOTIF_OBLIGATOIRE", "Le motif d'abandon est obligatoire.");
        }
        if (!motif.estPermisPourAbandonManuel()) {
            throw invalide("MOTIF_INVALIDE", "Le motif d'abandon doit être VOLONTAIRE, MANUEL ou AUTRE.");
        }
        return motif;
    }

    private static DonneesCourseInvalidesException invalide(String code, String message) {
        return new DonneesCourseInvalidesException(List.of(new ViolationValidation(CHAMP, code, message)));
    }
}
