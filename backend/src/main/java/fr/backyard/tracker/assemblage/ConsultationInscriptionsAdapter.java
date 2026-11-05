package fr.backyard.tracker.assemblage;

import fr.backyard.tracker.comptes.domaine.ConsultationInscriptions;
import fr.backyard.tracker.courses.application.CompteADesInscriptions;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pont entre les contextes : implémente le port ConsultationInscriptions de comptes par le cas d'usage de lecture de
 * courses, dans la transaction de la suppression du Compte. Exception nommée des règles ArchUnit (9.6a RG9), comme
 * {@link AnnulationInscriptionsAdapter} et {@link DesaffectationBenevoleAdapter}.
 */
@Component
public class ConsultationInscriptionsAdapter implements ConsultationInscriptions {

    private final CompteADesInscriptions compteADesInscriptions;

    public ConsultationInscriptionsAdapter(CompteADesInscriptions compteADesInscriptions) {
        this.compteADesInscriptions = compteADesInscriptions;
    }

    @Override
    public boolean aDesInscriptions(UUID compteId) {
        return compteADesInscriptions.aDesInscriptions(compteId);
    }
}
