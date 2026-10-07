package fr.backyard.tracker.comptes.domaine;

import java.util.UUID;

/**
 * Port sortant : annule les Inscriptions d'un Compte supprimé aux Courses qui n'ont pas démarré, en conservant les
 * autres. Implémenté hors du contexte comptes, qui ne connaît pas les Courses ; s'exécute dans la transaction de
 * l'appelant.
 */
public interface AnnulationInscriptions {

    /** @return le nombre d'Inscriptions annulées (0 pour un Compte sans Inscription) */
    int annulerPour(UUID compteId);
}
