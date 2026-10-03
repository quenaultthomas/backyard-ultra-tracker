package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.AffecterBenevoles;
import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import fr.backyard.tracker.courses.domaine.ViolationValidation;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Corps de PUT /api/administration/courses/{id}/benevoles : l'ensemble complet souhaité. Tout autre champ est ignoré. */
public record AffecterBenevolesRequete(List<UUID> benevoleIds) {

    static final int NOMBRE_MAX_BENEVOLES = 500;
    private static final String CHAMP = "benevoleIds";

    /** Identifiants masqués : le journal (DEBUG de Spring compris) ne les reprend pas. */
    @Override
    public String toString() {
        return "AffecterBenevolesRequete[contenu masqué]";
    }

    /**
     * Contrôle de format, avant toute lecture de la Course.
     *
     * @param idCourse identifiant brut du chemin (404 s'il n'est pas un UUID, après le format)
     * @throws DonneesCourseInvalidesException liste absente, avec un élément null, ou trop longue
     */
    AffecterBenevoles.Commande versCommande(String idCourse) {
        if (benevoleIds == null || benevoleIds.stream().anyMatch(Objects::isNull)) {
            throw invalide("BENEVOLES_REQUIS", "La liste des bénévoles est obligatoire.");
        }
        if (benevoleIds.size() > NOMBRE_MAX_BENEVOLES) {
            throw invalide("BENEVOLES_TROP_NOMBREUX", "Une course ne peut pas avoir plus de 500 bénévoles.");
        }
        return new AffecterBenevoles.Commande(IdentifiantCourse.deCourse(idCourse), benevoleIds);
    }

    private static DonneesCourseInvalidesException invalide(String code, String message) {
        return new DonneesCourseInvalidesException(List.of(new ViolationValidation(CHAMP, code, message)));
    }
}
