package fr.backyard.tracker.comptes.application;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Actions différées à l'issue de la transaction du cas d'usage : après sa validation (jamais si elle est annulée), ou
 * après son annulation. Sans transaction en cours (tests sans Spring), l'action « après validation » est immédiate et
 * l'action « après annulation » n'a pas lieu.
 */
final class ApresTransaction {

    private ApresTransaction() {
    }

    static void apresValidation(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    static void apresAnnulation(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int statut) {
                if (statut != STATUS_COMMITTED) {
                    action.run();
                }
            }
        });
    }
}
