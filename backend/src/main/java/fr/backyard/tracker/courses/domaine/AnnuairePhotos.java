package fr.backyard.tracker.courses.domaine;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port sortant : photos publiques des Comptes, que le contexte courses ne connaît que par leur identifiant (10.2 RG2).
 * Lecture seule, en une seule recherche pour tous les identifiants demandés.
 */
public interface AnnuairePhotos {

    /**
     * Jeton opaque de la photo de chaque Compte qui en a une. Un Compte sans photo (dont un Compte anonymisé) ou un
     * identifiant inconnu est absent de la Map (jamais d'exception) ; une collection vide donne une Map vide.
     */
    Map<UUID, String> jetonsPhotoDe(Collection<UUID> idsComptes);
}
