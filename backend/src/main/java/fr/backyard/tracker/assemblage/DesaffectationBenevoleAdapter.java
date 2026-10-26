package fr.backyard.tracker.assemblage;

import fr.backyard.tracker.comptes.domaine.DesaffectationBenevole;
import fr.backyard.tracker.courses.application.DesaffecterBenevoleDesCoursesEnPreparation;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pont entre les contextes : implémente le port DesaffectationBenevole de comptes par le cas d'usage de courses, dans
 * la transaction de la désactivation du bénévole. Exception nommée des règles ArchUnit (8.1 RG18), comme
 * {@link AnnuaireBenevolesAdapter}, {@link AnnuairePseudosAdapter} et {@link AnnulationInscriptionsAdapter}.
 */
@Component
public class DesaffectationBenevoleAdapter implements DesaffectationBenevole {

    private final DesaffecterBenevoleDesCoursesEnPreparation desaffecter;

    public DesaffectationBenevoleAdapter(DesaffecterBenevoleDesCoursesEnPreparation desaffecter) {
        this.desaffecter = desaffecter;
    }

    @Override
    public int desaffecterDesCoursesEnPreparation(UUID benevoleId) {
        return desaffecter.desaffecter(benevoleId);
    }
}
