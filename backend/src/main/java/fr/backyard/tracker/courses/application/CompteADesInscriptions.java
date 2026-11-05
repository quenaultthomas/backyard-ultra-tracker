package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecture : un Compte a-t-il au moins une Inscription, toute Course et tout statut confondus ? Utilisée à la
 * suppression d'un bénévole ou d'un admin pour choisir entre anonymisation et suppression (9.6a RG9). N'écrit rien ;
 * rejoint la transaction de l'appelant.
 */
@Service
public class CompteADesInscriptions {

    private final DepotInscriptions depotInscriptions;

    public CompteADesInscriptions(DepotInscriptions depotInscriptions) {
        this.depotInscriptions = depotInscriptions;
    }

    @Transactional
    public boolean aDesInscriptions(UUID compteId) {
        return !depotInscriptions.parCompte(compteId).isEmpty();
    }
}
