package fr.backyard.tracker.comptes.domaine;

import java.util.List;

/**
 * Décision du nettoyage des photos (correctif audit jalon 10 RG4, RG7) : noms des fichiers à supprimer, nombre de
 * fichiers ignorés (noms inconnus, entrées non ordinaires, fichiers portés dont l'extension diffère du format du
 * Compte) et état du garde-fou. Si {@code suspendu}, aucun orphelin n'est à supprimer et {@code nombreOrphelins} compte
 * les orphelins anciens trouvés, sur {@code nombreConformes} fichiers de photo conformes.
 */
public record PlanNettoyage(List<String> orphelinsASupprimer, List<String> temporairesASupprimer, int ignores,
                            boolean suspendu, int nombreOrphelins, int nombreConformes) {

    public PlanNettoyage {
        orphelinsASupprimer = List.copyOf(orphelinsASupprimer);
        temporairesASupprimer = List.copyOf(temporairesASupprimer);
    }

    /** Nombres seulement : les noms contiennent des jetons. */
    @Override
    public String toString() {
        return "PlanNettoyage[orphelins=" + orphelinsASupprimer.size() + ", temporaires=" + temporairesASupprimer.size()
                + ", ignores=" + ignores + ", suspendu=" + suspendu + "]";
    }
}
