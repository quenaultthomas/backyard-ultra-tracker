package fr.backyard.tracker.assemblage;

import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException;
import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException.CourseEnCours;
import fr.backyard.tracker.comptes.domaine.DesaffectationBenevole;
import fr.backyard.tracker.courses.application.DesaffecterBenevoleDesCoursesEnPreparation;
import fr.backyard.tracker.courses.application.RetirerBenevoleDeToutesSesCourses;
import fr.backyard.tracker.courses.application.RetraitAffectations;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pont entre les contextes : implémente le port DesaffectationBenevole de comptes par les cas d'usage de courses, dans
 * la transaction de la désactivation ou de la suppression du bénévole. Exception nommée des règles ArchUnit (8.1
 * RG18), comme {@link AnnuaireBenevolesAdapter}, {@link AnnuairePseudosAdapter} et
 * {@link AnnulationInscriptionsAdapter}.
 */
@Component
public class DesaffectationBenevoleAdapter implements DesaffectationBenevole {

    private final DesaffecterBenevoleDesCoursesEnPreparation desaffecter;
    private final RetirerBenevoleDeToutesSesCourses retirer;

    public DesaffectationBenevoleAdapter(DesaffecterBenevoleDesCoursesEnPreparation desaffecter,
                                         RetirerBenevoleDeToutesSesCourses retirer) {
        this.desaffecter = desaffecter;
        this.retirer = retirer;
    }

    @Override
    public int desaffecterDesCoursesEnPreparation(UUID benevoleId) {
        return desaffecter.desaffecter(benevoleId);
    }

    @Override
    public int desaffecterDeToutesLesCourses(UUID benevoleId) {
        RetraitAffectations retrait = retirer.retirer(benevoleId);
        if (retrait.estRefuse()) {
            throw new BenevoleAffecteCourseEnCoursException(retrait.bloquantes().stream()
                    .map(course -> new CourseEnCours(course.id(), course.nom()))
                    .toList());
        }
        return retrait.retirees();
    }
}
