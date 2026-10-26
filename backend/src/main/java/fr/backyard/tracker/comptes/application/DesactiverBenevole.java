package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AutoDesactivationInterditeException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DesaffectationBenevole;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Désactivation d'un Compte BENEVOLE par un admin (ou l'admin master), dans une transaction : Compte relu sous verrou,
 * contrôles du domaine, Compte enregistré inactif, retrait des Courses EN_PREPARATION (Courses verrouillées après le
 * Compte), puis fermeture de toutes ses sessions après validation. Un Compte déjà inactif : aucun effet.
 */
@Service
public class DesactiverBenevole {

    private final DepotComptes depotComptes;
    private final DesaffectationBenevole desaffectation;
    private final InvalidationAutresSessions invalidationSessions;

    public DesactiverBenevole(DepotComptes depotComptes, DesaffectationBenevole desaffectation,
                              InvalidationAutresSessions invalidationSessions) {
        this.depotComptes = depotComptes;
        this.desaffectation = desaffectation;
        this.invalidationSessions = invalidationSessions;
    }

    /**
     * @throws CompteNonGerableException            aucun Compte BENEVOLE pour cet identifiant
     * @throws AutoDesactivationInterditeException la cible est l'appelant (défense en profondeur)
     */
    @Transactional
    public void executer(UUID appelantId, UUID cibleId) {
        Compte cible = ComptesGeres.BENEVOLES.cible(depotComptes.trouverParIdPourModification(cibleId));
        Desactivation.verifier(cible, appelantId, List.of());
        if (!cible.actif()) {
            return;
        }
        depotComptes.mettreAJour(cible.desactiver());
        int retirees = desaffectation.desaffecterDesCoursesEnPreparation(cibleId);
        Desactivation.journaliser(cibleId, appelantId, retirees);
        Desactivation.fermerSessionsApresValidation(invalidationSessions, cibleId);
    }
}
