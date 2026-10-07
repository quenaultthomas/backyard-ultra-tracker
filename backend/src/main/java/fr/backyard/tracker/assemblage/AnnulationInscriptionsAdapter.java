package fr.backyard.tracker.assemblage;

import fr.backyard.tracker.comptes.domaine.AnnulationInscriptions;
import fr.backyard.tracker.courses.application.AnnulerInscriptionsEnPreparation;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pont entre les contextes : implémente le port AnnulationInscriptions de comptes par le cas d'usage de courses, dans
 * la transaction de la suppression du Compte. Exception nommée des règles ArchUnit (3.6, RG16), comme
 * {@link AnnuaireBenevolesAdapter} et {@link AnnuairePseudosAdapter}.
 */
@Component
public class AnnulationInscriptionsAdapter implements AnnulationInscriptions {

    private final AnnulerInscriptionsEnPreparation annulerInscriptionsEnPreparation;

    public AnnulationInscriptionsAdapter(AnnulerInscriptionsEnPreparation annulerInscriptionsEnPreparation) {
        this.annulerInscriptionsEnPreparation = annulerInscriptionsEnPreparation;
    }

    @Override
    public int annulerPour(UUID compteId) {
        return annulerInscriptionsEnPreparation.annulerPour(compteId);
    }
}
