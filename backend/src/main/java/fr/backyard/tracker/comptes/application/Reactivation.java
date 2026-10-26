package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.UUID;

/**
 * Réactivation commune aux routes admins et bénévoles : Compte relu sous verrou, enregistré actif s'il ne l'était pas.
 * Aucun refus métier, aucune réaffectation, aucune session touchée. Journal sans pseudo (RG17).
 */
final class Reactivation {

    private static final Logger JOURNAL = System.getLogger(Reactivation.class.getName());

    private Reactivation() {
    }

    /** @throws CompteNonGerableException aucun Compte géré par cette route pour cet identifiant */
    static void executer(DepotComptes depotComptes, ComptesGeres geres, UUID appelantId, UUID cibleId) {
        Compte cible = geres.cible(depotComptes.trouverParIdPourModification(cibleId));
        if (cible.actif()) {
            return;
        }
        depotComptes.mettreAJour(cible.reactiver());
        JOURNAL.log(Level.INFO, "Compte réactivé (compte {0} par {1})", cibleId, appelantId);
    }
}
