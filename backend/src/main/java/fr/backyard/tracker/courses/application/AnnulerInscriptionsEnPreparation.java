package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.Inscription;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Annulation des Inscriptions d'un Compte supprimé : celles des Courses qui permettent encore la désinscription sont
 * supprimées définitivement, les autres conservées intactes. Chaque Course est chargée sous verrou (comme pour la
 * désinscription), dans l'ordre croissant des identifiants pour éviter tout interblocage, et son statut relu sous ce
 * verrou ; une Course supprimée entre-temps est ignorée. Rejoint la transaction de l'appelant.
 */
@Service
public class AnnulerInscriptionsEnPreparation {

    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;

    public AnnulerInscriptionsEnPreparation(DepotCourses depotCourses, DepotInscriptions depotInscriptions) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
    }

    /** @return le nombre d'Inscriptions supprimées */
    @Transactional
    public int annulerPour(UUID compteId) {
        int annulees = 0;
        for (Map.Entry<UUID, List<Inscription>> parCourse : inscriptionsParCourse(compteId).entrySet()) {
            Optional<Course> course = depotCourses.parIdPourModification(parCourse.getKey());
            if (course.isPresent() && course.get().permetDesinscription()) {
                parCourse.getValue().forEach(inscription -> depotInscriptions.supprimer(inscription.id()));
                annulees += parCourse.getValue().size();
            }
        }
        return annulees;
    }

    /** Inscriptions du Compte regroupées par Course, Courses triées par identifiant croissant. */
    private Map<UUID, List<Inscription>> inscriptionsParCourse(UUID compteId) {
        return depotInscriptions.parCompte(compteId).stream()
                .collect(Collectors.groupingBy(Inscription::courseId, TreeMap::new, Collectors.toList()));
    }
}
