package fr.backyard.tracker.comptes.domaine;

import java.util.UUID;

/**
 * Port sortant : indique si un Compte a des Inscriptions, toutes Courses et tous statuts confondus. Implémenté hors du
 * contexte comptes, qui ne connaît pas les Courses ; s'exécute dans la transaction de l'appelant (9.6a RG9).
 */
public interface ConsultationInscriptions {

    /** @return vrai si au moins une Inscription du Compte existe (faux pour un Compte inconnu) */
    boolean aDesInscriptions(UUID compteId);
}
