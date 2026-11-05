package fr.backyard.tracker.comptes.domaine;

import java.util.List;
import java.util.UUID;

/**
 * Port sortant : consultation des Inscriptions d'un Compte. Implémenté hors du contexte comptes, qui ne connaît pas
 * les Courses ; s'exécute dans la transaction de l'appelant (9.6a RG9, 9.6b RG20).
 */
public interface ConsultationInscriptions {

    /** @return vrai si au moins une Inscription du Compte existe, toutes Courses et tous statuts confondus */
    boolean aDesInscriptions(UUID compteId);

    /**
     * @return les Courses non TERMINEE auxquelles le Compte est inscrit (tout statut d'Inscription), dans un ordre
     *         quelconque ; vide pour un Compte inconnu
     */
    List<CourseNonTerminee> coursesNonTerminees(UUID compteId);
}
