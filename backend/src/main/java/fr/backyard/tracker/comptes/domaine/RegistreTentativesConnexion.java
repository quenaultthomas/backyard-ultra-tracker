package fr.backyard.tracker.comptes.domaine;

import java.time.Instant;

/**
 * Port sortant : tentatives de connexion par clé (pseudo normalisé).
 * Chaque opération est atomique pour une clé : aucun échec perdu sous accès concurrents.
 */
public interface RegistreTentativesConnexion {

    /** État de la clé à l'instant donné ({@link TentativesConnexion#aucune()} si inconnue). */
    TentativesConnexion constater(String cle, Instant maintenant);

    /** Enregistre un échec selon la politique et retourne le nouvel état de la clé. */
    TentativesConnexion enregistrerEchec(String cle, Instant maintenant, PolitiqueBlocage politique);

    /** Oublie la clé (après une connexion réussie). */
    void effacer(String cle);
}
