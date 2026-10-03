package fr.backyard.tracker.comptes.domaine;

import java.util.UUID;

/**
 * Port sortant : ferme toutes les sessions ouvertes d'un compte, sauf celle de la requête en cours.
 * Les sessions des autres comptes ne sont pas touchées.
 */
public interface InvalidationAutresSessions {

    void invaliderAutresSessions(UUID compteId);
}
