package fr.backyard.tracker.comptes.domaine;

import java.util.UUID;

/**
 * Port sortant : ferme les sessions ouvertes d'un compte, sauf celle de la requête en cours (changement de mot de
 * passe) ou toutes (suppression du compte). Les sessions des autres comptes ne sont pas touchées.
 */
public interface InvalidationAutresSessions {

    void invaliderAutresSessions(UUID compteId);

    /**
     * Ferme toutes les sessions du compte, celle de la requête en cours comprise. Par défaut non prise en charge, pour
     * que les doubles réduits au changement de mot de passe restent valides ; l'adaptateur la redéfinit.
     *
     * @throws UnsupportedOperationException si l'implémentation ne sait fermer que les autres sessions
     */
    default void invaliderToutesLesSessions(UUID compteId) {
        throw new UnsupportedOperationException("Cette invalidation ne sait pas fermer toutes les sessions.");
    }
}
