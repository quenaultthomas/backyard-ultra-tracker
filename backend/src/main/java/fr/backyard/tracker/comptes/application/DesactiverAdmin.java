package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AdminMasterNonDesactivableException;
import fr.backyard.tracker.comptes.domaine.AutoDesactivationInterditeException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DernierAdminActifException;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Désactivation d'un Compte ADMIN par l'admin master, dans une transaction : tous les Comptes ADMIN sont relus sous
 * verrou (ordre croissant d'identifiant) avant toute décision, ce qui sérialise deux désactivations concurrentes ; puis
 * contrôles du domaine, Compte enregistré inactif et fermeture de toutes ses sessions après validation. Un admin n'a
 * aucune affectation. Un Compte déjà inactif : aucun effet.
 */
@Service
public class DesactiverAdmin {

    private final DepotComptes depotComptes;
    private final InvalidationAutresSessions invalidationSessions;

    public DesactiverAdmin(DepotComptes depotComptes, InvalidationAutresSessions invalidationSessions) {
        this.depotComptes = depotComptes;
        this.invalidationSessions = invalidationSessions;
    }

    /**
     * @throws CompteNonGerableException            aucun Compte ADMIN ni ADMIN_MASTER pour cet identifiant
     * @throws AdminMasterNonDesactivableException la cible est l'admin master
     * @throws AutoDesactivationInterditeException la cible est l'appelant
     * @throws DernierAdminActifException          la cible est le dernier admin actif
     */
    @Transactional
    public void executer(UUID appelantId, UUID cibleId) {
        List<Compte> admins = depotComptes.trouverParRolePourModification(Role.ADMIN);
        Compte cible = ComptesGeres.ADMINS.cible(parmi(admins, cibleId)
                .or(() -> depotComptes.trouverParIdPourModification(cibleId)));
        Desactivation.verifier(cible, appelantId, admins);
        if (!cible.actif()) {
            return;
        }
        depotComptes.mettreAJour(cible.desactiver());
        Desactivation.journaliser(cibleId, appelantId, 0);
        Desactivation.fermerSessionsApresValidation(invalidationSessions, cibleId);
    }

    private static Optional<Compte> parmi(List<Compte> comptes, UUID id) {
        return comptes.stream().filter(compte -> compte.id().equals(id)).findFirst();
    }
}
