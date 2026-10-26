package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DesactivationRefuseeException;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Collection;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Étapes communes aux désactivations d'admin et de bénévole : contrôle du domaine journalisé, journal du changement,
 * fermeture de toutes les sessions du Compte après la validation de la transaction (jamais si elle est annulée).
 * Journal sans pseudo, mot de passe ni nom de Course (RG17).
 */
final class Desactivation {

    private static final Logger JOURNAL = System.getLogger(Desactivation.class.getName());

    private Desactivation() {
    }

    /** Refus du domaine journalisé (motif seul) puis propagé tel quel. */
    static void verifier(Compte cible, UUID appelantId, Collection<Compte> admins) {
        try {
            cible.verifierDesactivable(appelantId, admins);
        } catch (DesactivationRefuseeException refus) {
            JOURNAL.log(Level.INFO, "Désactivation refusée (compte {0}, motif {1})", cible.id(), refus.motif());
            throw refus;
        }
    }

    static void journaliser(UUID cibleId, UUID appelantId, int affectationsRetirees) {
        JOURNAL.log(Level.INFO, "Compte désactivé (compte {0} par {1}, {2} affectation(s) retirée(s))", cibleId,
                appelantId, affectationsRetirees);
    }

    /**
     * Toutes les sessions du Compte sont fermées une fois la désactivation validée : toute autre transaction lit déjà
     * le Compte inactif. Sans transaction en cours (tests sans Spring), fermeture immédiate.
     */
    static void fermerSessionsApresValidation(InvalidationAutresSessions sessions, UUID compteId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sessions.invaliderToutesLesSessions(compteId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sessions.invaliderToutesLesSessions(compteId);
            }
        });
    }
}
