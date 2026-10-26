package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retrait d'un bénévole désactivé des Courses qui n'ont pas démarré ; ses autres affectations sont conservées. Chaque
 * Course candidate est chargée sous verrou dans l'ordre croissant des identifiants (comme l'annulation des
 * Inscriptions), son statut relu sous ce verrou ; une Course supprimée entre-temps est ignorée. Rejoint la transaction
 * de l'appelant.
 */
@Service
public class DesaffecterBenevoleDesCoursesEnPreparation {

    private final DepotCourses depotCourses;

    public DesaffecterBenevoleDesCoursesEnPreparation(DepotCourses depotCourses) {
        this.depotCourses = depotCourses;
    }

    /** @return le nombre de Courses dont le bénévole a été retiré */
    @Transactional
    public int desaffecter(UUID benevoleId) {
        int retirees = 0;
        for (UUID idCourse : coursesCandidates(benevoleId)) {
            Optional<Course> course = depotCourses.parIdPourModification(idCourse);
            if (course.isPresent() && course.get().retirerBenevoleDesactive(benevoleId)) {
                depotCourses.enregistrer(course.get());
                retirees++;
            }
        }
        return retirees;
    }

    /** Courses du bénévole qui retirent encore un bénévole désactivé, par identifiant croissant. */
    private List<UUID> coursesCandidates(UUID benevoleId) {
        return depotCourses.parBenevole(benevoleId).stream()
                .filter(Course::retireLesBenevolesDesactives)
                .map(Course::id)
                .sorted()
                .toList();
    }
}
